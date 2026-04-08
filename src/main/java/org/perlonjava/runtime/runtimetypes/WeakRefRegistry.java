package org.perlonjava.runtime.runtimetypes;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * External registry for weak references.
 * <p>
 * Weak ref tracking uses external maps to avoid memory overhead on every RuntimeScalar.
 * The forward map (weakScalars) tracks which RuntimeScalar instances are weak refs.
 * The reverse map (referentToWeakRefs) tracks which weak refs point to each referent.
 */
public class WeakRefRegistry {

    // Forward map: is this RuntimeScalar a weak ref?
    private static final Set<RuntimeScalar> weakScalars =
            Collections.newSetFromMap(new IdentityHashMap<>());

    // Reverse map: referent → set of weak RuntimeScalars pointing to it.
    private static final IdentityHashMap<RuntimeBase, Set<RuntimeScalar>> referentToWeakRefs =
            new IdentityHashMap<>();

    /**
     * Make a reference weak. The reference no longer counts as a strong reference
     * for refCount purposes. If this was the last strong reference, DESTROY fires.
     */
    public static void weaken(RuntimeScalar ref) {
        if (!RuntimeScalarType.isReference(ref)) return;
        if (!(ref.value instanceof RuntimeBase base)) return;
        if (weakScalars.contains(ref)) return;  // already weak

        // If referent was already destroyed, immediately undef the weak ref
        if (base.refCount == Integer.MIN_VALUE) {
            ref.type = RuntimeScalarType.UNDEF;
            ref.value = null;
            return;
        }

        weakScalars.add(ref);
        referentToWeakRefs
                .computeIfAbsent(base, k -> Collections.newSetFromMap(new IdentityHashMap<>()))
                .add(ref);

        // Weak ref doesn't count as strong reference
        if (base.refCount > 0) {
            if (--base.refCount == 0) {
                base.refCount = Integer.MIN_VALUE;
                DestroyDispatch.callDestroy(base);
            }
        }
    }

    /**
     * Check if a RuntimeScalar is a weak reference.
     */
    public static boolean isweak(RuntimeScalar ref) {
        return weakScalars.contains(ref);
    }

    /**
     * Make a weak reference strong again.
     */
    public static void unweaken(RuntimeScalar ref) {
        if (!weakScalars.remove(ref)) return;
        if (ref.value instanceof RuntimeBase base) {
            Set<RuntimeScalar> weakRefs = referentToWeakRefs.get(base);
            if (weakRefs != null) weakRefs.remove(ref);
            if (base.refCount >= 0) base.refCount++;  // restore strong count
            // Note: if MIN_VALUE, object already destroyed — unweaken is a no-op
        }
    }

    /**
     * Clear all weak references to a referent. Called when refCount reaches 0,
     * before DESTROY. Sets all weak scalars pointing to this referent to undef.
     */
    public static void clearWeakRefsTo(RuntimeBase referent) {
        Set<RuntimeScalar> weakRefs = referentToWeakRefs.remove(referent);
        if (weakRefs == null) return;
        for (RuntimeScalar weak : weakRefs) {
            weak.type = RuntimeScalarType.UNDEF;
            weak.value = null;
            weakScalars.remove(weak);
        }
    }
}
