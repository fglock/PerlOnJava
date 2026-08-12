package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.runtime.regex.RuntimeRegex;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

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
    private int publicDepth;

    public RuntimeGraphCloner(PerlRuntime sourceRuntime, PerlRuntime targetRuntime) {
        this.sourceRuntime = java.util.Objects.requireNonNull(sourceRuntime, "sourceRuntime");
        this.targetRuntime = java.util.Objects.requireNonNull(targetRuntime, "targetRuntime");
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

    protected RuntimeBase cloneCode(RuntimeCode code) {
        throw new IllegalStateException("CODE cloning requires Phase 15 capture metadata");
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
            target.value = cloneValue(base);
        } else if (source.value instanceof byte[] bytes) {
            target.value = bytes.clone();
        } else {
            target.value = source.value;
        }

        if (isWeak(source)) weakReferences.add(target);
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
        for (Map.Entry<String, RuntimeScalar> entry : source.elements.entrySet()) {
            RuntimeScalar cloned = (RuntimeScalar) cloneValue(entry.getValue());
            try (PerlRuntime.Binding ignored = targetRuntime.bind()) {
                target.elements.put(entry.getKey(), cloned);
            }
        }
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
