package org.perlonjava.runtime.runtimetypes;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Marker and first-tranche synchronization policy for threads::shared. */
public final class SharedPerlStorage {
    private SharedPerlStorage() {}

    public static RuntimeBase referent(RuntimeScalar reference) {
        if (reference == null) return null;
        return reference.value instanceof RuntimeBase base ? base : reference;
    }

    public static RuntimeBase share(RuntimeScalar reference) {
        RuntimeBase root = referent(reference);
        if (root == null) throw new IllegalArgumentException("share requires a scalar, array, or hash reference");
        markGraph(root, Collections.newSetFromMap(new IdentityHashMap<>()));
        return root;
    }

    public static boolean isShared(RuntimeScalar reference) {
        RuntimeBase root = referent(reference);
        return root != null && root.threadShared;
    }

    public static RuntimeScalar sharedClone(RuntimeScalar reference) {
        PerlRuntime runtime = PerlRuntime.current();
        RuntimeScalar clone = new RuntimeGraphCloner(runtime, runtime).cloneGraph(reference);
        share(clone);
        return clone;
    }

    private static void markGraph(RuntimeBase value, Set<RuntimeBase> seen) {
        if (value == null || !seen.add(value)) return;
        if (value.blessId != 0) throw new IllegalArgumentException("Sharing blessed values is not supported");
        if (value instanceof RuntimeScalar scalar) {
            if (scalar.type == RuntimeScalarType.TIED_SCALAR) {
                throw new IllegalArgumentException("Sharing tied values is not supported");
            }
            scalar.threadShared = true;
            if (scalar.value instanceof RuntimeBase nested) markGraph(nested, seen);
            return;
        }
        if (value instanceof RuntimeArray array) {
            if (array.type != RuntimeArray.PLAIN_ARRAY) {
                throw new IllegalArgumentException("Sharing tied arrays is not supported");
            }
            for (RuntimeScalar element : array.elements) markGraph(element, seen);
            array.elements = Collections.synchronizedList(array.elements);
            array.threadShared = true;
            return;
        }
        if (value instanceof RuntimeHash hash) {
            if (hash.type != RuntimeHash.PLAIN_HASH) {
                throw new IllegalArgumentException("Sharing tied hashes is not supported");
            }
            for (RuntimeScalar element : hash.elements.values()) markGraph(element, seen);
            hash.elements = Collections.synchronizedMap(hash.elements);
            hash.threadShared = true;
            return;
        }
        throw new IllegalArgumentException("Unsupported shared value type " + value.getClass().getName());
    }
}
