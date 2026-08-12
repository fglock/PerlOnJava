package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.backend.bytecode.InterpretedCode;
import org.perlonjava.runtime.regex.RuntimeRegex;

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
    private final List<RuntimeScalar> weakReferences = new ArrayList<>();
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
            if (--publicDepth == 0) finishWeakReferences();
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
            if (--publicDepth == 0) finishWeakReferences();
        }
    }

    /** Package/runtime snapshot entry point that retains the shared graph map. */
    RuntimeBase cloneValue(RuntimeBase value) {
        if (value == null) return null;
        if (value.threadShared) return value;
        Object existing = clones.get(value);
        if (existing != null) return (RuntimeBase) existing;

        if (value instanceof RuntimeScalarReadOnly) {
            // Constants are immutable and may safely retain process identity.
            clones.put(value, value);
            return value;
        }
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
            target = cloneInterpretedCode(interpreted);
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
                    completed = (RuntimeCode) new RuntimeGraphCloner(
                            sourceRuntime, targetRuntime, skippedClasses).cloneCode(source);
                }
                target.adoptDefinitionFrom(completed);
                target.compilerSupplier = null;
                return null;
            };
        }
        target.isMapGrepBlock = source.isMapGrepBlock;
        target.isEvalBlock = source.isEvalBlock;
        target.isTryExpressionWrapper = source.isTryExpressionWrapper;
        target.cvStartFile = source.cvStartFile;
        target.cvStartLine = source.cvStartLine;
        target.deparseSourceText = source.deparseSourceText;
        target.deparseFlags = source.deparseFlags;
        target.deparseSourceOffset = source.deparseSourceOffset;
        target.deparseSourceEnd = source.deparseSourceEnd;
        target.lexicalVariableNames = source.lexicalVariableNames == null
                ? null : new java.util.LinkedHashSet<>(source.lexicalVariableNames);
        target.stateVariableInitialized = new java.util.HashMap<>(source.stateVariableInitialized);
        target.stateVariable = cloneScalarMap(source.stateVariable);
        target.stateArray = cloneArrayMap(source.stateArray);
        target.stateHash = cloneHashMap(source.stateHash);
        target.constantValue = source.constantValue;

        if (source.closedOverVariables != null) {
            target.closedOverVariables = new LinkedHashMap<>();
            for (Map.Entry<String, RuntimeBase> entry : source.closedOverVariables.entrySet()) {
                target.closedOverVariables.put(entry.getKey(), cloneValue(entry.getValue()));
            }
            target.capturedScalars = target.closedOverVariables.values().stream()
                    .filter(RuntimeScalar.class::isInstance).map(RuntimeScalar.class::cast)
                    .toArray(RuntimeScalar[]::new);
            target.capturedAggregates = target.closedOverVariables.values().stream()
                    .filter(value -> value instanceof RuntimeArray || value instanceof RuntimeHash)
                    .toArray(RuntimeBase[]::new);
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

        if (source.type == CODE && source.value instanceof RuntimeCode code) {
            target.value = cloneCode(code);
        } else if (source.value instanceof RuntimeRegex regex) {
            target.value = regex.cloneTracked();
        } else if (source.value instanceof RuntimeIO) {
            // Java channels/descriptors have no portable ithread duplication
            // semantics. Non-standard resource scalars become undef.
            target.type = UNDEF;
            target.value = null;
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

        if (isWeak(source)) weakReferences.add(target);
        if (target.value instanceof RuntimeCode code
                && code.subroutine instanceof CloneablePerlSubroutine cloneable
                && code.__SUB__ == null) {
            code.__SUB__ = target;
            cloneable.setSelfReference(target);
        } else if (target.value instanceof InterpretedCode interpreted && interpreted.__SUB__ == null) {
            interpreted.__SUB__ = target;
        }
        return target;
    }

    private RuntimeArray cloneArray(RuntimeArray source) {
        RuntimeArray target = new RuntimeArray(source.elements.size());
        clones.put(source, target);
        copyBase(source, target);
        target.type = source.type;
        target.strictAutovivify = source.strictAutovivify;
        target.scalarContextSize = source.scalarContextSize;
        target.elementsOwned = source.elementsOwned;
        target.elementsAliased = source.elementsAliased;

        if (source.type == RuntimeArray.TIED_ARRAY && source.elements instanceof TieArray tie) {
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

        if (source.type == RuntimeHash.TIED_HASH && source.elements instanceof TieHash tie) {
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
        // Filehandles are runtime resources. Standard handles are installed by
        // PerlRuntime; detached/non-standard handles become an empty IO slot.
        target.IO = new RuntimeScalar();
        return target;
    }

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
        target.blessId = cloneBlessId(source.blessId);
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

    private boolean isWeak(RuntimeScalar scalar) {
        try (PerlRuntime.Binding ignored = sourceRuntime.bind()) {
            return WeakRefRegistry.isweak(scalar);
        }
    }

    private void finishWeakReferences() {
        if (weakReferences.isEmpty()) return;
        try (PerlRuntime.Binding ignored = targetRuntime.bind()) {
            for (RuntimeScalar weakReference : weakReferences) {
                WeakRefRegistry.weaken(weakReference);
            }
        }
        weakReferences.clear();
    }
}
