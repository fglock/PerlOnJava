package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.backend.bytecode.InterpretedCode;
import org.perlonjava.runtime.regex.RuntimeRegex;
import org.perlonjava.runtime.regex.RuntimeRegexCallback;
import org.perlonjava.runtime.io.BorrowedIOHandle;
import org.perlonjava.runtime.io.ClosedIOHandle;
import org.perlonjava.runtime.io.DupIOHandle;
import org.perlonjava.runtime.io.IOHandle;
import org.perlonjava.runtime.io.InternalPipeHandle;
import org.perlonjava.runtime.io.LayeredIOHandle;
import org.perlonjava.runtime.io.ScalarBackedIO;
import org.perlonjava.runtime.io.SharedTransportIOHandle;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.CODE;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.GLOB;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.TIED_SCALAR;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.UNDEF;

/**
 * Clones one Perl value graph between runtimes while preserving SV identity,
 * aliases, cycles, blessings and weak edges.
 *
 * <p>This is deliberately separate from Storable: ithread cloning does not run
 * serialization hooks and uses one identity map across all runtime roots.</p>
 */
public class RuntimeGraphCloner {
    private final PerlRuntime sourceRuntime;
    private final PerlRuntime targetRuntime;
    private final IdentityHashMap<Object, Object> clones = new IdentityHashMap<>();
    private final IdentityHashMap<IOHandle, InheritedHandlePair> inheritedHandles =
            new IdentityHashMap<>();
    private final List<RuntimeScalar> weakReferences = new ArrayList<>();
    private final List<RuntimeBase> snapshotStrongRoots = new ArrayList<>();
    private final Set<String> skippedClasses;
    private int publicDepth;

    public RuntimeGraphCloner(PerlRuntime sourceRuntime, PerlRuntime targetRuntime) {
        this(sourceRuntime, targetRuntime, Set.of());
    }

    public RuntimeGraphCloner(
            PerlRuntime sourceRuntime, PerlRuntime targetRuntime, Set<String> skippedClasses) {
        this.sourceRuntime = java.util.Objects.requireNonNull(sourceRuntime, "sourceRuntime");
        this.targetRuntime = java.util.Objects.requireNonNull(targetRuntime, "targetRuntime");
        this.skippedClasses = Set.copyOf(skippedClasses);
    }

    public PerlRuntime sourceRuntime() {
        return sourceRuntime;
    }

    public PerlRuntime targetRuntime() {
        return targetRuntime;
    }

    /** Clone a root, completing deferred weak-edge installation at the boundary. */
    @SuppressWarnings("unchecked")
    public <T extends RuntimeBase> T cloneGraph(T value) {
        publicDepth++;
        try {
            return (T) cloneValue(value);
        } finally {
            if (--publicDepth == 0) finishCloneBoundary();
        }
    }

    /** Clone several roots through the same identity map. */
    public List<RuntimeBase> cloneRoots(List<? extends RuntimeBase> roots) {
        publicDepth++;
        try {
            List<RuntimeBase> result = new ArrayList<>(roots.size());
            for (RuntimeBase root : roots) result.add(cloneValue(root));
            return result;
        } finally {
            if (--publicDepth == 0) finishCloneBoundary();
        }
    }

    /**
     * Add thread-entry roots while a runtime snapshot is still being built.
     * Weak edges and observed-address publication are finalized only after the
     * package graph, entry CODE, and arguments all share this identity map.
     */
    List<RuntimeBase> cloneSnapshotRoots(List<? extends RuntimeBase> roots) {
        List<RuntimeBase> result = new ArrayList<>(roots.size());
        for (RuntimeBase root : roots) result.add(cloneValue(root));
        snapshotStrongRoots.addAll(result);
        return result;
    }

    private void finishCloneBoundary() {
        Map<Long, RuntimeBase> observed = sourceRuntime.snapshotReferenceAddresses();
        // A stringified object can remain visible only through a weak Perl
        // edge. ithreads still clone that live SV before invoking CLONE, so
        // ensure it participates in this graph even when no strong root led
        // to it during the ordinary traversal.
        for (RuntimeBase source : observed.values()) {
            if (!source.threadShared && !clones.containsKey(source)) {
                cloneValue(source);
            }
        }
        finishWeakReferences();
        for (Map.Entry<Long, RuntimeBase> entry : observed.entrySet()) {
            RuntimeBase source = entry.getValue();
            RuntimeBase target = source.threadShared
                    ? source : (RuntimeBase) clones.get(source);
            if (target != null) {
                targetRuntime.registerReferenceAddress(entry.getKey(), target);
            }
        }
    }

