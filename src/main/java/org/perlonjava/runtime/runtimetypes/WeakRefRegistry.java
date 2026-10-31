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

    private static LifecycleRuntimeState state() {
        return PerlRuntime.current().lifecycleState;
    }

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
    public static boolean weakRefsExist() {
        return state().weakRefsExist;
    }

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

    /** Drop references belonging to the previous top-level script. */
    public static void resetState() {
        state().weakScalars.clear();
        state().referentToWeakRefs.clear();
        // Keep weakRefsExist conservative. Once enabled, the associated
        // lexical/root bookkeeping must remain enabled for this JVM.
    }

    /**
     * Make a reference weak. The reference no longer counts as a strong reference
     * for refCount purposes. If this was the last strong reference, DESTROY fires.
     * For untracked objects (refCount == -1), simply registers in WeakRefRegistry
     * without changing refCount — weak refs to untracked objects are never cleared
     * deterministically (see Strategy A in weaken-destroy.md).
     */
    public static void weaken(RuntimeScalar ref) {
        weaken(ref, java.util.List.of());
    }

    /** Install a cloned weak edge while treating the not-yet-running thread
     * entry graph as a strong root. */
    static void weakenForSnapshot(
            RuntimeScalar ref, java.util.List<? extends RuntimeBase> snapshotRoots) {
        weaken(ref, snapshotRoots);
    }

    private static void weaken(
            RuntimeScalar ref, java.util.List<? extends RuntimeBase> snapshotRoots) {
        if (ref.destroySelfArgument
                || ref instanceof RuntimeScalarReadOnly
                || ref.type == RuntimeScalarType.READONLY_SCALAR) {
            throw new PerlCompilerException("Modification of a read-only value attempted");
        }
        if (!RuntimeScalarType.isReference(ref)) {
            if (ref.type == RuntimeScalarType.UNDEF) return;  // weaken(undef) is a no-op
            throw new PerlCompilerException("Can't weaken a nonreference");
        }
        if (!(ref.value instanceof RuntimeBase base)) return;
        LifecycleRuntimeState state = state();
        if (state.weakScalars.contains(ref)) return;  // already weak

        // Weakness changes the strong root graph even though the scalar's
        // referent field is unchanged.
        MortalList.invalidateAllRootSnapshots();

        // If referent was already destroyed, immediately undef the weak ref.
        // CODE refs are an exception: Sub::Quote compiles named, no-install
        // subs under a localized glob and returns the CODE ref after restoring
        // that glob. The restore path can mark the temporary stash reference as
        // destroyed even though a strong coderef escaped. Revive that stale
        // marker so a later weaken() of the escaped CODE behaves like Perl.
        if (base.refCount == Integer.MIN_VALUE) {
            if (base instanceof RuntimeCode code
                    && code.defined()
                    && !code.currentlyDestroying) {
                base.refCount = -1;
            } else {
                ref.type = RuntimeScalarType.UNDEF;
                ref.value = null;
                ref.refreshArgumentAliasOwnerSlots();
                return;
            }
        }

        state.weakScalars.add(ref);
        ref.refreshArgumentAliasOwnerSlots();
        state.referentToWeakRefs
                .computeIfAbsent(base, k -> Collections.newSetFromMap(new IdentityHashMap<>()))
                .add(ref);
        base.activateOwnerTracking();
        ref.releaseUntrackedReferenceOwner(base);
        // Releasing the final strong slot can synchronously run DESTROY and
        // clear this newly registered weak observer, as Perl does when the
        // weakened variable was the last owner.
        if (!state.weakScalars.contains(ref) || ref.value != base) return;
        ref.releaseClosureCaptureReferentsForWeaken(base);
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
        if (!state.weakRefsExist) {
            MortalList.noteBoundaryWork();
            state.weakRefsExist = true;
            MyVarCleanupStack.snapshotStackToLiveCounts();
            // Prepare the installed-CODE capture root index on the first
            // weaken(), during setup, so the first callback-boundary sweep
            // does not have to build it synchronously while dispatching work.
            GlobalVariable.globalCodeRefEdgeRootsView();
        }

        boolean weakenedLiveLexical = MyVarCleanupStack.isLive(ref);
        boolean weakenedLiveCodeRef = base instanceof RuntimeCode && weakenedLiveLexical;
        if (base.refCount > 0 && ref.refCountOwned) {
            PerlOwnerSlot releasedSlot = ref.countedOwnerSlot();
            boolean identifiedOwner = releasedSlot != null && releasedSlot.isActive()
                    && releasedSlot.referent() == base;
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
            base.releaseActiveOwner(ref);
            if (--base.refCount == 0) {
                if (base instanceof RuntimeCode code && code.hasActiveCodeOwner()) {
                    code.deferFinalReleaseToActiveCode();
                } else if (base.hasSemanticCaptureOwner()) {
                    // A captured pad is an authoritative Perl owner even when
                    // the weak probe being weakened held the last ordinary
                    // counted slot.  Keep the referent alive until the shared
                    // pad cell is released; multiple closures share this one
                    // owner and must not inflate refCount.
                    base.refCount = 1;
                } else if (base.localBindingExists) {
                    // Named container (my %hash / my @array): the local variable
                    // slot holds a strong reference not counted in refCount.
                    // Don't call callDestroy — the container is still alive.
                    // Cleanup will happen at scope exit (scopeExitCleanupHash/Array).
                } else if (base.restoreCountedOwnerSlotRefCount()) {
                    // A surviving counted identity is direct evidence of a Perl
                    // owner. Repair a transient legacy undercount before asking
                    // the root walker to rediscover the same scalar/container edge.
                } else if (!(identifiedOwner && snapshotRoots.isEmpty()
                        && MortalList.canTrustFinalCountedRelease(base))
                        && !(base instanceof RuntimeScalar scalar
                        && scalar.ephemeralScalarReferenceReferent)
                        && hasWeakRefsTo(base)
                        && (base.hasUntrackedReferenceOwnerSlotIdentity()
                            || base.hasDirectOwnerSlotProofForReleasedWeakReferent())) {
                    // Refresh each untracked scalar owner against its current
                    // durable storage before accepting it. A stale slot must
                    // not turn a final release into a WEAKLY_TRACKED referent
                    // that waits for a later sweep to clear its weak observers.
                    base.refCount = WEAKLY_TRACKED;
                } else if (!(identifiedOwner && snapshotRoots.isEmpty()
                        && MortalList.canTrustFinalCountedRelease(base))
                        && !(base instanceof RuntimeScalar scalar
                        && scalar.ephemeralScalarReferenceReferent)
                        && hasWeakRefsTo(base)
                        && (ReachabilityWalker.isReachableFromLiveCodeCaptures(base)
                        || ReachabilityWalker.isReachableFromStrongRoots(base, snapshotRoots)
                        || RuntimeCode.isInstalledPadConstant(base))) {
                    // A temporary probe can be weakened without owning the last
                    // strong Perl reference. Test::Refcount does this when it
                    // weakens a local copy before calling B::svref_2object().
                    // If another strong lexical/global path is still reachable,
                    // keep existing weak refs intact instead of treating this
                    // selective refCount==0 as object death.
                    base.refCount = 1;
                } else {
                    // No local binding: refCount==0 means truly no strong refs.
                    // Trigger DESTROY + clear weak refs.
                    base.refCount = Integer.MIN_VALUE;
                    if (identifiedOwner && snapshotRoots.isEmpty()
                            && MortalList.canTrustFinalCountedRelease(base)) {
                        DestroyDispatch.callDestroyFromCountedOwner(base);
                    } else {
                        DestroyDispatch.callDestroy(base);
                    }
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
        } else if (base.refCount == -1
                && base instanceof RuntimeScalarReadOnly
                && RuntimeCode.isInstalledPadConstant(base)) {
            // A live CV optree owns this constant through an explicit owner slot.
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
            boolean legacyPackageNestedAggregate = base.blessId == 0
                    && (base instanceof RuntimeArray || base instanceof RuntimeHash)
                    && base.storedInPackageGlobal
                    && base.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE) != 0;
            if (legacyPackageNestedAggregate
                    && ReachabilityWalker.hasLiveUntrackedOwnerSlot(base)) {
                // Keep the existing compatibility count at this unmigrated
                // package-root boundary, but give that temporary pin an exact
                // identity. The scalar owner slots remain the Perl ownership
                // evidence and release this bridge when the last one leaves.
                base.refCount = 1;
                base.packageRootRescuedUntrackedAggregate = true;
                if (base.packageRootRescueOwnerSlot == null
                        || !base.packageRootRescueOwnerSlot.isActive()) {
                    base.packageRootRescueOwnerSlot = new PerlOwnerSlot(
                            PerlOwnerSlot.Kind.WEAKLY_TRACKED_COMPATIBILITY_PIN);
                }
                base.packageRootRescueOwnerSlot.acquireWeaklyTrackedCompatibilityPin(base);
            } else {
                // Keep the compatibility sentinel while an untracked value is
                // between expression evaluation and installation in a durable
                // slot. Owner release hooks finish weak cleanup; do not infer
                // Perl ownership from a general root-graph walk here.
                base.refCount = WEAKLY_TRACKED;
            }
        }
        boolean shouldCheckLiveCodeRef = weakenedLiveCodeRef
                && codeRefHasCountedOwners(base)
                && !ModuleInitGuard.inModuleInit();
        if (base instanceof RuntimeCode code
                && code.refCount >= 0
                && state.weakRefsExist
                && (shouldCheckLiveCodeRef
                || (code.hadStashRef
                && code.stashRefCount <= 0
                && !isInstalledGlobalCodeRef(code)))) {
            // Keep this path targeted to the CODE referent. DBIC weakens
            // callback CODE refs in hot cursor paths; running a full
            // ReachabilityWalker sweep here forces repeated System.gc() calls.
            // The CODE-specific stale-ref decision below is enough: live CODE
            // refs are protected by clearWeakRefsTo()/shouldKeepCodeWeakRefs,
            // while expired anonymous or former-stash CODE refs can still clear
            // their weak registry entries immediately.
            if (hasWeakRefsTo(code)
                    && code.stashRefCount <= 0
                    && !isInstalledGlobalCodeRef(code)) {
                // clearWeakRefsTo() makes the final decision from stash state,
                // active frames, and explicit owner-slot identities. A scalar
                // root walk here duplicates that proof and misses the purpose
                // of the owner ledger.
                clearWeakRefsTo(code);
            }
        }
    }

    private static boolean codeRefHasCountedOwners(RuntimeBase base) {
        return base.refCount > 0 || base.activeOwnerCount() > 0
                || base.hasSemanticCaptureOwner();
    }

    /**
     * Check if a RuntimeScalar is a weak reference.
     */
    public static boolean isweak(RuntimeScalar ref) {
        return state().weakScalars.contains(ref);
    }

    /**
     * Make a weak reference strong again.
     */
    public static void unweaken(RuntimeScalar ref) {
        LifecycleRuntimeState state = state();
        if (!state.weakScalars.contains(ref)) return;
        MortalList.invalidateAllRootSnapshots();
        if (!state.weakScalars.remove(ref)) return;
        if (ref.value instanceof RuntimeBase base) {
            Set<RuntimeScalar> weakRefs = state.referentToWeakRefs.get(base);
            if (weakRefs != null) {
                weakRefs.remove(ref);
                if (weakRefs.isEmpty()) state.referentToWeakRefs.remove(base);
            }
            if (base.refCount >= 0) {
                base.traceRefCount(+1, "WeakRefRegistry.unweaken (restore strong count)");
                base.refCount++;  // restore strong count
                ref.refCountOwned = true;  // restore ownership
                base.hadCountedReference = true;
                base.recordOwner(ref, "WeakRefRegistry.unweaken");
                base.recordActiveOwner(ref);
            }
            RuntimeScalar.incrementRefCountForContainerStore(ref);
            // Note: if MIN_VALUE, object already destroyed — unweaken is a no-op
        }
        ref.refreshArgumentAliasOwnerSlots();
    }

    /**
     * Remove a scalar from weak ref tracking when it's being overwritten.
     * Returns true if the scalar was indeed a weak ref (so the caller can
     * skip refCount decrement for the old referent).
     */
    public static boolean removeWeakRef(RuntimeScalar ref, RuntimeBase oldReferent) {
        LifecycleRuntimeState state = state();
        if (!state.weakScalars.contains(ref)) return false;
        MortalList.invalidateAllRootSnapshots();
        if (!state.weakScalars.remove(ref)) return false;
        if (System.getenv("PJ_WEAKCLEAR_TRACE") != null) {
            System.err.println("[WEAKREMOVE] ref=" + System.identityHashCode(ref)
                + " oldReferent=" + System.identityHashCode(oldReferent)
                + " (" + (oldReferent == null ? "null" : oldReferent.getClass().getSimpleName()) + ")");
            new Throwable().printStackTrace(System.err);
        }
        Set<RuntimeScalar> weakRefs = state.referentToWeakRefs.get(oldReferent);
        if (weakRefs != null) {
            weakRefs.remove(ref);
            if (weakRefs.isEmpty()) state.referentToWeakRefs.remove(oldReferent);
        }
        return true;
    }

    /**
     * Check if any weak references point to a given referent.
     */
    public static boolean hasWeakRefsTo(RuntimeBase referent) {
        Set<RuntimeScalar> weakRefs = state().referentToWeakRefs.get(referent);
        return weakRefs != null && !weakRefs.isEmpty();
    }

    /** Finish tracked cleanup when its final owner slot leaves. */
    static void onStrongOwnerSlotReleased(RuntimeBase referent) {
        if (referent instanceof RuntimeCode code) {
            code.releasePadConstantsForUntrackedOwnerSlot();
        }
        boolean zeroCountOwnerSlotsExhausted = referent != null
                && referent.refCount == 0
                && !(referent instanceof RuntimeCode)
                && !GlobalVariable.isPackageRootedAggregate(referent)
                && MortalList.canTrustFinalCountedRelease(referent);
        if (referent != null
                && ((referent.deferredFinalReleaseForUncountedOwners
                        && referent.refCount == WEAKLY_TRACKED)
                    || zeroCountOwnerSlotsExhausted)
                && !referent.hasOwnerSlots()
                && PerlRuntime.currentOrNull() != null
                && !referent.localBindingExists
                && referent.captureCount == 0
                && !referent.hasPendingReturnOwner()
                && referent.countedOwnerSlotCount() == 0
                && !(referent instanceof RuntimeScalarReadOnly
                    && RuntimeCode.isInstalledPadConstant(referent))) {
            referent.deferredFinalReleaseForUncountedOwners = false;
            referent.refCount = Integer.MIN_VALUE;
            DestroyDispatch.callDestroy(referent);
            return;
        }
        PerlOwnerSlot packagePin = referent == null
                ? null : referent.packageRootRescueOwnerSlot;
        if (referent != null
                && referent.packageRootRescuedUntrackedAggregate
                && packagePin != null
                && packagePin.isActive()
                && referent.refCount > 0
                && referent.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE) == 0
                && PerlRuntime.currentOrNull() != null
                && !(referent instanceof RuntimeScalarReadOnly
                    && RuntimeCode.isInstalledPadConstant(referent))) {
            boolean hasWeakObservers = hasWeakRefsTo(referent);
            boolean hasOtherPerlOwner = referent.refCount > 1
                    || referent.localBindingExists
                    || referent.captureCount > 0
                    || referent.hasPendingReturnOwner()
                    || referent.countedOwnerSlotCount() != 0
                    || referent.hasUncountedPerlOwnerSlotIdentity();
            referent.packageRootRescuedUntrackedAggregate = false;
            referent.packageRootRescueOwnerSlot = null;
            boolean finalWeakRelease = !hasOtherPerlOwner && hasWeakObservers;
            if (finalWeakRelease) {
                referent.refCount = Integer.MIN_VALUE;
            } else if (hasOtherPerlOwner && referent.refCount == 1 && hasWeakObservers) {
                referent.refCount = WEAKLY_TRACKED;
            } else {
                referent.refCount = referent.refCount == 1
                        ? -1 : referent.refCount - 1;
            }
            packagePin.release();
            if (finalWeakRelease) DestroyDispatch.callDestroy(referent);
            return;
        }
        if (referent == null
                || referent.refCount != WEAKLY_TRACKED
                || referent.hasOwnerSlots()
                || PerlRuntime.currentOrNull() == null
                || !hasWeakRefsTo(referent)
                || referent.localBindingExists
                || referent.captureCount > 0
                || referent.hasPendingReturnOwner()
                || referent.countedOwnerSlotCount() != 0
                || (referent instanceof RuntimeScalarReadOnly
                    && RuntimeCode.isInstalledPadConstant(referent))) return;

        if (ReachabilityWalker.correctiveSweepsEnabled()) {
            MortalList.requestTargetedWeakSweep(referent);
            return;
        }

        if (referent.blessId != 0
                || referent instanceof RuntimeArray
                || referent instanceof RuntimeHash) {
            referent.refCount = Integer.MIN_VALUE;
            DestroyDispatch.callDestroy(referent);
        } else {
            clearWeakRefsTo(referent);
        }
    }

    /** Finalize an untracked value after its scalar owner has left scope. */
    static void finalizeUntrackedAfterOwnerScopeExit(RuntimeBase referent) {
        if (referent == null || referent.refCount != -1
                || PerlRuntime.currentOrNull() == null
                || referent.hasOwnerSlots() || referent.localBindingExists
                || referent.captureCount > 0 || referent.hasPendingReturnOwner()
                || referent.countedOwnerSlotCount() != 0
                || MortalList.temporaryRootDirectlyReferences(referent)
                || (referent instanceof RuntimeCode code && RuntimeCode.isActiveCode(code))) {
            return;
        }
        referent.refCount = Integer.MIN_VALUE;
        DestroyDispatch.callDestroy(referent);
    }

    /** Finish an aggregate's cleanup after its active lexical pad owner left. */
    static void onActiveLexicalOwnerReleased(RuntimeBase referent) {
        if (referent == null || PerlRuntime.currentOrNull() == null
                || referent.hasOwnerSlots() || referent.captureCount > 0
                || referent.hasPendingReturnOwner()
                || referent.countedOwnerSlotCount() != 0
                || referent.localBindingExists || referent.refCount > 0
                || referent.refCount == Integer.MIN_VALUE) return;

        boolean untrackedAggregate = referent.blessId == 0
                && (referent instanceof RuntimeArray || referent instanceof RuntimeHash);
        if (untrackedAggregate && referent.refCount == 1) {
            referent.refCount = WEAKLY_TRACKED;
        }

        // Keep the compatibility path's targeted scheduling when explicitly
        // enabled. The default-off path below finalizes directly from slot
        // identity and does not need a later reachability pass.
        if (ReachabilityWalker.correctiveSweepsEnabled()) {
            if (referent.refCount == WEAKLY_TRACKED && hasWeakRefsTo(referent)) {
                MortalList.requestTargetedWeakSweep(referent);
            }
            return;
        }

        if (referent.blessId != 0) {
            referent.refCount = Integer.MIN_VALUE;
            DestroyDispatch.callDestroy(referent);
            return;
        }

        if ((referent.refCount == WEAKLY_TRACKED || referent.refCount == 0)
                && hasWeakRefsTo(referent)) {
            clearWeakRefsTo(referent);
        }
    }

    /**
     * Clear all weak references to a referent. Called when refCount reaches 0,
     * before DESTROY. Sets all weak scalars pointing to this referent to undef.
     */
    public static void clearWeakRefsTo(RuntimeBase referent) {
        clearWeakRefsTo(referent, false);
    }

    static void clearWeakRefsTo(RuntimeBase referent, boolean finalCountedOwner) {
        // A captured pad is a semantic strong owner even when the selective
        // refcount has reached a weak-tracking sentinel. All destruction and
        // sweeping paths funnel through this method, so preserve that edge at
        // the weak-clearing boundary as well as in refcount transitions.
        if (referent.hasSemanticCaptureOwner()) return;
        // CODE refs can live in both lexicals and the symbol table. Do not
        // clear weak CODE refs while a stash slot still owns the sub, but do
        // clear anonymous CODE refs when their selective refcount reaches zero.
        // Sub::Quote/Sub::Defer store weak backrefs to anonymous deferred subs;
        // leaving those weak scalars defined keeps the whole quoted/deferred
        // metadata graph Java-reachable and leaks until global destruction.
        if (!finalCountedOwner && referent instanceof RuntimeCode code
                && shouldKeepCodeWeakRefs(code)) return;
        LifecycleRuntimeState state = state();
        Set<RuntimeScalar> weakRefs = state.referentToWeakRefs.remove(referent);
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
            state.weakScalars.remove(weak);
        }
    }

    private static boolean isInstalledGlobalCodeRef(RuntimeCode code) {
        // Anonymous CODE refs have never occupied a package slot, so their
        // common weak-clear path can avoid consulting the package-root index.
        // Formerly installed refs remain in the index while any alias occupies
        // a package slot, independent of their current stash reference count.
        if (!code.hadStashRef) return false;
        return GlobalVariable.installedGlobalCodeRefsView().contains(code);
    }

    private static boolean shouldKeepCodeWeakRefs(RuntimeCode code) {
        boolean installed = isInstalledGlobalCodeRef(code);
        boolean trace = System.getenv("PJ_WEAKCLEAR_TRACE") != null
                && code.hadStashRef && code.stashRefCount <= 0 && !installed;
        if (trace) {
            System.err.println("[WEAKCODE-CHECK] code=" + System.identityHashCode(code)
                    + " refCount=" + code.refCount + " stashRefCount=" + code.stashRefCount
                    + " installed=" + installed + " slots=" + code.ownerSlotSnapshot().size());
        }
        if (code.stashRefCount > 0 || installed) return true;
        // The active frame is already a definitive strong owner. Check it
        // before scanning the live scalar registries, which can be large and
        // sits on the callback dispatch path when a quiet sweep runs.
        if (RuntimeCode.isActiveCode(code)) return true;
        // Stale frame-temporary scalar cells can leave counted identities on
        // a CODE after their Perl scope exits. Retire those cells before using
        // the remaining owner ledger as direct liveness evidence.
        code.releaseTemporaryCodeScalarOwners();
        if (hasWeakPreservingCodeOwnerSlot(code)) {
            if (trace) {
                for (PerlOwnerSlot slot : code.ownerSlotSnapshot()) {
                    RuntimeScalar owner = slot.countedScalarOwner();
                    System.err.println("[WEAKCODE-SLOT] kind=" + slot.kind()
                            + " id=" + slot.identity() + " native=" + slot.isNativeOwner()
                            + " owner=" + (owner == null ? "null"
                                : System.identityHashCode(owner))
                            + " ownerType=" + (owner == null ? "-" : owner.type)
                            + " valueIsCode=" + (owner != null && owner.value == code)
                            + " refCountOwned=" + (owner != null && owner.refCountOwned)
                            + " registered=" + (owner != null && MyVarCleanupStack.isRegistered(owner))
                            + " scopeExited=" + (owner != null && owner.scopeExited)
                            + " local=" + (owner != null && owner.localBindingExists)
                            + " package=" + (owner != null && owner.isPackageGlobalRoot)
                            + " container=" + (owner != null && owner.containerOwner != null));
                }
            }
            return true;
        }
        // A Perl-visible owner must have an active slot identity. Sweep-local
        // reachability snapshots and scalar registry scans are not ownership.
        return false;
    }

    private static boolean hasWeakPreservingCodeOwnerSlot(RuntimeCode code) {
        for (PerlOwnerSlot slot : code.ownerSlotSnapshot()) {
            // CODE_SLOT identities also represent compatibility pins retained
            // for compiled call sites after a glob is deleted. The visible
            // stash checks above distinguish live Perl CODE owners from those
            // Java-facing pins.
            if (slot.isActive() && slot.kind() != PerlOwnerSlot.Kind.CODE_SLOT) return true;
        }
        return false;
    }

    /**
     * Clear weak refs for ALL blessed, non-CODE objects in the registry.
     * Called after flushDeferredCaptures() — at this point the main script
     * has returned and all lexical scopes have exited. Objects with inflated
     * selective refCounts (due to JVM temporaries, method-call argument
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
        return new java.util.ArrayList<>(state().referentToWeakRefs.keySet());
    }

}
