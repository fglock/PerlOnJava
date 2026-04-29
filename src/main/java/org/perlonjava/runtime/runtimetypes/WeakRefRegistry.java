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
     * Fast-path flag: has {@code weaken()} ever been called in this JVM?
     * Once true, stays true (conservative but safe).
     * <p>
     * Used by {@link MortalList#scopeExitCleanupHash} /
     * {@link MortalList#scopeExitCleanupArray} to decide whether the
     * "no blessed objects" fast-exit is safe. Even without blessed objects,
     * unblessed containers may have weak refs that need clearing on scope
     * exit, so those sites must walk elements when weak refs exist.
     */
    public static volatile boolean weakRefsExist = false;

    /**
     * Special refCount value for objects that have weak refs but whose strong
     * refs can't be counted accurately. Used in two cases:
     * <p>
     * 1. Unblessed birth-tracked objects (refCount started at 0) with blessId == 0,
     *    where closure captures and temporary copies bypass {@code setLarge()},
     *    making refCount unreliable.
     * <p>
     * 2. Untracked non-CODE objects (refCount == -1) that acquire weak refs via
     *    {@code weaken()}. These are transitioned to WEAKLY_TRACKED so that
     *    {@code undefine()} and {@code scopeExitCleanup()} can clear weak refs
     *    when a strong reference is dropped. CODE refs are excluded because they
     *    live in both lexicals and the symbol table, making stash references
     *    invisible to refcounting.
     * <p>
     * Setting refCount to WEAKLY_TRACKED prevents {@code setLarge()} from
     * incorrectly decrementing to 0 and triggering false destruction.
     * Weak ref clearing happens only via explicit {@code undef} or scope exit.
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
        if (System.getenv("PJ_WEAKCLEAR_TRACE") != null) {
            System.err.println("[WEAKEN] ref=" + System.identityHashCode(ref)
                + " referent=" + System.identityHashCode(base)
                + " (" + base.getClass().getSimpleName() + ")");
        }
        // Flip the fast-path flag so scopeExit cascades don't bail out
        // via the !blessedObjectExists shortcut when unblessed data has
        // weak refs that need clearing.
        // Phase D-W2b (perf): the very first weaken() also has to
        // backfill `MyVarCleanupStack.liveCounts` so that already-live
        // my-vars become visible to the walker. We gate the per-`my`
        // merge cost on weakRefsExist; without backfill, my-vars
        // declared before the first weaken would never appear in
        // liveCounts and the walker would miss them.
        if (!weakRefsExist) {
            weakRefsExist = true;
            MyVarCleanupStack.snapshotStackToLiveCounts();
        }

        if (base.refCount > 0 && ref.refCountOwned) {
            // Tracked object with a properly-counted reference:
            // decrement strong count (weak ref doesn't count).
            // Only decrement if refCountOwned=true, meaning the hash element
            // or variable's creation incremented the referent's refCount via
            // setLargeRefCounted or incrementRefCountForContainerStore.
            // If refCountOwned=false (e.g., element in an untracked anonymous
            // hash like `{ weakref => $target }`), the store never incremented
            // refCount, so weaken must not decrement either — otherwise we
            // get a double-decrement that causes premature destruction.
            // Clear refCountOwned because weaken's DEC consumes the ownership —
            // the weak scalar should not trigger another DEC on scope exit or overwrite.
            ref.refCountOwned = false;
            base.traceRefCount(-1, "WeakRefRegistry.weaken (decrement on weakening)");
            base.releaseOwner(ref, "weaken");
            if (--base.refCount == 0) {
                if (base.localBindingExists) {
                    // Named container (my %hash / my @array): the local variable
                    // slot holds a strong reference not counted in refCount.
                    // Don't call callDestroy — the container is still alive.
                    // Cleanup will happen at scope exit (scopeExitCleanupHash/Array).
                } else {
                    // No local binding: refCount==0 means truly no strong refs.
                    // Trigger DESTROY + clear weak refs.
                    base.refCount = Integer.MIN_VALUE;
                    DestroyDispatch.callDestroy(base);
                }
            }
            // Note: we do NOT transition unblessed tracked objects to WEAKLY_TRACKED
            // here anymore. The previous transition (base.blessId == 0 → WEAKLY_TRACKED)
            // caused premature clearing of weak refs when ANY strong ref exited scope,
            // even though other strong refs still existed (e.g., Moo's CODE refs in
            // glob slots). Birth-tracked objects maintain accurate refCounts through
            // setLarge(), so we can trust the count. The concern about untracked copies
            // (new RuntimeScalar(RuntimeScalar)) is mitigated by the fact that such
            // copies don't decrement refCount on cleanup (refCountOwned=false), so
            // they can't cause false-positive refCount==0 destruction.
        } else if (base.refCount == -1 && !(base instanceof RuntimeCode)) {
            // Untracked non-CODE object: transition to WEAKLY_TRACKED so that
            // undefine() and scopeExitCleanup() can clear weak refs
            // when a strong reference is dropped. This is a heuristic —
            // it may clear weak refs too early when multiple strong refs
            // exist (since we never counted them), but it's better than
            // never clearing at all. Unblessed objects have no DESTROY,
            // so over-eager clearing causes no side effects beyond the
            // weak ref becoming undef.
            //
            // CODE refs are excluded because they live in BOTH lexicals AND
            // the symbol table (stash). Stash assignments (*Foo::bar = $coderef)
            // don't go through setLarge(), making the stash reference invisible
            // to refcounting. If we transition CODE refs to WEAKLY_TRACKED,
            // setLarge()/scopeExitCleanup() will prematurely clear weak refs
            // when a lexical reference is overwritten — even though the CODE ref
            // is still alive in the stash. This breaks Sub::Quote/Sub::Defer
            // (which use weaken() for back-references) and cascades to break
            // Moo's accessor inlining (51 test failures). See §15.
            ref.refCountOwned = false;
            base.refCount = WEAKLY_TRACKED;
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
        if (System.getenv("PJ_WEAKCLEAR_TRACE") != null) {
            System.err.println("[WEAKREMOVE] ref=" + System.identityHashCode(ref)
                + " oldReferent=" + System.identityHashCode(oldReferent)
                + " (" + (oldReferent == null ? "null" : oldReferent.getClass().getSimpleName()) + ")");
            new Throwable().printStackTrace(System.err);
        }
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
        // Skip clearing weak refs to CODE objects. CODE refs live in both
        // lexicals and the symbol table (stash), but stash assignments
        // (*Foo::bar = $coderef) bypass setLarge(), making the stash reference
        // invisible to refcounting. This causes false refCount==0 via mortal
        // flush when a lexical goes out of scope — even though the CODE ref
        // is still alive in the stash. Since DESTROY is not implemented,
        // there is no behavioral difference from skipping the clear.
        // This is critical for Sub::Quote/Sub::Defer which use weaken()
        // for back-references to deferred subs.
        if (referent instanceof RuntimeCode) return;
        Set<RuntimeScalar> weakRefs = referentToWeakRefs.remove(referent);
        if (weakRefs == null) return;
        if (System.getenv("PJ_WEAKCLEAR_TRACE") != null) {
            System.err.println("[WEAKCLEAR] referent=" + System.identityHashCode(referent)
                + " (" + referent.getClass().getSimpleName() + ") clearing "
                + weakRefs.size() + " weak refs");
            new Throwable().printStackTrace(System.err);
        }
        for (RuntimeScalar weak : weakRefs) {
            weak.type = RuntimeScalarType.UNDEF;
            weak.value = null;
            weakScalars.remove(weak);
        }
    }

    /**
     * Clear weak refs for ALL blessed, non-CODE objects in the registry.
     * Called after flushDeferredCaptures() — at this point the main script
     * has returned and all lexical scopes have exited. Objects with inflated
     * cooperative refCounts (due to JVM temporaries, method-call argument
     * copies, etc.) may still appear "alive" even though no Perl code holds
     * a reference. Clearing their weak refs allows DBIC's leak tracer
     * (which runs in an END block) to see them as "collected".
     * <p>
     * This is safe because:
     * 1. Only weak refs are cleared — the Java objects remain alive
     * 2. CODE refs are excluded (they may still be called from stashes)
     * 3. END blocks that check for leaks run AFTER this method
     */
    /**
     * Phase 4 (refcount_alignment_plan.md): snapshot all referents currently
     * in the weak-ref registry. Used by {@link ReachabilityWalker} to iterate
     * safely (the registry may be modified by concurrent DESTROY / weak-ref
     * clearing during the walk).
     */
    public static java.util.List<RuntimeBase> snapshotWeakRefReferents() {
        return new java.util.ArrayList<>(referentToWeakRefs.keySet());
    }

    public static void clearAllBlessedWeakRefs() {
        // Snapshot the keys to avoid ConcurrentModificationException,
        // since clearWeakRefsTo modifies referentToWeakRefs.
        java.util.List<RuntimeBase> referents =
                new java.util.ArrayList<>(referentToWeakRefs.keySet());
        for (RuntimeBase referent : referents) {
            if (referent instanceof RuntimeCode) continue;
            // Phase H3: skip unblessed containers (ARRAY/HASH) at pre-END
            // time. Sub::Defer's $deferred_info and Sub::Quote's
            // $quoted_info are reachable only via closure captures not
            // traversed by `clearAllBlessedWeakRefs`. Clearing them
            // breaks END-block leak-tracer dispatch loops that call
            // Moo accessors to stringify weak-registry slots.
            if (referent.blessId == 0 && !(referent instanceof RuntimeScalar)) continue;
            // Phase I: skip clearing weak refs to scalars that hold CODE
            // refs or are UNDEF (Sub::Quote/Sub::Defer slot scalars).
            if (referent instanceof RuntimeScalar s) {
                if (s.type == RuntimeScalarType.UNDEF) continue;
                if ((s.type & RuntimeScalarType.REFERENCE_BIT) != 0
                        && s.value instanceof RuntimeCode) {
                    continue;
                }
            }
            clearWeakRefsTo(referent);
        }
    }
}