    /** Complete a runtime snapshot before any child CLONE hooks execute. */
    void finishSnapshot() {
        finishCloneBoundary();
    }

    /** Package/runtime snapshot entry point that retains the shared graph map. */
    RuntimeBase cloneValue(RuntimeBase value) {
        if (value == null) return null;
        // Plain shared storage keeps object identity across ithreads. Tied
        // variables are different: Perl clones the tie callback object into
        // each runtime, while lock/condition operations still refer to one
        // shared synchronization identity.
        if (value.threadShared && !needsSharedRuntimeView(value)) return value;
        Object existing = clones.get(value);
        if (existing != null) return (RuntimeBase) existing;

        if (value instanceof RuntimeScalarReadOnly scalar
                && !RuntimeScalarType.isReference(scalar)) {
            // Plain immutable values may safely retain process identity. A
            // readonly reference is only an immutable reference *slot*; its
            // referent is still part of the ithread snapshot and must pass
            // through this cloner's identity map.
            clones.put(value, value);
            return value;
        }
        if (value instanceof RuntimeCode code) return cloneCode(code);
        if (value instanceof RuntimeStash stash) return cloneStash(stash);
        if (value instanceof RuntimeGlob glob) return cloneGlob(glob);
        if (value instanceof RuntimeScalar scalar) return cloneScalar(scalar);
        if (value instanceof RuntimeArray array) return cloneArray(array);
        if (value instanceof RuntimeHash hash) return cloneHash(hash);
        throw new IllegalArgumentException("Unsupported Perl graph node: " + value.getClass().getName());
    }

    protected RuntimeBase cloneCode(RuntimeCode source) {
        Object existing = clones.get(source);
        if (existing != null) return (RuntimeCode) existing;

        RuntimeCode target;
        if (source instanceof InterpretedCode interpreted) {
            // cloneInterpretedCode copies metadata itself because it is also
            // used for an interpreted body nested inside a lazy RuntimeCode
            // wrapper. Do not copy it a second time here: capture metadata
            // retains every captured scalar, and a duplicate retain leaks
            // shared lexical storage across the child snapshot boundary.
            return cloneInterpretedCode(interpreted);
        } else if (source.subroutine instanceof InterpretedCode interpreted) {
            // Lazy named subs keep their stable RuntimeCode placeholder and
            // install the materialized interpreter body into subroutine/codeObject.
            // Cloning the wrapper as an opaque Java PerlSubroutine would retain
            // the parent's capturedVars array even though the placeholder's
            // metadata had been cloned correctly.
            target = new RuntimeCode((PerlSubroutine) null, source.prototype);
            clones.put(source, target);
            InterpretedCode clonedBody = cloneInterpretedCode(interpreted);
            target.subroutine = clonedBody;
            target.codeObject = clonedBody;
        } else {
            target = new RuntimeCode((PerlSubroutine) null, source.prototype);
            clones.put(source, target);
            if (source.subroutine instanceof CloneablePerlSubroutine cloneable) {
                RuntimeBase[] captures = cloneable.capturedValues();
                RuntimeBase[] clonedCaptures = new RuntimeBase[captures.length];
                for (int i = 0; i < captures.length; i++) {
                    clonedCaptures[i] = cloneValue(captures[i]);
                }
                CloneablePerlSubroutine clonedSubroutine = cloneable.cloneWithCaptures(clonedCaptures);
                target.subroutine = clonedSubroutine;
                target.codeObject = clonedSubroutine;
            } else {
                // Java/native PerlSubroutine implementations carry no Perl pad.
                target.subroutine = source.subroutine;
                target.codeObject = source.codeObject;
                target.methodHandle = source.methodHandle;
            }
        }
        copyCodeMetadata(source, target);
        return target;
    }

    private RuntimeRegexCallback cloneRegexCallback(RuntimeRegexCallback source) {
        Object existing = clones.get(source);
        if (existing != null) return (RuntimeRegexCallback) existing;
        RuntimeRegexCallback target = source.cloneForThread(
                code -> (RuntimeCode) cloneValue(code));
        clones.put(source, target);
        return target;
    }

