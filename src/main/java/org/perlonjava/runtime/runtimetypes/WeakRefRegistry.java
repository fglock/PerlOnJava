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
     * Special refCount value for unblessed birth-tracked objects that have weak
     * refs but whose strong refs can't be counted accurately. These objects were
     * born via {@code createReferenceWithTrackedElements} (refCount started at 0)
     * but have blessId == 0 (unblessed), meaning closure captures and temporary
     * copies bypass {@code setLarge()}, making refCount unreliable.
     * <p>
     * Setting refCount to WEAKLY_TRACKED prevents {@code setLarge()} from
     * incorrectly decrementing to 0 and triggering false destruction.
     * Weak ref clearing happens only via explicit {@code undef} or scope exit.
     * <p>
     * Note: untracked objects (refCount == -1) are NOT transitioned to
     * WEAKLY_TRACKED — they stay at -1 and their weak refs are never cleared
     * deterministically. This distinction fixes the qr-72922.t regression
     * where untracked regex objects had weak refs prematurely cleared.
     */
    public static final int WEAKLY_TRACKED = -2;

    /**
     * Make a reference weak. The reference no longer counts as a strong reference
     * for refCount purposes. If this was the last strong reference, DESTROY fires.
     * For untracked objects (refCount == -1), simply registers in WeakRefRegistry
     * without changing refCount — weak refs to untracked objects are never cleared
     * deterministically (see Strategy A in weaken-destroy.md).
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

        if (base.refCount > 0) {
            // Tracked object: decrement strong count (weak ref doesn't count).
            // Clear refCountOwned because weaken's DEC consumes the ownership —
            // the weak scalar should not trigger another DEC on scope exit or overwrite.
            ref.refCountOwned = false;
            if (--base.refCount == 0) {
                // No strong refs remain — trigger DESTROY + clear weak refs.
                base.refCount = Integer.MIN_VALUE;
                DestroyDispatch.callDestroy(base);
            } else if (base.blessId == 0) {
                // Unblessed tracked object with remaining strong refs: transition to
                // WEAKLY_TRACKED because closure captures and temporary copies
                // via new RuntimeScalar(RuntimeScalar) aren't tracked in refCount.
                // Without this transition, a mortal flush can bring refCount to 0
                // and trigger clearWeakRefsTo while the object is still alive
                // (e.g., Sub::Quote deferred coderefs captured by closures).
                // Since unblessed objects don't have DESTROY, there's no
                // semantic cost to switching off refCount tracking.
                base.refCount = WEAKLY_TRACKED;
            }
        }
        // For untracked objects (refCount == -1): register only, no refCount change.
        // Unlike the old code, we do NOT transition -1 → WEAKLY_TRACKED here.
        // This fixes qr-72922.t where untracked regex objects had their weak refs
        // prematurely cleared on undef when other strong refs still existed.
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
