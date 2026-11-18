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

    /** Whether this runtime currently has any registered weak observers. */
    public static boolean hasAnyWeakRefs() {
        return !state().referentToWeakRefs.isEmpty();
    }

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
     * For untracked objects, simply registers in WeakRefRegistry
     * without changing refCount — weak refs to untracked objects are never cleared
     * deterministically (see Strategy A in weaken-destroy.md).
     */
    public static void weaken(RuntimeScalar ref) {
        weakenInternal(ref);
    }

    private static void weakenInternal(RuntimeScalar ref) {
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

        ref.noteReferenceSlotMutation();

        // Weakness retires the scalar's strong owner identity even though its
        // referent field is unchanged.

        // If referent was already destroyed, immediately undef the weak ref.
        // CODE refs are an exception: Sub::Quote compiles named, no-install
        // subs under a localized glob and returns the CODE ref after restoring
        // that glob. The restore path can mark the temporary stash reference as
        // destroyed even though a strong coderef escaped. Revive that stale
        // marker so a later weaken() of the escaped CODE behaves like Perl.
        if (base.isPerlDestroyed()) {
            if (base instanceof RuntimeCode code
                    && code.defined()
                    && !code.currentlyDestroying) {
                base.markRefCountUntracked();
                base.markPerlResurrected();
            } else {
                ref.type = RuntimeScalarType.UNDEF;
                ref.value = null;
                ref.refreshArgumentAliasOwnerSlots();
                RuntimeScalar.releaseIoOwner(ref);
                return;
            }
        }

        state.weakScalars.add(ref);
        ref.refreshArgumentAliasOwnerSlots();
        ref.refreshUntrackedReferenceOwner();
        state.referentToWeakRefs
                .computeIfAbsent(base, k -> Collections.newSetFromMap(new IdentityHashMap<>()))
                .add(ref);
        ref.releaseUntrackedReferenceOwner(base);
        // Releasing the final strong slot can synchronously run DESTROY and
        // clear this newly registered weak observer, as Perl does when the
        // weakened variable was the last owner.
        if (!state.weakScalars.contains(ref) || ref.value != base) {
            RuntimeScalar.releaseIoOwner(ref);
            return;
        }
        ref.releaseClosureCaptureReferentsForWeaken(base);
        if (System.getenv("PJ_WEAKCLEAR_TRACE") != null) {
            System.err.println("[WEAKEN] ref=" + System.identityHashCode(ref)
                + " referent=" + System.identityHashCode(base)
                + " (" + base.getClass().getSimpleName() + ")");
        }
        // Flip the fast-path flag so scopeExit cascades don't bail out
        // via the !blessedObjectExists shortcut when unblessed data has
        // weak refs that need clearing.
        if (!state.weakRefsExist) {
            MortalList.noteBoundaryWork();
            state.weakRefsExist = true;
        }

        boolean weakenedLiveLexical = MyVarCleanupStack.isRegistered(ref);
        boolean weakenedLiveCodeRef = base instanceof RuntimeCode && weakenedLiveLexical;
        if (base.isRefCountTracked() && base.refCount > 0 && ref.refCountOwned) {
            PerlOwnerSlot releasedSlot = ref.countedOwnerSlot();
            boolean identifiedOwner = releasedSlot != null && releasedSlot.isActive()
                    && releasedSlot.referent() == base;
            if (!identifiedOwner) {
                // Legacy scalar construction can mark an already-counted edge
                // before its storage producer has installed a slot token.
                // Adopt that existing count as an identity without incrementing
                // it, then retire the exact edge through the standard release.
                RuntimeScalar.adoptExistingCountedOwnerForCurrentValue(
                        ref, base, ref.countedOwnerKind(), "WeakRefRegistry.weaken legacy edge");
                releasedSlot = ref.countedOwnerSlot();
                if (releasedSlot == null || !releasedSlot.isActive()
                        || releasedSlot.referent() != base) {
                    throw new IllegalStateException(
                            "weakening failed to adopt the current counted owner slot");
                }
            }
            // Weakening retires the exact strong edge. Queue its token through
            // the same lifecycle path as overwrite and aggregate removal so
            // the slot identity and count cannot drift apart.
            PerlOwnerSlot.PendingRelease release =
                    ref.releaseCountedOwnerIdentity(base, "weaken", true);
            if (release == null) {
                throw new IllegalStateException(
                        "weakening a counted scalar lost its owner release token");
            }
            MortalList.releaseCountedOwnerSlotImmediately(
                    base, release, "WeakRefRegistry.weaken");
        } else if (base.isRefCountUntracked()
                && base instanceof RuntimeScalarReadOnly
                && RuntimeCode.isInstalledPadConstant(base)) {
            // A live CV optree owns this constant through an explicit owner slot.
        } else if (base.isRefCountUntracked() && !(base instanceof RuntimeCode)) {
            // Untracked non-CODE object: transition to weak-tracked state so that
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
            // to refcounting. If we transition CODE refs to weak-tracked state,
            // setLarge()/scopeExitCleanup() will prematurely clear weak refs
            // when a lexical reference is overwritten — even though the CODE ref
            // is still alive in the stash. This breaks Sub::Quote/Sub::Defer
            // (which use weaken() for back-references) and cascades to break
            // Moo's accessor inlining (51 test failures). See §15.
            ref.refCountOwned = false;
            // Keep weak-tracked state instead of fabricating a
            // positive count for package-nested aggregates. Their exact
            // UNTRACKED_REFERENCE slots keep the observer live and trigger
            // cleanup when the final scalar owner releases its edge.
            base.markRefCountWeaklyTracked();
        }
        boolean shouldCheckLiveCodeRef = weakenedLiveCodeRef
                && codeRefHasCountedOwners(base);
        if (base instanceof RuntimeCode code
                && code.isRefCountTracked()
                && state.weakRefsExist
                && (shouldCheckLiveCodeRef
                || (code.hadStashRef
                && !isInstalledGlobalCodeRef(code)))) {
            // Keep this path targeted to the CODE referent. DBIC weakens
            // callback CODE refs in hot cursor paths, so use the CODE-specific
            // lifetime checks below.
            // The CODE-specific stale-ref decision below is enough: live CODE
            // refs are protected by clearWeakRefsTo()/shouldKeepCodeWeakRefs,
            // while expired anonymous or former-stash CODE refs can still clear
            // their weak registry entries immediately.
            if (hasWeakRefsTo(code)
                    && !isInstalledGlobalCodeRef(code)) {
                // clearWeakRefsTo() makes the final decision from stash state,
                // active frames, and explicit owner-slot identities.
                clearWeakRefsTo(code);
            }
        }
        // A weakened glob scalar cannot keep an anonymous IO resource alive
        // independently of the glob referent. Release its resource-owner slot
        // only after any final DESTROY dispatch above has completed.
        RuntimeScalar.releaseIoOwner(ref);
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
        if (!state.weakScalars.remove(ref)) return;
        ref.noteReferenceSlotMutation();
        if (ref.value instanceof RuntimeBase base) {
            Set<RuntimeScalar> weakRefs = state.referentToWeakRefs.get(base);
            if (weakRefs != null) {
                weakRefs.remove(ref);
                if (weakRefs.isEmpty()) state.referentToWeakRefs.remove(base);
            }
            PerlOwnerSlot currentOwner = ref.countedOwnerSlot();
            if (ref.refCountOwned
                    && (currentOwner == null || !currentOwner.isActive()
                        || currentOwner.referent() != base
                        || currentOwner.countedScalarOwner() != ref)) {
                // Weak scalars own no counted edge. Legacy paths can leave the
                // marker behind after the exact slot has already been retired;
                // normalize that stale marker before reacquiring the strong
                // edge below, which will install a fresh identity if needed.
                ref.refCountOwned = false;
            }
            // Restore the scalar edge through the same count-and-slot
            // acquisition used by ordinary container stores. The helper also
            // leaves formerly untracked referents uncounted.
            RuntimeScalar.incrementRefCountForContainerStore(
                    ref, ref.countedOwnerKind());
            if (!base.isPerlDestroyed()) {
                RuntimeScalar.retainUnstashedIoForDurableSlot(ref);
            }
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
            code.finishActiveCodeOwnerRelease();
        }
        boolean zeroCountOwnerSlotsExhausted = referent != null
                && referent.isRefCountTracked()
                && referent.refCount == 0
                && !(referent instanceof RuntimeCode)
                && !GlobalVariable.isPackageRootedAggregate(referent)
                && MortalList.canTrustFinalCountedRelease(referent);
        if (referent != null
                && ((referent.deferredFinalReleaseForUncountedOwners
                        && (referent.isWeaklyRefCountTracked()
                            || (referent.isRefCountTracked() && referent.refCount == 0)))
                    || zeroCountOwnerSlotsExhausted)
                && !referent.hasOwnerSlots()
                && PerlRuntime.currentOrNull() != null
                && !referent.localBindingExists
                && !referent.hasClosureCaptureOwner()
                && !referent.hasPendingReturnOwner()
                && referent.countedOwnerSlotCount() == 0
                && !(referent instanceof RuntimeScalarReadOnly
                    && RuntimeCode.isInstalledPadConstant(referent))) {
            referent.deferredFinalReleaseForUncountedOwners = false;
            DestroyDispatch.callDestroy(referent);
            return;
        }
        if (referent == null
                || !referent.isWeaklyRefCountTracked()
                || referent.hasOwnerSlots()
                || PerlRuntime.currentOrNull() == null
                || !hasWeakRefsTo(referent)
                || referent.localBindingExists
                || referent.hasClosureCaptureOwner()
                || referent.hasPendingReturnOwner()
                || referent.countedOwnerSlotCount() != 0
                || (referent instanceof RuntimeScalarReadOnly
                    && RuntimeCode.isInstalledPadConstant(referent))) return;

        if (referent.blessId != 0
                || referent instanceof RuntimeArray
                || referent instanceof RuntimeHash) {
            DestroyDispatch.callDestroy(referent);
        } else {
            clearWeakRefsTo(referent);
        }
    }

    /** Recheck cleared aggregate cleanup after a suspended lexical owner retires. */
    public static void onSuspendedFrameOwnerReleased(RuntimeBase referent) {
        if (referent != null && !referent.isPerlDestroyed()
                && referent.clearedOwnedAggregateElement) {
            MortalList.finalizeClearedAggregateOwnerAfterScopeExit(referent);
        }
    }

    /** Finalize an untracked value after its scalar owner has left scope. */
    static void finalizeUntrackedAfterOwnerScopeExit(RuntimeBase referent) {
        if (referent == null || !referent.isRefCountUntracked()
                || PerlRuntime.currentOrNull() == null
                || referent.hasOwnerSlots() || referent.localBindingExists
                || referent.hasClosureCaptureOwner() || referent.hasPendingReturnOwner()
                || referent.countedOwnerSlotCount() != 0
                || (referent instanceof RuntimeCode code && RuntimeCode.isActiveCode(code))) {
            return;
        }
        DestroyDispatch.callDestroy(referent);
    }

    /** Finish an aggregate's cleanup after its active lexical pad owner left. */
    static void onActiveLexicalOwnerReleased(RuntimeBase referent) {
        if (referent == null || PerlRuntime.currentOrNull() == null
                || referent.hasOwnerSlots() || referent.hasClosureCaptureOwner()
                || referent.hasPendingReturnOwner()
                || referent.countedOwnerSlotCount() != 0
                || referent.localBindingExists || referent.refCount > 0
                || referent.isPerlDestroyed()) return;

        boolean untrackedAggregate = referent.blessId == 0
                && (referent instanceof RuntimeArray || referent instanceof RuntimeHash);
        if (untrackedAggregate && referent.isRefCountTracked()
                && referent.refCount == 1) {
            referent.markRefCountWeaklyTracked();
        }

        if (referent.blessId != 0) {
            DestroyDispatch.callDestroy(referent);
            return;
        }

        if ((referent.isWeaklyRefCountTracked()
                    || (referent.isRefCountTracked() && referent.refCount == 0))
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
        // refcount has entered weak-tracked state. Destruction paths
        // funnel through this method, so preserve that edge at
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
        return code.hasStashCodeSlotOwner();
    }

    private static boolean shouldKeepCodeWeakRefs(RuntimeCode code) {
        boolean installed = isInstalledGlobalCodeRef(code);
        boolean trace = System.getenv("PJ_WEAKCLEAR_TRACE") != null
                && code.hadStashRef && !installed;
        if (trace) {
            System.err.println("[WEAKCODE-CHECK] code=" + System.identityHashCode(code)
                    + " refCount=" + code.refCount
                    + " installed=" + installed + " slots=" + code.ownerSlotSnapshot().size());
        }
        if (installed) return true;
        // The active frame is already a definitive strong owner. Check it
        // before inspecting remaining owner slots, which can be numerous on
        // the callback dispatch path.
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
        // A Perl-visible owner must have an active slot identity. Java-side
        // diagnostic references are not Perl ownership.
        return false;
    }

    private static boolean hasWeakPreservingCodeOwnerSlot(RuntimeCode code) {
        for (PerlOwnerSlot slot : code.ownerSlotSnapshot()) {
            // CODE_SLOT identities also represent compatibility pins retained
            // for compiled call sites after a glob is deleted. The visible
            // stash checks above distinguish live Perl CODE owners from those
            // Java-facing pins.
            if (!slot.isActive() || slot.kind() == PerlOwnerSlot.Kind.CODE_SLOT) continue;
            if (slot.kind() == PerlOwnerSlot.Kind.UNTRACKED_REFERENCE) {
                RuntimeScalar owner = slot.untrackedScalarOwner();
                boolean currentStrongValue = owner != null
                        && owner.untrackedReferenceOwnerSlot() == slot
                        && !owner.isTransientArgumentValueOwner()
                        && !isweak(owner)
                        && (owner.type & RuntimeScalarType.REFERENCE_BIT) != 0
                        && owner.value == code
                        && owner.hasLiveCurrentStrongValueSlot();
                if (currentStrongValue) return true;
                // A stale untracked token is not a live Perl owner. The exact
                // container-transfer release retires it; if a weak-clear
                // boundary arrives first, do not let an exited @_ array pin
                // the observer.
                continue;
            }
            return true;
        }
        return false;
    }

}