    private InterpretedCode cloneInterpretedCode(InterpretedCode source) {
        // Register a temporary RuntimeCode first so recursive captured CODE
        // edges resolve. The finished interpreted object replaces it before
        // any public clone boundary returns.
        RuntimeCode placeholder = new RuntimeCode((PerlSubroutine) null, source.prototype);
        clones.put(source, placeholder);

        RuntimeBase[] captures = cloneRuntimeBases(source.capturedVars);
        Object[] constants = source.constants.clone();
        for (int i = 0; i < constants.length; i++) {
            if (constants[i] instanceof RuntimeBase base) constants[i] = cloneValue(base);
        }
        InterpretedCode target;
        try (PerlRuntime.Binding ignored = targetRuntime.bind()) {
            target = new InterpretedCode(
                    source.bytecode, constants, source.stringPool, source.maxRegisters, captures,
                    source.sourceName, source.sourceLine, source.pcToTokenIndex,
                    source.variableRegistry, source.errorUtil, source.strictOptions,
                    source.featureFlags, source.warningFlags, source.compilePackage,
                    source.evalSiteRegistries, source.evalSitePragmaFlags,
                    source.warningBitsString);
        }
        clones.put(source, target);
        target.ourVariableRegistry = source.ourVariableRegistry;
        target.usesLocalization = source.usesLocalization;
        target.futureAsyncAwaitSub = source.futureAsyncAwaitSub;
        target.futureAsyncAwaitFutureClass = source.futureAsyncAwaitFutureClass;
        target.signatureMinArgs = source.signatureMinArgs;
        target.signatureMaxArgs = source.signatureMaxArgs;
        target.signatureSubName = source.signatureSubName;
        target.gotoLabelPcs = source.gotoLabelPcs;
        copyCodeMetadata(source, target);
        return target;
    }

    private RuntimeBase[] cloneRuntimeBases(RuntimeBase[] values) {
        if (values == null) return null;
        RuntimeBase[] result = new RuntimeBase[values.length];
        for (int i = 0; i < values.length; i++) result[i] = cloneValue(values[i]);
        return result;
    }

