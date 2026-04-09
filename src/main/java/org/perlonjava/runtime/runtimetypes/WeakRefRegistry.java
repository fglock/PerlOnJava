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
     * Special refCount value for non-DESTROY objects that have weak refs.
     * Unlike DESTROY objects (where refCount tracks strong refs accurately),
     * non-DESTROY objects can't have their strong refs counted (because refs
     * created before weaken() activation weren't tracked). Using -2 prevents
     * setLarge() from incrementing/decrementing (which would give wrong counts),
     * and weak ref clearing happens only via explicit undef or scope exit.
     */
    public static final int WEAKLY_TRACKED = -2;

    /**
     * Make a reference weak. The reference no longer counts as a strong reference
     * for refCount purposes. If this was the last strong reference, DESTROY fires.
     * For non-DESTROY objects (refCount == -1), activates minimal tracking so that
     * weak refs can be nullified when the last strong reference is dropped.
     */
    public static void weaken(RuntimeScalar ref) {
        if (!RuntimeScalarType.isReference(ref)) {
            if (ref.type == RuntimeScalarType.UNDEF) return;  // weaken(undef) is a no-op
            throw new PerlCompilerException("Can't weaken a nonreference");
        }
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

        if (base.refCount == -1) {
            MortalList.active = true;
            base.refCount = WEAKLY_TRACKED;
        } else if (base.refCount > 0) {
            // DESTROY-tracked or birth-tracked object: decrement strong count
            // (weak ref doesn't count). Clear refCountOwned because weaken's
            // DEC consumes the ownership — the weak scalar should not trigger
            // another DEC on scope exit or overwrite.
            ref.refCountOwned = false;
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
            if (base.refCount >= 0) {
                base.refCount++;  // restore strong count
                ref.refCountOwned = true;  // restore ownership
            }
            // Note: if MIN_VALUE, object already destroyed — unweaken is a no-op
        }
    }

    /**
     * Remove a scalar from weak ref tracking when it's being overwritten.
     * Returns true if the scalar was indeed a weak ref (so the caller can
     * skip refCount decrement for the old referent).
     */
    public static boolean removeWeakRef(RuntimeScalar ref, RuntimeBase oldReferent) {
        if (!weakScalars.remove(ref)) return false;
        Set<RuntimeScalar> weakRefs = referentToWeakRefs.get(oldReferent);
        if (weakRefs != null) {
            weakRefs.remove(ref);
            if (weakRefs.isEmpty()) referentToWeakRefs.remove(oldReferent);
        }
        return true;
    }

    /**
     * Check if any weak references point to a given referent.
     */
    public static boolean hasWeakRefsTo(RuntimeBase referent) {
        Set<RuntimeScalar> weakRefs = referentToWeakRefs.get(referent);
        return weakRefs != null && !weakRefs.isEmpty();
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