    private void copyCodeMetadata(RuntimeCode source, RuntimeCode target) {
        target.prototype = source.prototype;
        target.attributes = source.attributes == null ? null : new ArrayList<>(source.attributes);
        target.packageName = source.packageName;
        target.subName = source.subName;
        target.sourcePackage = source.sourcePackage;
        target.autoloadVariableName = source.autoloadVariableName;
        target.isStatic = source.isStatic;
        target.isBuiltin = source.isBuiltin;
        target.isDeclared = source.isDeclared;
        target.isSymbolicReference = source.isSymbolicReference;
        target.isClosurePrototype = source.isClosurePrototype;
        target.tracksRuntimeRegexLexicals = source.tracksRuntimeRegexLexicals;
        target.definitionPending = source.definitionPending;
        if (source.compilerSupplier == null) {
            target.compilerSupplier = null;
        } else {
            // Lazy named-sub suppliers close over the source placeholder and compiler
            // context.  Running that supplier directly in the child materializes the
            // parent CV and leaves the cloned CV undefined.  Defer the cost, but run
            // the source materializer under its owning runtime and then clone the
            // completed definition into this runtime.
            target.compilerSupplier = () -> {
                synchronized (source) {
                    if (source.compilerSupplier != null) {
                        try (PerlRuntime.Binding ignored = sourceRuntime.bind()) {
                            source.compilerSupplier.get();
                        }
                    }
                }
                // Lazy compilation may have registered eval STRING descriptors
                // after the thread's initial runtime snapshot.  Copy only that
                // compiled metadata before installing the completed child CODE.
                sourceRuntime.runtimeCodeState().snapshotCompiledMetadataInto(
                        targetRuntime.runtimeCodeState());
                sourceRuntime.globalState().snapshotCompiledCodeRefsInto(
                        targetRuntime.globalState(), this);
                RuntimeCode completed;
                try (PerlRuntime.Binding ignored = targetRuntime.bind()) {
                    // Reuse this snapshot's identity map. A fresh cloner would
                    // duplicate lexicals that were already copied as runtime
                    // roots before this lazy CV was materialized.
                    clones.remove(source);
                    try {
                        completed = (RuntimeCode) cloneCode(source);
                    } finally {
                        clones.put(source, target);
                    }
                }
                target.adoptDefinitionFrom(completed);
                target.compilerSupplier = null;
                return null;
            };
        }
        target.isMapGrepBlock = source.isMapGrepBlock;
        target.isRegexCallbackPseudoBlock = source.isRegexCallbackPseudoBlock;
        target.isQuotedRegexCallback = source.isQuotedRegexCallback;
        target.isEvalBlock = source.isEvalBlock;
        target.isTryExpressionWrapper = source.isTryExpressionWrapper;
        target.inheritsSelfReference = source.inheritsSelfReference;
        target.explicitlyRenamed = source.explicitlyRenamed;
        target.isConstantCv = source.isConstantCv;
        target.stashInstallPackage = source.stashInstallPackage;
        target.stashInstallSub = source.stashInstallSub;
        target.hadStashRef = source.hadStashRef;
        target.installedViaAnonGlobAssign = source.installedViaAnonGlobAssign;
        target.cvStartFile = source.cvStartFile;
        target.cvStartLine = source.cvStartLine;
        target.deparseSourceText = source.deparseSourceText;
        target.deparseFlags = source.deparseFlags;
        target.deparseSourceOffset = source.deparseSourceOffset;
        target.deparseSourceEnd = source.deparseSourceEnd;
        target.lexicalVariableNames = source.lexicalVariableNames == null
                ? null : new java.util.LinkedHashSet<>(source.lexicalVariableNames);
        target.ourVariableRegistry = source.ourVariableRegistry == null
                ? null : new java.util.LinkedHashMap<>(source.ourVariableRegistry);
        target.stateVariableInitialized = new java.util.HashMap<>(source.stateVariableInitialized);
        target.stateVariable = cloneScalarMap(source.stateVariable);
        target.stateArray = cloneArrayMap(source.stateArray);
        target.stateHash = cloneHashMap(source.stateHash);
        if (source.__SUB__ != null) {
            target.restoreClonedSelfReference(
                    (RuntimeScalar) cloneValue(source.__SUB__));
        }
        if (source.constantValue == null) {
            target.constantValue = null;
        } else {
            target.constantValue = new RuntimeList();
            for (RuntimeBase value : source.constantValue.elements) {
                target.constantValue.elements.add(cloneValue(value));
            }
        }

        if (source.closedOverVariables != null) {
            target.closedOverVariables = new LinkedHashMap<>();
            for (Map.Entry<String, RuntimeBase> entry : source.closedOverVariables.entrySet()) {
                target.closedOverVariables.put(entry.getKey(), cloneValue(entry.getValue()));
            }
        }

        // capturedScalars/capturedAggregates are the authoritative ownership
        // lists built by makeCodeObject()/CREATE_CLOSURE. closedOverVariables
        // is diagnostic/name metadata and can legitimately omit executable
        // captures, so rebuilding ownership from that map allowed a method
        // return to DESTROY a still-live captured object in an ithread.
        if (source.capturedScalars != null) {
            target.capturedScalars = new RuntimeScalar[source.capturedScalars.length];
            for (int i = 0; i < source.capturedScalars.length; i++) {
                target.capturedScalars[i] =
                        (RuntimeScalar) cloneValue(source.capturedScalars[i]);
            }
            try (PerlRuntime.Binding ignored = targetRuntime.bind()) {
                for (RuntimeScalar captured : target.capturedScalars) {
                    captured.retainThreadCloneClosureCapture();
                }
            }
        }
        if (source.capturedAggregates != null) {
            target.capturedAggregates = new RuntimeBase[source.capturedAggregates.length];
            for (int i = 0; i < source.capturedAggregates.length; i++) {
                target.capturedAggregates[i] = cloneValue(source.capturedAggregates[i]);
            }
            try (PerlRuntime.Binding ignored = targetRuntime.bind()) {
                for (RuntimeBase captured : target.capturedAggregates) {
                    captured.retainClosureCapture();
                }
            }
        }
        if (target.capturedScalars != null || target.capturedAggregates != null) {
            target.refCount = 0;
        }
    }

    private Map<String, RuntimeScalar> cloneScalarMap(Map<String, RuntimeScalar> source) {
        Map<String, RuntimeScalar> result = new java.util.HashMap<>();
        for (Map.Entry<String, RuntimeScalar> entry : source.entrySet()) {
            result.put(entry.getKey(), (RuntimeScalar) cloneValue(entry.getValue()));
        }
        return result;
    }

    private Map<String, RuntimeArray> cloneArrayMap(Map<String, RuntimeArray> source) {
        Map<String, RuntimeArray> result = new java.util.HashMap<>();
        for (Map.Entry<String, RuntimeArray> entry : source.entrySet()) {
            result.put(entry.getKey(), (RuntimeArray) cloneValue(entry.getValue()));
        }
        return result;
    }

    private Map<String, RuntimeHash> cloneHashMap(Map<String, RuntimeHash> source) {
        Map<String, RuntimeHash> result = new java.util.HashMap<>();
        for (Map.Entry<String, RuntimeHash> entry : source.entrySet()) {
            result.put(entry.getKey(), (RuntimeHash) cloneValue(entry.getValue()));
        }
        return result;
    }

    private RuntimeScalar cloneScalar(RuntimeScalar source) {
        if (source instanceof ProgramNameVariable) {
            ProgramNameVariable target = new ProgramNameVariable();
            clones.put(source, target);
            copyBase(source, target);
            copyScalarMetadata(source, target);
            return target;
        }
        if (source instanceof ScalarSpecialVariable special) {
            ScalarSpecialVariable target = new ScalarSpecialVariable(
                    special.variableId, special.position);
            clones.put(source, target);
            copyBase(source, target);
            copyScalarMetadata(source, target);
            if (special.lvalue != null) {
                target.lvalue = (RuntimeScalar) cloneValue(special.lvalue);
            }
            return target;
        }
        if (source instanceof TieScalar tied) {
            RuntimeScalar placeholder = new RuntimeScalar();
            clones.put(source, placeholder);
            TieScalar result = new TieScalar(tied.getTiedPackage(),
                    (RuntimeScalar) cloneValue(tied.getPreviousValue()),
                    (RuntimeScalar) cloneValue(tied.getSelf()));
            clones.put(source, result);
            copyBase(source, result);
            return result;
        }

        RuntimeScalar target = new RuntimeScalar();
        clones.put(source, target);
        copyBase(source, target);
        copyScalarMetadata(source, target);

        if (RuntimeScalarType.isReference(source) && source.value == null) {
            // A cleared weak reference can temporarily retain its reference
            // type while its payload has already disappeared.  It is Perl
            // undef at a thread snapshot boundary; copying the stale type
            // produces an impossible child SV that later crashes overload
            // stringification/numification on a null referent.
            target.type = UNDEF;
            target.value = null;
        } else if (source.type == CODE && source.value instanceof RuntimeCode code) {
            target.value = cloneCode(code);
        } else if (source.value instanceof RuntimeRegex regex) {
            target.value = regex.cloneTrackedForThread(this::cloneRegexCallback);
        } else if (source.value instanceof RuntimeIO io) {
            RuntimeIO inherited = cloneRuntimeIO(io);
            if (inherited != null) {
                target.value = inherited;
            } else {
                target.type = UNDEF;
                target.value = null;
            }
        } else if (source.value instanceof ThreadCloneableResource resource) {
            Object inherited = cloneThreadResource(resource);
            if (inherited == null) {
                target.type = UNDEF;
                target.value = null;
            } else {
                target.value = inherited;
            }
        } else if (source.value instanceof RuntimeBase base) {
            if (shouldSkip(base)) {
                target.type = UNDEF;
                target.value = null;
            } else {
                target.value = cloneValue(base);
            }
        } else if (source.value instanceof byte[] bytes) {
            target.value = bytes.clone();
        } else {
            target.value = source.value;
        }

        boolean weak = isWeak(source);
        if (weak) {
            weakReferences.add(target);
        } else {
            restoreScalarReferenceOwnership(source, target);
        }
        if (target.value instanceof RuntimeCode code && code.__SUB__ == null) {
            // JVM CODE commonly stores its generated implementation in
            // codeObject/methodHandle with subroutine == null. The old
            // CloneablePerlSubroutine-only branch therefore left the generated
            // __SUB__ field null after a thread snapshot, making a second-level
            // SUPER call restart from the most-derived invocant and recurse.
            code.restoreClonedSelfReference(target);
        }
        return target;
    }

    private void restoreScalarReferenceOwnership(RuntimeScalar source, RuntimeScalar target) {
        if (!source.refCountOwned
                || (target.type & RuntimeScalarType.REFERENCE_BIT) == 0
                || !(target.value instanceof RuntimeBase referent)) {
            return;
        }
        if (referent.refCount < 0
                && (referent instanceof RuntimeHash || referent instanceof RuntimeArray)) {
            referent.refCount = 0;
        }
        if (referent.refCount < 0) return;
        if (referent.refCount == 0) referent.registerSharedFetchedView();
        referent.traceRefCount(+1, "RuntimeGraphCloner.restoreScalarReferenceOwnership");
        referent.recordOwner(target, "thread clone scalar owner");
        referent.recordActiveOwner(target);
        referent.refCount++;
        referent.hadCountedReference = true;
        target.refCountOwned = true;
        try (PerlRuntime.Binding ignored = targetRuntime.bind()) {
            ScalarRefRegistry.registerRef(target);
        }
    }

    private RuntimeArray cloneArray(RuntimeArray source) {
        RuntimeArray target = new RuntimeArray(
                source.elements instanceof ArraySpecialVariable ? 0 : source.elements.size());
        clones.put(source, target);
        copyBase(source, target);
        target.type = source.type;
        target.strictAutovivify = source.strictAutovivify;
        target.scalarContextSize = source.scalarContextSize;
        target.elementsOwned = source.elementsOwned;
        target.elementsAliased = source.elementsAliased;

        if (source.threadShared && source.type == RuntimeArray.PLAIN_ARRAY) {
            // The aggregate wrapper (including blessing) is runtime-local;
            // only its synchronized backing storage crosses by identity.
            target.elements = source.elements;
        } else if (source.elements instanceof ArraySpecialVariable special) {
            // Regex capture arrays are dynamic views over the bound runtime's
            // match state. Copy the view mode, never its current elements.
            target.elements = special.snapshotView();
        } else if (source.type == RuntimeArray.TIED_ARRAY && source.elements instanceof TieArray tie) {
            target.elements = new TieArray(tie.getTiedPackage(),
                    (RuntimeArray) cloneValue(tie.getPreviousValue()),
                    (RuntimeScalar) cloneValue(tie.getSelf()), target);
        } else {
            try (PerlRuntime.Binding ignored = targetRuntime.bind()) {
                for (RuntimeScalar element : source.elements) {
                    target.addClonedElement((RuntimeScalar) cloneValue(element));
                }
            }
        }
        return target;
    }

    private RuntimeHash cloneHash(RuntimeHash source) {
        RuntimeHash target = new RuntimeHash();
        clones.put(source, target);
        copyBase(source, target);
        target.type = source.type;
        target.taintEnvironmentAliasDescription = source.taintEnvironmentAliasDescription;
        target.isGlobalPackageHash = source.isGlobalPackageHash;
        target.isEnvironmentHash = source.isEnvironmentHash;

        if (source.threadShared && source.type == RuntimeHash.PLAIN_HASH) {
            // See cloneArray: class identity is local, contents are shared.
            target.elements = source.elements;
        } else if (source.type == RuntimeHash.TIED_HASH && source.elements instanceof TieHash tie) {
            target.elements = new TieHash(tie.getTiedPackage(),
                    (RuntimeHash) cloneValue(tie.getPreviousValue()),
                    (RuntimeScalar) cloneValue(tie.getSelf()));
        } else {
            try (PerlRuntime.Binding ignored = targetRuntime.bind()) {
                for (Map.Entry<String, RuntimeScalar> entry : source.elements.entrySet()) {
                    target.putClonedElement(entry.getKey(),
                            (RuntimeScalar) cloneValue(entry.getValue()));
                }
            }
        }
        return target;
    }

    private RuntimeStash cloneStash(RuntimeStash source) {
        RuntimeStash target;
        try (PerlRuntime.Binding ignored = targetRuntime.bind()) {
            target = new RuntimeStash(source.namespace);
        }
        clones.put(source, target);
        copyBase(source, target);
        target.type = source.type;
        // A stash is a live view over the runtime's canonical global slot maps,
        // not an independently-owned hash. GlobalRuntimeState snapshots those
        // maps separately. Replaying the source view through HashSpecialVariable
        // would perform semantic typeglob assignments in the child and can clear
        // slots that were already cloned (notably a package's @ISA array).
        return target;
    }

    private RuntimeGlob cloneGlob(RuntimeGlob source) {
        RuntimeGlob target = new RuntimeGlob(source.globName);
        clones.put(source, target);
        copyBase(source, target);
        target.type = source.type;
        target.value = target;
        target.nameOverride = source.nameOverride;
        target.scalarSlot = (RuntimeScalar) cloneValue(source.scalarSlot);
        target.arraySlot = (RuntimeArray) cloneValue(source.arraySlot);
        target.hashSlot = (RuntimeHash) cloneValue(source.hashSlot);
        if (source.codeSlot != null) {
            target.codeSlot = (RuntimeScalar) cloneValue(source.codeSlot);
        }
        // PerlRuntime installs the child's canonical standard globs first. Other
        // glob handles cross the snapshot using the same explicit resource policy
        // as lexical and aggregate-held handles.
        if (source.IO != null && source.IO.value instanceof RuntimeIO) {
            target.IO = (RuntimeScalar) cloneValue(source.IO);
        } else {
            target.IO = new RuntimeScalar();
        }
        return target;
    }

    private RuntimeIO cloneRuntimeIO(RuntimeIO source) {
        Object existing = clones.get(source);
        if (existing != null) return (RuntimeIO) existing;

        if (source instanceof TieHandle tied) {
            RuntimeIO placeholder = new RuntimeIO();
            clones.put(source, placeholder);
            RuntimeIO previous = cloneRuntimeIO(tied.getPreviousValue());
            RuntimeScalar self = (RuntimeScalar) cloneValue(tied.getSelf());
            TieHandle target = new TieHandle(tied.getTiedPackage(), previous, self);
            clones.put(source, target);
            copyBase(source, target);
            target.currentLineNumber = source.currentLineNumber;
            target.globName = source.globName;
            target.setCloneFlags(source.needsFlushForThreadClone(), source.isAutoFlush());
            return target;
        }

        RuntimeIO target = new RuntimeIO();
        clones.put(source, target);
        target.currentLineNumber = source.currentLineNumber;
        target.globName = source.globName;
        target.setCloneFlags(source.needsFlushForThreadClone(), source.isAutoFlush());

        if (source.directoryIO != null) {
            target.directoryIO = source.directoryIO.inheritedCopy();
            return target;
        }

        InheritedHandlePair pair = inheritHandle(source.ioHandle);
        if (pair == null || pair.child() == null || pair.child() instanceof ClosedIOHandle) {
            clones.remove(source);
            return null;
        }
        // Shared transports replace the parent's raw owner with its lease. For
        // wrapper stacks this also ensures the parent and child have independent
        // layer state over the same underlying transport.
        source.ioHandle = pair.parent();
        target.ioHandle = pair.child();
        try (PerlRuntime.Binding ignored = targetRuntime.bind()) {
            RuntimeIO.addHandle(target.ioHandle);
        }
        return target;
    }

    private InheritedHandlePair inheritHandle(IOHandle source) {
        if (source == null) return null;
        InheritedHandlePair existing = inheritedHandles.get(source);
        if (existing != null) return existing;

        InheritedHandlePair result;
        switch (source.threadInheritancePolicy()) {
            case SHARED_TRANSPORT -> {
                SharedTransportIOHandle[] leases = SharedTransportIOHandle.createPair(source);
                result = new InheritedHandlePair(leases[0], leases[1]);
            }
            case IMPLEMENTATION_COPY -> {
                if (source instanceof InternalPipeHandle pipe) {
                    result = new InheritedHandlePair(source, pipe.inheritedCopy());
                } else if (source instanceof SharedTransportIOHandle shared) {
                    IOHandle child = shared.inheritedCopy();
                    result = child == null ? null : new InheritedHandlePair(source, child);
                } else {
                    result = null;
                }
            }
            case WRAPPER_COPY -> result = inheritWrapper(source);
            case VALUE_COPY -> result = inheritValueHandle(source);
            case CLOSED, UNSUPPORTED -> result = null;
            default -> result = null;
        }
        if (result != null) inheritedHandles.put(source, result);
        return result;
    }

    private InheritedHandlePair inheritWrapper(IOHandle source) {
        if (source instanceof LayeredIOHandle layered) {
            InheritedHandlePair delegate = inheritHandle(layered.getDelegate());
            if (delegate == null) return null;
            LayeredIOHandle parentCopy;
            LayeredIOHandle childCopy;
            try (PerlRuntime.Binding ignored = sourceRuntime.bind()) {
                parentCopy = layered.threadCopy(delegate.parent());
            }
            try (PerlRuntime.Binding ignored = targetRuntime.bind()) {
                childCopy = layered.threadCopy(delegate.child());
            }
            return new InheritedHandlePair(parentCopy, childCopy);
        }
        if (source instanceof DupIOHandle duplicate) {
            return new InheritedHandlePair(source, DupIOHandle.addDup(duplicate));
        }
        if (source instanceof BorrowedIOHandle borrowed) {
            return new InheritedHandlePair(source,
                    new BorrowedIOHandle(borrowed.getDelegate()));
        }
        return null;
    }

    private InheritedHandlePair inheritValueHandle(IOHandle source) {
        if (source instanceof ScalarBackedIO scalar) {
            RuntimeScalar backing = (RuntimeScalar) cloneValue(scalar.backingScalar());
            return new InheritedHandlePair(source, scalar.threadCopy(backing));
        }
        return null;
    }

    private Object cloneThreadResource(ThreadCloneableResource source) {
        Object existing = clones.get(source);
        if (existing != null) return existing;
        Object result = source.cloneForThread(new ThreadCloneableResource.ThreadCloneContext() {
            @Override public PerlRuntime sourceRuntime() { return sourceRuntime; }
            @Override public PerlRuntime targetRuntime() { return targetRuntime; }
            @Override public RuntimeBase clonePerlValue(RuntimeBase value) {
                return cloneValue(value);
            }
        });
        if (result != null) clones.put(source, result);
        return result;
    }

    private record InheritedHandlePair(IOHandle parent, IOHandle child) {}

    private void copyScalarMetadata(RuntimeScalar source, RuntimeScalar target) {
        target.type = source.type;
        target.numericLiteralText = source.numericLiteralText;
        target.firstClassRegexScalar = source.firstClassRegexScalar;
        target.formatPictureTainted = source.formatPictureTainted;
        target.numericContextSeen = source.numericContextSeen;
        target.utf8UncheckedOctets = source.utf8UncheckedOctets;
        target.tainted = source.tainted;
        target.globalCodeRefFqn = source.globalCodeRefFqn;
        target.ioOwner = false;
    }

    private void copyBase(RuntimeBase source, RuntimeBase target) {
        target.threadShared = source.threadShared;
        target.threadSharedIdentity = source.threadSharedIdentity;
        target.threadSharedLifecycle = source.threadSharedLifecycle;
        target.threadSharedRuntimeView = source.threadShared && sourceRuntime != targetRuntime;
        target.threadSharedBlessName = source.threadSharedLifecycle == null
                ? source.threadSharedBlessName : source.threadSharedLifecycle.publishedBlessName;
        if (source.threadShared && target.threadSharedBlessName != null) {
            try (PerlRuntime.Binding ignored = targetRuntime.bind()) {
                target.blessId = NameNormalizer.getBlessId(target.threadSharedBlessName);
            }
        } else {
            target.blessId = cloneBlessId(source.blessId);
        }
        target.localBindingExists = source.localBindingExists;
        target.storedInPackageGlobal = source.storedInPackageGlobal;
        target.isPackageGlobalRoot = source.isPackageGlobalRoot;
        // Refcount/lifecycle queues are reconstructed in the child runtime.
        target.refCount = source.refCount >= 0 ? 0 : -1;
    }

    private int cloneBlessId(int sourceBlessId) {
        if (sourceBlessId == 0) return 0;
        String className;
        try (PerlRuntime.Binding ignored = sourceRuntime.bind()) {
            className = NameNormalizer.getBlessStr(sourceBlessId);
        }
        if (className == null || className.isEmpty()) return 0;
        try (PerlRuntime.Binding ignored = targetRuntime.bind()) {
            return NameNormalizer.getBlessId(className);
        }
    }

    private boolean shouldSkip(RuntimeBase base) {
        if (base.blessId == 0 || skippedClasses.isEmpty()) return false;
        try (PerlRuntime.Binding ignored = sourceRuntime.bind()) {
            return skippedClasses.contains(NameNormalizer.getBlessStr(base.blessId));
        }
    }

    private static boolean needsSharedRuntimeView(RuntimeBase value) {
        if (value instanceof RuntimeArray || value instanceof RuntimeHash) return true;
        if (value instanceof RuntimeScalar scalar) {
            return scalar.type == TIED_SCALAR;
        }
        return false;
    }

    private boolean isWeak(RuntimeScalar scalar) {
        try (PerlRuntime.Binding ignored = sourceRuntime.bind()) {
            return WeakRefRegistry.isweak(scalar);
        }
    }

    private void finishWeakReferences() {
        if (weakReferences.isEmpty()) return;
        try (PerlRuntime.Binding ignored = targetRuntime.bind()) {
            for (RuntimeScalar weakReference : weakReferences) {
                WeakRefRegistry.weakenForSnapshot(weakReference, snapshotStrongRoots);
            }
        }
        weakReferences.clear();
        snapshotStrongRoots.clear();
    }
}
