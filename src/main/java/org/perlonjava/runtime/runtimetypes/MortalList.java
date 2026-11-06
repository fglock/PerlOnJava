package org.perlonjava.runtime.runtimetypes;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lightweight mortal-like defer-decrement mechanism.
 * <p>
 * Perl 5 uses "mortals" to keep values alive until the end of the current
 * statement (FREETMPS). Without this, hash delete would trigger DESTROY before
 * the caller can capture the returned value.
 * <p>
 * This is critical for POE compatibility: {@code delete $heap->{wheel}} must
 * trigger DESTROY at statement end, not immediately during delete.
 */
public class MortalList {
    private static final int MAX_UNBLESSED_AGGREGATE_DRAIN_ROOTS = 50_000;
    private static final AtomicInteger runtimesWithBoundaryWork = new AtomicInteger();

    static final class DeferredArgumentOwnerRelease {
        final RuntimeBase base;
        final RuntimeBase.PendingOwnerRelease ownerRelease;
        final String transientOwnerKind;

        DeferredArgumentOwnerRelease(RuntimeBase base,
                                     RuntimeBase.PendingOwnerRelease ownerRelease,
                                     String transientOwnerKind) {
            this.base = base;
            this.ownerRelease = ownerRelease;
            this.transientOwnerKind = transientOwnerKind;
        }
    }

    private record PendingOwnerEntry(
            RuntimeBase base,
            RuntimeBase.PendingOwnerRelease ownerRelease,
            String transientOwnerKind) {}

    static final class ReachabilityQueryMeasurement implements AutoCloseable {
        private final LifecycleRuntimeState state;
        private final ReachabilityQueryStats stats;
        private final ReachabilityQueryStats previous;

        private ReachabilityQueryMeasurement(LifecycleRuntimeState state,
                                             ReachabilityQueryStats stats,
                                             ReachabilityQueryStats previous) {
            this.state = state;
            this.stats = stats;
            this.previous = previous;
        }

        ReachabilityQueryStats stats() {
            return stats;
        }

        @Override
        public void close() {
            if (state.activeReachabilityQueryStats == stats) {
                state.activeReachabilityQueryStats = previous;
            }
        }
    }

    static ReachabilityQueryMeasurement measureReachabilityQueries() {
        LifecycleRuntimeState state = state();
        ReachabilityQueryStats previous = state.activeReachabilityQueryStats;
        ReachabilityQueryStats stats = new ReachabilityQueryStats();
        state.activeReachabilityQueryStats = stats;
        return new ReachabilityQueryMeasurement(state, stats, previous);
    }

    static ReachabilityQueryStats activeReachabilityQueryStats() {
        return state().activeReachabilityQueryStats;
    }

    // Always-on: refCount tracking for birth-tracked objects (anonymous hashes,
    // arrays, closures with captures) requires balanced increment/decrement.
    // The increment side fires unconditionally in setLarge() when refCount >= 0,
    // so the decrement side (deferDecrementIfTracked, flush, etc.) must also
    // be active from the start.  The per-method `!active` guards are retained
    // as a trivially-predicted branch; the JIT will elide them.
    private static LifecycleRuntimeState state() {
        return PerlRuntime.current().lifecycleState;
    }

    private static void markBoundaryWork(LifecycleRuntimeState state) {
        synchronized (state) {
            if (state.boundaryWorkRegistered.compareAndSet(false, true)) {
                runtimesWithBoundaryWork.incrementAndGet();
            }
        }
    }

    private static void refreshBoundaryWork(LifecycleRuntimeState state) {
        synchronized (state) {
            boolean needed = !state.pending.isEmpty()
                    || !state.pendingTiedReleases.isEmpty()
                    || !state.pendingIoReleases.isEmpty()
                    || !state.pendingUntrackedReturnOwnerReleases.isEmpty()
                    || state.deferredCapturesMayBeReady;
            if (!needed && state.boundaryWorkRegistered.compareAndSet(true, false)) {
                runtimesWithBoundaryWork.decrementAndGet();
            }
        }
    }

    static void noteBoundaryWork() {
        markBoundaryWork(state());
    }

    static void clearCurrentRuntimeState() {
        LifecycleRuntimeState state = state();
        state.clear();
        refreshBoundaryWork(state);
    }

    public static boolean isActive() {
        return state().mortalActive;
    }

    public static void setActive(boolean active) {
        state().mortalActive = active;
    }

    public static void pushTemporaryRoot(RuntimeBase root) {
        if (root != null) {
            state().temporaryRoots.push(root);
        }
    }

    public static void popTemporaryRoot(RuntimeBase root) {
        if (root != null) {
            ArrayDeque<RuntimeBase> roots = state().temporaryRoots;
            if (!roots.isEmpty() && roots.peek() == root) {
                roots.pop();
            } else {
                roots.removeFirstOccurrence(root);
            }
        }
    }

    public static java.util.List<RuntimeBase> snapshotTemporaryRoots() {
        return new java.util.ArrayList<>(state().temporaryRoots);
    }

    public static boolean hasTemporaryRoots() {
        return !state().temporaryRoots.isEmpty();
    }

    static boolean temporaryRootDirectlyReferences(RuntimeBase target) {
        for (RuntimeBase root : state().temporaryRoots) {
            if (directlyReferences(root, target)) return true;
        }
        return false;
    }

    private static boolean directlyReferences(RuntimeBase value, RuntimeBase target) {
        return directlyReferences(value, target, new IdentityHashMap<>());
    }

    private static boolean directlyReferences(RuntimeBase value, RuntimeBase target,
                                              IdentityHashMap<RuntimeBase, Boolean> visited) {
        if (value == target) return true;
        if (value == null || visited.put(value, Boolean.TRUE) != null) return false;
        if (value instanceof RuntimeScalar scalar
                && !WeakRefRegistry.isweak(scalar)
                && (scalar.type & RuntimeScalarType.REFERENCE_BIT) != 0
                && scalar.value instanceof RuntimeBase referent) {
            // The reference owns its referent through the slot ledger. Its
            // descendants are owned by their own element slots; searching
            // through that graph here duplicates reference counting and can
            // traverse all Moose metadata on every lexical scope exit.
            return referent == target;
        }
        if (value instanceof RuntimeList list) {
            for (RuntimeBase element : list.elements) {
                if (directlyReferences(element, target, visited)) return true;
            }
        } else if (value instanceof RuntimeArray array) {
            for (RuntimeScalar element : array.elements) {
                if (directlyReferences(element, target, visited)) return true;
            }
        }
        return false;
    }

    public static void clearTemporaryRoots() {
        state().temporaryRoots.clear();
        invalidateAllRootSnapshots();
    }

    /**
     * Phase I: O(1) check whether the given scalar is in
     * {@link #deferredCaptures}. Used by the reachability walker to
     * filter out stale {@link ScalarRefRegistry} seeds.
     */
    public static boolean isDeferredCapture(RuntimeScalar scalar) {
        if (scalar == null) return false;
        return state().deferredCapturesSet.containsKey(scalar);
    }

    /**
     * Legacy unpaired decrement without scalar-slot release provenance.
     * Production mutation paths must use the slot-aware release methods below.
     */
    public static void deferDecrement(RuntimeBase base) {
        deferDecrement(base, null);
    }

    /** Queue a decrement paired with a trace-only non-scalar owner kind. */
    public static void deferDecrement(RuntimeBase base, String transientOwnerKind) {
        if (base.refCountTrace) {
            base.traceRefCount(0, "MortalList.deferDecrement (queued)");
        }
        LifecycleRuntimeState state = state();
        markBoundaryWork(state);
        queueDeferredBase(state, base, null, transientOwnerKind);
    }

    /**
     * Whether a release for this exact referent is queued while a call argument
     * is being staged. The staging scalar is a live argument alias, so it must
     * acquire that identity before the queued temporary release can drain.
     */
    static boolean hasPendingStrongReleaseFor(RuntimeBase referent) {
        if (referent == null) return false;
        for (RuntimeBase pending : state().pending) {
            if (pending == referent) return true;
        }
        return false;
    }

    /** Find the pending release for this exact scalar slot and referent. */
    static PerlOwnerSlot.PendingRelease pendingCountedReleaseFor(
            RuntimeScalar owner, RuntimeBase referent) {
        if (owner == null || referent == null) return null;
        LifecycleRuntimeState state = state();
        for (int i = 0; i < state.pending.size(); i++) {
            if (state.pending.get(i) != referent) continue;
            java.lang.ref.WeakReference<RuntimeScalar> ownerReference =
                    state.pendingOwnerScalars.get(i);
            if (ownerReference == null || ownerReference.get() != owner) continue;
            RuntimeBase.PendingOwnerRelease pendingRelease =
                    state.pendingOwnerReleases.get(i);
            if (pendingRelease != null && pendingRelease.ownerSlotRelease != null) {
                return pendingRelease.ownerSlotRelease;
            }
        }
        return null;
    }

    /**
     * Complete the exact return and source releases after a receiving scalar
     * has acquired the returned referent. Source provenance is a stable slot
     * identity plus its release sequence; unrelated pending owners of the
     * same object remain queued.
     */
    static void consumeMortalReturnValue(RuntimeScalar returned, RuntimeBase destinationReferent) {
        if (!isActive() || returned == null || !returned.hasMortalReturnSnapshot()
                || returned.value != destinationReferent || destinationReferent == null
                || RuntimeCode.hasActiveCallArgumentFrame()) {
            return;
        }
        LifecycleRuntimeState state = state();
        if (state.flushing) return;

        int returnIndex = -1;
        int sourceIndex = -1;
        for (int i = 0; i < state.pending.size(); i++) {
            if (state.pending.get(i) != destinationReferent) continue;
            RuntimeBase.PendingOwnerRelease ownerRelease =
                    state.pendingOwnerReleases.get(i);
            PerlOwnerSlot.PendingRelease slotRelease = ownerRelease == null
                    ? null : ownerRelease.ownerSlotRelease;
            if (slotRelease == null) continue;
            if (slotRelease.kind() == PerlOwnerSlot.Kind.RETURN_VALUE) {
                java.lang.ref.WeakReference<RuntimeScalar> ownerReference =
                        state.pendingOwnerScalars.get(i);
                if (ownerReference != null && ownerReference.get() == returned) {
                    returnIndex = i;
                    continue;
                }
            }
            if (slotRelease.ownerSlotIdentity() == returned.mortalReturnSourceOwnerIdentity()
                    && slotRelease.sequence() == returned.mortalReturnSourceReleaseSequence()
                    && destinationReferent == returned.mortalReturnSourceReferent()) {
                sourceIndex = i;
            }
        }

        int untrackedReturnIndex = state.pendingUntrackedReturnOwnerReleases
                .indexOf(returned);
        PendingOwnerEntry returnEntry = null;
        PendingOwnerEntry sourceEntry = null;
        state.flushing = true;
        invalidateDrainReachabilityCaches();
        try {
            if (returnIndex >= 0 && returnIndex == sourceIndex) {
                sourceIndex = -1;
            }
            if (returnIndex > sourceIndex) {
                returnEntry = removePendingOwnerEntry(state, returnIndex);
                if (sourceIndex >= 0) sourceEntry = removePendingOwnerEntry(state, sourceIndex);
            } else {
                if (sourceIndex >= 0) sourceEntry = removePendingOwnerEntry(state, sourceIndex);
                if (returnIndex >= 0) returnEntry = removePendingOwnerEntry(state, returnIndex);
            }

            if (sourceEntry != null) {
                processDeferredBase(sourceEntry.base(), false,
                        sourceEntry.ownerRelease(), sourceEntry.transientOwnerKind());
            }
            if (returnEntry != null) {
                processDeferredBase(returnEntry.base(), false,
                        returnEntry.ownerRelease(), returnEntry.transientOwnerKind());
            }
            if (untrackedReturnIndex >= 0) {
                state.pendingUntrackedReturnOwnerReleases.remove(untrackedReturnIndex);
                returned.releaseMortalReturnValueOwner();
            }
            returned.finishMortalReturnHandoff();
        } finally {
            state.flushing = false;
            invalidateDrainReachabilityCaches();
        }
        refreshBoundaryWork(state);
    }

    private static PendingOwnerEntry removePendingOwnerEntry(
            LifecycleRuntimeState state, int index) {
        RuntimeBase base = state.pending.remove(index);
        RuntimeBase.PendingOwnerRelease ownerRelease =
                state.pendingOwnerReleases.remove(index);
        state.pendingOwnerScalars.remove(index);
        state.pendingArgumentFrames.remove(index);
        String transientOwnerKind = state.pendingTransientOwnerKinds.remove(index);
        return new PendingOwnerEntry(base, ownerRelease, transientOwnerKind);
    }

    /**
     * Acquire and queue the mortal reference created by the first bless of a
     * referent. Unlike an ordinary removed storage edge, this temporary stays
     * live until the deferred boundary processes its release.
     */
    public static void deferBlessTemporary(RuntimeBase base, boolean strongPerlOwner) {
        if (base == null) return;
        if (base.refCount == Integer.MIN_VALUE) {
            throw new IllegalStateException("cannot bless a destroyed referent");
        }
        PerlOwnerSlot temporaryOwner = new PerlOwnerSlot(PerlOwnerSlot.Kind.SCALAR_CELL);
        temporaryOwner.acquireCountedTemporaryOwner(
                base, "ReferenceOperators.bless mortal temporary");
        base.acquireTransientTraceOwner("bless mortal temporary",
                "ReferenceOperators.bless");
        PerlOwnerSlot.PendingRelease slotRelease = strongPerlOwner
                ? temporaryOwner.releaseCountedIdentityAtCompletion()
                : temporaryOwner.releaseCountedIdentity();

        LifecycleRuntimeState state = state();
        markBoundaryWork(state);
        RuntimeBase.PendingOwnerRelease ownerRelease =
                base.queueOwnerSlotRelease(slotRelease);
        queueDeferredBase(state, base, ownerRelease, "bless mortal temporary");
    }

    /** Queue the deferred release together with the array scalar that owned it. */
    public static void deferDecrementFromArraySlot(
            RuntimeBase base, RuntimeScalar ownerScalar, String releaseSite) {
        if (base.refCountTrace) {
            base.traceRefCount(0, releaseSite + " (array-slot decrement queued)");
        }
        deferCountedSlotRelease(base, ownerScalar, releaseSite);
    }

    /** Queue a mutable scalar cell's counted release before its value is restored. */
    public static void deferDecrementFromScalarSlot(
            RuntimeBase base, RuntimeScalar ownerScalar, String releaseSite) {
        if (base.refCountTrace) {
            base.traceRefCount(0, releaseSite + " (scalar-slot decrement queued)");
        }
        deferCountedSlotRelease(base, ownerScalar, releaseSite);
    }

    private static void deferCountedSlotRelease(
            RuntimeBase base, RuntimeScalar ownerScalar, String releaseSite) {
        LifecycleRuntimeState state = state();
        markBoundaryWork(state);
        RuntimeBase.PendingOwnerRelease ownerRelease =
                base.queueOwnerRelease(ownerScalar, releaseSite);
        queueDeferredBase(state, base, ownerRelease, null, ownerScalar);
    }

    /** Queue a closure-capture bridge decrement with owner-slot provenance. */
    static void deferOwnerSlotDecrement(RuntimeBase base, String transientOwnerKind,
                                        PerlOwnerSlot.PendingRelease slotRelease) {
        deferOwnerSlotDecrement(base, transientOwnerKind, slotRelease,
                "MortalList.deferOwnerSlotDecrement");
    }

    private static void deferOwnerSlotDecrement(RuntimeBase base, String transientOwnerKind,
                                                 PerlOwnerSlot.PendingRelease slotRelease,
                                                 String releaseSite) {
        if (base.refCountTrace) {
            base.traceRefCount(0, releaseSite + " (queued)");
        }
        LifecycleRuntimeState state = state();
        markBoundaryWork(state);
        RuntimeBase.PendingOwnerRelease ownerRelease = base.queueOwnerSlotRelease(slotRelease);
        queueDeferredBase(state, base, ownerRelease, transientOwnerKind);
    }

    /** Consume one exact scalar-edge release after its replacement is published. */
    static void releaseCountedOwnerSlotImmediately(
            RuntimeBase base, PerlOwnerSlot.PendingRelease slotRelease, String releaseSite) {
        releaseCountedOwnerSlotImmediately(base, slotRelease, null, releaseSite);
    }

    /** Retire an exact scalar edge while building host-side storage. */
    static void releaseCountedOwnerWithoutPerlRuntime(
            RuntimeScalar owner, RuntimeBase referent, String releaseSite) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(referent, "referent");
        Objects.requireNonNull(releaseSite, "releaseSite");
        if (PerlRuntime.currentOrNull() != null) {
            throw new IllegalStateException(
                    "host-side counted release cannot run inside a Perl runtime");
        }
        PerlOwnerSlot slot = owner.countedOwnerSlot();
        if (!owner.refCountOwned || owner.value != referent
                || slot == null || !slot.isActive() || slot.referent() != referent
                || slot.countedScalarOwner() != owner
                || !PerlOwnerSlot.isCountedKind(slot.kind())) {
            throw new IllegalStateException(
                    "host-side counted release lost its current scalar owner at " + releaseSite);
        }
        if (referent.refCount <= 0) {
            throw new IllegalStateException(
                    "host-side counted release requires a positive reference count at "
                            + releaseSite);
        }

        owner.refCountOwned = false;
        referent.releaseOwner(owner, releaseSite);
        PerlOwnerSlot.PendingRelease release = slot.releaseCountedIdentity();
        if (release == null) {
            throw new IllegalStateException(
                    "host-side counted release lost its release token at " + releaseSite);
        }
        releaseCountedOwnerSlotImmediately(
                referent, release, null, releaseSite, false, true);
    }

    static void releaseCountedOwnerSlotImmediately(
            RuntimeBase base, PerlOwnerSlot.PendingRelease slotRelease,
            String transientOwnerKind, String releaseSite) {
        releaseCountedOwnerSlotImmediately(
                base, slotRelease, transientOwnerKind, releaseSite, false);
    }

    private static void releaseCountedOwnerSlotImmediately(
            RuntimeBase base, PerlOwnerSlot.PendingRelease slotRelease,
            String transientOwnerKind, String releaseSite,
            boolean clearWeakRefsForLocalBinding) {
        releaseCountedOwnerSlotImmediately(
                base, slotRelease, transientOwnerKind, releaseSite,
                clearWeakRefsForLocalBinding, false);
    }

    private static void releaseCountedOwnerSlotImmediately(
            RuntimeBase base, PerlOwnerSlot.PendingRelease slotRelease,
            String transientOwnerKind, String releaseSite,
            boolean clearWeakRefsForLocalBinding, boolean hostCountOnly) {
        if (base == null || slotRelease == null) return;
        if (PerlRuntime.currentOrNull() == null) {
            if (hostCountOnly) {
                if (base.refCount <= 0) {
                    throw new IllegalStateException(
                            "host-side owner-slot release requires a positive reference count at "
                                    + releaseSite);
                }
                base.releaseTrackedOwnerCount(releaseSite);
                slotRelease.complete();
                return;
            }
            // A host-side owner transition can retire a non-final count without
            // a Perl execution context. Keep this path limited to count-only
            // releases and empty unblessed aggregates; any final Perl lifecycle
            // work still requires the owning runtime to be bound.
            if (base.currentlyDestroying || base.refCount < 0
                    || base.refCount == Integer.MIN_VALUE) {
                slotRelease.complete();
                return;
            }
            if (base.refCount > 1) {
                base.releaseTrackedOwnerCount(releaseSite);
                slotRelease.complete();
                return;
            }
            if (base.refCount == 1 && base.blessId == 0
                    && !base.hasOwnerSlots() && isEmptyUnblessedAggregate(base)) {
                base.releaseTrackedOwnerCount(releaseSite);
                slotRelease.complete();
                return;
            }
            throw new IllegalStateException(
                    "final Perl owner release requires a bound PerlRuntime at " + releaseSite);
        }
        int checkpoint = pendingSize();
        deferOwnerSlotDecrement(base, transientOwnerKind, slotRelease, releaseSite);
        // Scalar overwrite and weaken historically leave weak observers alone
        // when an unrelated local binding keeps the referent alive.
        drainPendingSince(checkpoint, clearWeakRefsForLocalBinding);
    }

    private static boolean isEmptyUnblessedAggregate(RuntimeBase base) {
        return base instanceof RuntimeArray array
                    && (array.elements == null || array.elements.isEmpty())
                || base instanceof RuntimeHash hash
                    && (hash.elements == null || hash.elements.isEmpty());
    }

    /** Release a counted native owner through the normal destruction transition. */
    public static void releaseCountedNativeOwner(
            RuntimeBase base, PerlOwnerSlot ownerSlot) {
        releaseCountedNativeOwner(base, ownerSlot,
                CountedNativeReleasePolicy.FINALIZE_REFERENT,
                "MortalList.releaseCountedNativeOwner");
    }

    /** Retire a regex callback's count before its callback owner releases CODE captures. */
    static void releaseRegexCallbackOwner(
            RuntimeBase base, PerlOwnerSlot ownerSlot, String releaseSite) {
        if (ownerSlot == null || ownerSlot.kind() != PerlOwnerSlot.Kind.REGEX_CALLBACK) {
            throw new IllegalArgumentException(
                    "regex callback release requires a regex callback owner slot");
        }
        releaseCountedNativeOwner(base, ownerSlot,
                CountedNativeReleasePolicy.RETIRE_COUNT_ONLY, releaseSite);
    }

    private enum CountedNativeReleasePolicy {
        FINALIZE_REFERENT,
        RETIRE_COUNT_ONLY
    }

    /** Keep slot retirement, paired count decrement, and lifecycle policy together. */
    private static void releaseCountedNativeOwner(
            RuntimeBase base, PerlOwnerSlot ownerSlot,
            CountedNativeReleasePolicy policy, String releaseSite) {
        if (base == null || ownerSlot == null || ownerSlot.referent() != base) return;
        if (!ownerSlot.isCountedNativeOwner()) {
            ownerSlot.release();
            return;
        }
        PerlOwnerSlot.PendingRelease release = ownerSlot.releaseCountedNativeIdentity();
        if (release == null) return;
        if (policy == CountedNativeReleasePolicy.RETIRE_COUNT_ONLY) {
            if (base.refCount > 0) base.releaseTrackedOwnerCount(releaseSite);
            release.complete();
            return;
        }
        if (base.refCount <= 0) {
            release.complete();
            return;
        }
        releaseCountedOwnerSlotImmediately(
                base, release, null, releaseSite, true);
    }

    /** Release a direct method-invocant edge, deferring its final count to the result boundary. */
    static void releaseCountedMethodInvocantOwner(
            RuntimeBase base, PerlOwnerSlot ownerSlot, String releaseSite,
            boolean abandoned) {
        if (base == null || ownerSlot == null
                || !ownerSlot.isMethodInvocantOwner(base)) return;
        PerlOwnerSlot.PendingRelease release = ownerSlot.releaseCountedIdentity();
        if (release == null) return;
        if (base.refCount <= 0 || base.currentlyDestroying) {
            release.complete();
            return;
        }
        if (base.refCount > 1) {
            base.releaseTrackedOwnerCount(releaseSite);
            release.complete();
            return;
        }
        int checkpoint = pendingSize();
        deferOwnerSlotDecrement(base, null, release);
        if (abandoned) drainPendingSince(checkpoint);
    }

    /** Release one exact scalar-backed owner at the operation that drops its Perl edge. */
    public static void releaseCountedOwnerImmediately(RuntimeScalar owner, String releaseSite) {
        if (owner == null || !owner.refCountOwned
                || !(owner.value instanceof RuntimeBase base)) return;
        PerlOwnerSlot ownerSlot = owner.countedOwnerSlot();
        if (ownerSlot == null || !ownerSlot.isActive() || ownerSlot.referent() != base
                || !PerlOwnerSlot.isCountedKind(ownerSlot.kind())) {
            throw new IllegalStateException(
                    "counted argument owner lost its active slot at " + releaseSite);
        }
        owner.refCountOwned = false;
        PerlOwnerSlot.PendingRelease release = ownerSlot.releaseCountedIdentity();
        if (release == null) {
            throw new IllegalStateException(
                    "counted argument owner lost its release token at " + releaseSite);
        }
        if (base.refCount <= 0 || base.currentlyDestroying) {
            release.complete();
            return;
        }
        releaseCountedOwnerSlotImmediately(base, release, releaseSite);
    }

    /** Balance DESTROY's synthetic $_[0] edge without re-entering destruction. */
    static void releaseDestroySelfArgument(RuntimeScalar owner, String releaseSite) {
        if (owner == null || !owner.refCountOwned
                || !(owner.value instanceof RuntimeBase base)) return;
        PerlOwnerSlot ownerSlot = owner.countedOwnerSlot();
        if (!base.currentlyDestroying || base.refCount <= 0
                || ownerSlot == null || !ownerSlot.isActive()
                || ownerSlot.referent() != base || ownerSlot.countedScalarOwner() != owner
                || !PerlOwnerSlot.isCountedKind(ownerSlot.kind())) {
            throw new IllegalStateException(
                    "DESTROY self argument lost its counted owner at " + releaseSite);
        }
        owner.refCountOwned = false;
        base.releaseOwner(owner, releaseSite);
        PerlOwnerSlot.PendingRelease release = ownerSlot.releaseCountedIdentity();
        if (release == null) {
            throw new IllegalStateException(
                    "DESTROY self argument lost its release token at " + releaseSite);
        }
        base.releaseTrackedOwnerCount(releaseSite);
        release.complete();
    }

    private static void queueDeferredBase(LifecycleRuntimeState state, RuntimeBase base,
                                          RuntimeBase.PendingOwnerRelease ownerRelease) {
        queueDeferredBase(state, base, ownerRelease, null);
    }

    private static void queueDeferredBase(LifecycleRuntimeState state, RuntimeBase base,
                                          RuntimeBase.PendingOwnerRelease ownerRelease,
                                          String transientOwnerKind) {
        queueDeferredBase(state, base, ownerRelease, transientOwnerKind, null);
    }

    private static void queueDeferredBase(LifecycleRuntimeState state, RuntimeBase base,
                                          RuntimeBase.PendingOwnerRelease ownerRelease,
                                          String transientOwnerKind, RuntimeScalar ownerScalar) {
        state.pending.add(base);
        state.pendingOwnerReleases.add(ownerRelease);
        state.pendingOwnerScalars.add(ownerScalar == null ? null
                : new java.lang.ref.WeakReference<>(ownerScalar));
        state.pendingArgumentFrames.add(
                RuntimeCode.argumentAliasFrameForOwner(ownerScalar));
        state.pendingTransientOwnerKinds.add(transientOwnerKind);
    }

    public static void deferTiedObjectRelease(TiedVariableBase tiedVariable) {
        LifecycleRuntimeState state = state();
        if (!state.mortalActive || tiedVariable == null) return;
        markBoundaryWork(state);
        state.pendingTiedReleases.add(tiedVariable);
    }

    public static void deferIoOwnerRelease(RuntimeScalar scalar) {
        LifecycleRuntimeState state = state();
        if (!state.mortalActive || scalar == null || !scalar.hasIoOwner()) return;
        markBoundaryWork(state);
        state.pendingIoReleases.add(scalar);
    }

    /** Queue an untracked mortal return slot for its caller's normal boundary. */
    static void deferUntrackedReturnValueOwnerRelease(RuntimeScalar scalar) {
        if (scalar == null || scalar.untrackedReferenceOwnerSlot() == null) return;
        LifecycleRuntimeState state = state();
        markBoundaryWork(state);
        state.pendingUntrackedReturnOwnerReleases.add(scalar);
    }

    /**
     * Record a captured scalar whose scope has exited but whose refCount
     * could not be decremented because a closure owner remains.
     * Called from {@link RuntimeScalar#scopeExitCleanup} for non-CODE
     * blessed references that are captured by closures.
     * <p>
     * After the main script returns, entries unrelated to END are processed by
     * {@link #flushDeferredCapturesBeforeEnd()}; retained entries are drained
     * by {@link #flushDeferredCaptures()} after END blocks run.
     */
    public static void addDeferredCapture(RuntimeScalar scalar) {
        LifecycleRuntimeState state = state();
        markBoundaryWork(state);
        state.deferredCaptures.add(scalar);
        state.deferredCapturesSet.merge(scalar, 1, Integer::sum);
        if (!scalar.hasClosureCaptureOwner() && scalar.scopeExited) {
            state.deferredCapturesMayBeReady = true;
        }
    }

    /** Compatibility hook; capture ownership no longer needs a reachability candidate. */
    public static void noteDeferredScopeExitCaptureCandidate(RuntimeScalar scalar) {
    }

    static void noteDeferredCaptureMayBeReady() {
        LifecycleRuntimeState state = state();
        if (!state.deferredCaptures.isEmpty()) {
            markBoundaryWork(state);
            state.deferredCapturesMayBeReady = true;
        }
    }

    /**
     * Process deferred captures after their final closure owner has left.
     * Called from {@link #popAndFlush()} at block scope exit, AFTER the
     * mortal list has been processed (which may trigger callDestroy →
     * releaseCaptures → capture owners are retired from captured variables).
     * <p>
     * This bridges the gap between deferred capture registration (at scope
     * exit while a closure owner remains) and flushDeferredCaptures (after the main
     * script returns). Without this, objects whose captures are fully
     * released at block exit still appear "alive" to leak tracers like
     * DBIC's assert_empty_weakregistry, which runs inside the main script.
     * <p>
     * Only processes entries with no closure owner AND scopeExited == true,
     * leaving others for later processing (either a subsequent block exit
     * or flushDeferredCaptures at script end).
     */
    private static void processReadyDeferredCaptures(LifecycleRuntimeState state) {
        if (state.deferredCaptures.isEmpty()) return;
        if (!state.deferredCapturesMayBeReady) return;
        state.deferredCapturesMayBeReady = false;
        boolean found = false;
        for (int i = state.deferredCaptures.size() - 1; i >= 0; i--) {
            RuntimeScalar scalar = state.deferredCaptures.get(i);
            if (!scalar.hasClosureCaptureOwner() && scalar.scopeExited) {
                deferDecrementIfTracked(scalar);
                state.deferredCaptures.remove(i);
                removeFromDeferredSet(scalar);
                found = true;
            }
        }
        if (found) {
            flush();
        }
    }

    private static void removeFromDeferredSet(RuntimeScalar scalar) {
        LifecycleRuntimeState state = state();
        Integer c = state.deferredCapturesSet.get(scalar);
        if (c == null) return;
        if (c <= 1) state.deferredCapturesSet.remove(scalar);
        else state.deferredCapturesSet.put(scalar, c - 1);
    }

    /**
     * Process all deferred captured scalars.
     * For each scalar, schedule a refCount decrement via
     * {@link #deferDecrementIfTracked}, then flush the pending list.
     * <p>
     * Called from PerlLanguageProvider after END blocks, so that blessed
     * objects retained by genuine live closures remain available to END.
     * Interpreter closure over-capture can keep unrelated values alive until
     * this boundary, but cannot justify destroying real captures before END.
     * <p>
     * At this point all lexical scopes and END blocks have exited. Closures can
     * still hold JVM references to the RuntimeScalar, but selective refCount
     * cleanup may now proceed into global destruction.
     */
    public static void flushDeferredCaptures() {
        LifecycleRuntimeState state = state();
        if (state.deferredCaptures.isEmpty()) return;
        for (RuntimeScalar scalar : state.deferredCaptures) {
            deferDecrementIfTracked(scalar);
        }
        state.deferredCaptures.clear();
        state.deferredCapturesSet.clear();
        state.deferredCapturesMayBeReady = false;
        flush();

        // After flushing deferred captures, clear weak refs for objects that
        // were rescued by DESTROY (e.g., Schema::DESTROY self-save pattern).
        // This must happen AFTER the flush above so that all pending refCount
        // decrements have been processed and before global destruction.
        DestroyDispatch.clearRescuedWeakRefs();

    }

    /** Compatibility hook; captured owners now leave through explicit slots. */
    public static void flushDeferredCapturesBeforeEnd() {
    }

    /**
     * Convenience: check if a RuntimeScalar holds a tracked reference
     * and schedule a deferred decrement if so. Only fires if the scalar
     * owns a refCount increment (refCountOwned == true), preventing
     * spurious decrements from copies that never incremented.
     */
    public static void deferDecrementIfTracked(RuntimeScalar scalar) {
        if (!isActive() || scalar == null) return;
        if (scalar.hasCodeSlotBinding()) return;
        if (!scalar.refCountOwned) return;
        if (((scalar.type & RuntimeScalarType.REFERENCE_BIT) != 0
                || scalar.type == RuntimeScalarType.GLOB)
                && scalar.value instanceof RuntimeBase base) {
            if (base.refCount > 0) {
                if (base.refCountTrace) {
                    base.traceRefCount(0, "MortalList.deferDecrementIfTracked (queued owner release)");
                }
                LifecycleRuntimeState state = state();
                markBoundaryWork(state);
                RuntimeBase.PendingOwnerRelease ownerRelease = base.queueOwnerRelease(
                        scalar, "MortalList.deferDecrementIfTracked");
                queueDeferredBase(state, base, ownerRelease, null, scalar);
            } else if (base.refCount == 0
                    && base.clearedOwnedAggregateElement
                    && WeakRefRegistry.hasWeakRefsTo(base)) {
                // A nested-call guard may preserve this object at a selective
                // count of zero. Retire the stale scalar owner identity; scope
                // exit or the final remaining owner slot performs cleanup.
                base.retireZeroCountOwnerIdentity(
                        scalar, "deferDecrementIfTracked zero-count owner");
            }
            // Note: WEAKLY_TRACKED (-2) objects are NOT scheduled for destruction
            // on scope exit. We can't count strong refs for non-DESTROY objects
            // (refs created before weaken() weren't tracked), so scope exit of
            // ONE reference doesn't mean there are no other strong refs (e.g.,
            // symbol table entries). Weak refs for these objects are cleared only
            // via explicit undefine() of the referent's last known reference.
        }
    }

    /**
     * Complete one compiler-owned CODE-slot release at an isolated mortal mark.
     * Compiled optree retirement is an exact CV-owner transition, not a statement
     * boundary for older temporary releases in the caller's scope.
     */
    public static void releaseTrackedCodeSlotAtExactBoundary(RuntimeScalar scalar) {
        if (!isActive() || scalar == null || scalar.hasCodeSlotBinding()
                || !scalar.refCountOwned || scalar.type != RuntimeScalarType.CODE
                || !(scalar.value instanceof RuntimeCode code) || code.refCount <= 0) {
            return;
        }
        LifecycleRuntimeState state = state();
        if (state.flushing || state.reachabilityDrainDepth != 0) {
            deferDecrementIfTracked(scalar);
            return;
        }

        int pendingMark = state.pending.size();
        int tiedMark = state.pendingTiedReleases.size();
        int ioMark = state.pendingIoReleases.size();
        int returnOwnerMark = state.pendingUntrackedReturnOwnerReleases.size();
        pushMark(state);
        deferDecrementIfTracked(scalar);
        boolean queuedRelease = state.pending.size() > pendingMark
                || state.pendingTiedReleases.size() > tiedMark
                || state.pendingIoReleases.size() > ioMark
                || state.pendingUntrackedReturnOwnerReleases.size() > returnOwnerMark;
        if (queuedRelease) {
            popAndFlush();
        } else {
            popMark(state);
        }
    }

    /** Release the tracked owner held by a scope-exited closure capture. */
    public static void releaseCapturedDecrement(RuntimeScalar scalar) {
        if (!isActive() || scalar == null) return;
        if (!scalar.refCountOwned) return;
        deferDecrementIfTracked(scalar);
    }

    /**
     * Like {@link #deferDecrementIfTracked}, but delegates to
     * {@link RuntimeScalar#scopeExitCleanup} if the scalar is captured
     * by a closure owner.
     * Used by the explicit {@code return} bytecode path which bypasses
     * {@link RuntimeScalar#scopeExitCleanup}.
     */
    public static void deferDecrementIfNotCaptured(RuntimeScalar scalar) {
        if (!isActive() || scalar == null) return;
        if (scalar.hasClosureCaptureOwner()
                || (scalar.referencedByScalarReference && scalar.localBindingExists)) {
            // Delegate to scopeExitCleanup which handles:
            // - Self-referential cycle detection (eval STRING closures)
            // - Setting scopeExited flag for deferred cleanup via releaseCaptures
            RuntimeScalar.scopeExitCleanup(scalar);
            return;
        }
        RuntimeScalar.clearStaleDiagnosticContextForUnaliasedIO(scalar);
        scalar.deferOwnedScalarReferenceContents();
        deferDecrementIfTracked(scalar);
    }

    /**
     * Defer DESTROY for tracked blessed refs in a collection being cleared.
     * <p>
     * Only decrements elements that own a refCount (refCountOwned == true).
     * Elements stored via copy constructor (no setLarge) are skipped.
     * Never-stored blessed objects (refCount == 0) are bumped to ensure DESTROY fires.
     */
    public static void deferDestroyForContainerClear(Iterable<RuntimeScalar> elements) {
        if (elements == null) return;
        boolean active = isActive();
        for (RuntimeScalar scalar : elements) {
            boolean aliasedScalarCell = scalar != null
                    && scalar.ownerSlotCount(PerlOwnerSlot.Kind.HASH_ALIAS_CELL) != 0;
            if (aliasedScalarCell) continue;
            if (scalar != null && scalar.untrackedReferenceOwnerSlot() != null) {
                scalar.refreshUntrackedReferenceOwner();
            }
            // Aggregate clear/replacement retires the durable slot that kept
            // an anonymous IO glob alive. Queue the holder release beside the
            // referent cleanup so bulk mutation has the same lifecycle as
            // delete and lexical scope exit.
            deferIoOwnerRelease(scalar);
            if (!active) continue;
            // Use the recursive path so a discarded wrapper/container also
            // releases references stored in its fields.  Test::Deep's
            // temporary comparator is a blessed hash whose `val` field can
            // otherwise keep the compared array alive after local %WrapCache
            // is restored.
            deferDecrementRecursive(scalar);
        }
    }

    /**
     * Scalarize a temporary RHS array whose elements are no longer needed.
     * Reference-alias list assignment returns only its element count in scalar
     * and void contexts; release the temporary reference wrappers after
     * computing that count so they do not keep aliased scalar cells alive.
     */
    public static RuntimeScalar scalarAndDiscardTemporaryArray(RuntimeArray array) {
        if (array == null) return new RuntimeScalar();
        RuntimeScalar result = array.scalar();
        deferDestroyForContainerClear(array.elements);
        array.elements.clear();
        array.elementsOwned = false;
        return result;
    }

    /**
     * Scope-exit cleanup for a single JVM local variable of unknown type.
     * Used by the JVM backend's eval exception handler to clean up all
     * my-variables when die unwinds through eval, since the normal
     * SCOPE_EXIT_CLEANUP bytecodes are skipped by Java exception handling.
     * <p>
     * Dispatches to the appropriate cleanup method based on runtime type.
     * Safe to call with null, non-Perl types, or already-cleaned-up values.
     *
     * @param local the JVM local variable value (may be null or any type)
     */
    public static void evalExceptionScopeCleanup(Object local) {
        if (local == null) return;
        if (local instanceof RuntimeScalar rs) {
            RuntimeScalar.scopeExitCleanup(rs);
        } else if (local instanceof RuntimeHash rh) {
            scopeExitCleanupHash(rh);
        } else if (local instanceof RuntimeArray ra) {
            scopeExitCleanupArray(ra);
        }
        // Other types (RuntimeList, Integer, etc.) are ignored - they don't need cleanup
    }

    /**
     * Recursively walk a RuntimeHash's values and defer refCount decrements
     * for any tracked blessed references found (including inside nested
     * arrays/hashes). Called at scope exit for {@code my %hash} variables.
     */
    public static void scopeExitCleanupHash(RuntimeHash hash) {
        scopeExitCleanupHash(hash, false);
    }

    static void scopeExitCleanupDestroyedHash(RuntimeHash hash) {
        scopeExitCleanupHash(hash, true);
    }

    private static void scopeExitCleanupHash(RuntimeHash hash, boolean finalRelease) {
        if (!isActive() || hash == null) return;
        // An aggregate borrowed through @_ belongs to its caller.  Its
        // current frame can end while the caller's lexical is still live;
        // leave its lexical-owner marker for the caller's own scope cleanup.
        // Clearing it here
        // makes Internals::SvREFCNT under-report the caller's reference and
        // lets Const::Fast freeze the original instead of its clone.
        if (!finalRelease && RuntimeCode.deferCleanupForActiveArgumentAggregate(hash)) return;
        scopeExitCleanupHashBody(hash, finalRelease);
    }

    private static void scopeExitCleanupHashBody(RuntimeHash hash, boolean finalRelease) {
        // Clear localBindingExists: the named variable's scope is ending.
        // This allows subsequent refCount==0 events (from setLargeRefCounted
        // or flush) to correctly trigger callDestroy, since the local
        // variable no longer holds a strong reference.
        hash.localBindingExists = false;
        if (!finalRelease) RuntimeCode.releaseActiveLexicalOwner(hash);
        hash.releaseUnownedClosureCaptures();
        if (!finalRelease && hash.hasClosureCaptureOwner()) {
            hash.scopeExited = true;
            return;
        }
        // A live reference keeps the container and its IO element slots alive.
        if (!finalRelease && (hash.refCount > 0 || hash.hasOwnerSlots()
                || temporaryRootDirectlyReferences(hash))) return;
        if (hash instanceof RuntimeStash stash) {
            stash.releaseSavedNamespaceMove();
        }
        for (RuntimeScalar val : hash.elements.values()) {
            RuntimeScalar.releaseIoOwner(val);
            if (val != null) val.releaseContainerOwner(hash);
        }
        // Skip container walks only when there are NO blessed objects AND NO
        // weak refs anywhere in the JVM. If weak refs exist (even to unblessed
        // data), we must still cascade decrements so their weak-ref entries
        // can be cleared when the referent's refCount reaches 0.
        if (!RuntimeBase.blessedObjectExists() && !WeakRefRegistry.weakRefsExist()
                && !RuntimeScalar.watcherCleanupNeeded()) return;
        // If the hash has outstanding references (e.g., from \%hash stored elsewhere),
        // do NOT clean up elements — the hash is still alive and its elements are
        // accessible through the reference. Cleanup will happen when the last
        // reference is released (in DestroyDispatch.callDestroy).
        if (RuntimeScalar.watcherCleanupNeeded()) {
            RuntimeScalar.notifyDestroyedWatchersRecursively(hash);
        }
        if (hash.type == RuntimeHash.TIED_HASH && hash.elements instanceof TieHash tieHash) {
            tieHash.releaseTiedObject();
        }
        // Quick scan: skip if no value could transitively contain blessed/tracked refs.
        boolean needsWalk = RuntimeScalar.watcherCleanupNeeded();
        for (RuntimeScalar val : hash.elements.values()) {
            if (val != null && (val.type & RuntimeScalarType.REFERENCE_BIT) != 0
                    && val.value instanceof RuntimeBase rb) {
                if (rb.blessId != 0 || rb.refCount >= 0) {
                    needsWalk = true;
                    break;
                }
                // Container ref — peek at children for any reference-type elements
                if (rb instanceof RuntimeArray innerArr) {
                    for (RuntimeScalar inner : innerArr.elements) {
                        if (inner != null && (inner.type & RuntimeScalarType.REFERENCE_BIT) != 0) {
                            needsWalk = true;
                            break;
                        }
                    }
                } else if (rb instanceof RuntimeHash innerHash) {
                    for (RuntimeScalar inner : innerHash.elements.values()) {
                        if (inner != null && (inner.type & RuntimeScalarType.REFERENCE_BIT) != 0) {
                            needsWalk = true;
                            break;
                        }
                    }
                }
                if (needsWalk) break;
            }
        }
        if (!needsWalk) return;
        List<RuntimeScalar> valuesInDestructionOrder = new ArrayList<>(hash.elements.values());
        Collections.reverse(valuesInDestructionOrder);
        for (RuntimeScalar val : valuesInDestructionOrder) {
            deferDecrementRecursive(val);
        }
    }

    /**
     * Recursively walk a RuntimeArray's elements and defer refCount decrements
     * for any tracked blessed references found (including inside nested
     * arrays/hashes). Called at scope exit for {@code my @array} variables.
     */
    public static void scopeExitCleanupArray(RuntimeArray arr) {
        scopeExitCleanupArray(arr, null);
    }

    /** Scope-exit cleanup with a returned aggregate whose IO aliases must survive. */
    public static void scopeExitCleanupArray(RuntimeArray arr, RuntimeBase returned) {
        scopeExitCleanupArray(arr, returned, false);
    }

    static void scopeExitCleanupDestroyedArray(RuntimeArray arr) {
        scopeExitCleanupArray(arr, null, true);
    }

    private static void scopeExitCleanupArray(RuntimeArray arr, RuntimeBase returned, boolean finalRelease) {
        if (!isActive() || arr == null) return;
        // A threads::shared array has canonical storage that outlives each
        // runtime-local wrapper.  Its elements are released by mutations of
        // that canonical storage, never by a child runtime's scope exit.
        if (arr.threadShared) return;
        // See scopeExitCleanupHash: an active @_ alias does not end the
        // caller's lexical lifetime.  In particular, Const::Fast uses
        // SvREFCNT to decide whether it must clone a referenced aggregate.
        if (!finalRelease && RuntimeCode.deferCleanupForActiveArgumentAggregate(arr)) return;
        scopeExitCleanupArrayBody(arr, returned, finalRelease);
    }

    private static void scopeExitCleanupArrayBody(
            RuntimeArray arr, RuntimeBase returned, boolean finalRelease) {
        // Clear localBindingExists: the named variable's scope is ending.
        // This allows subsequent refCount==0 events (from setLargeRefCounted
        // or flush) to correctly trigger callDestroy, since the local
        // variable no longer holds a strong reference.
        arr.localBindingExists = false;
        if (!finalRelease) RuntimeCode.releaseActiveLexicalOwner(arr);
        arr.releaseUnownedClosureCaptures();
        if (!finalRelease && arr.hasClosureCaptureOwner()) {
            arr.scopeExited = true;
            return;
        }
        // Alias arrays such as @_ do not own their original element slots.
        if (!finalRelease && (arr.refCount > 0 || arr.hasOwnerSlots()
                || temporaryRootDirectlyReferences(arr) || directlyReferences(returned, arr))) return;
        // A refalias list owns the scalar cells even though those cells are
        // not ordinary copied array values. Retire those cell identities only
        // when this RuntimeArray itself is no longer Perl-owned.
        arr.releaseReferenceListAliasCellOwners();
        // \$#array creates a scalar proxy, not an array reference.  Once the
        // lexical AV exits, that proxy becomes an orphaned undef lvalue; it
        // must not retain this Java RuntimeArray merely through its backing
        // reference.  A real escaped \@array has refCount > 0 above.
        arr.orphanArraySizeLvalues();
        if (!arr.elementsAliased) {
            for (RuntimeScalar elem : arr.elements) {
                if (returned == null) RuntimeScalar.releaseIoOwner(elem);
                else RuntimeScalar.releaseIoOwnerPreservingReturned(elem, returned);
                if (elem != null) elem.releaseContainerOwner(arr);
            }
        } else if (arr.ownedAliasElements != null) {
            for (RuntimeScalar elem : arr.ownedAliasElements) {
                if (returned == null) RuntimeScalar.releaseIoOwner(elem);
                else RuntimeScalar.releaseIoOwnerPreservingReturned(elem, returned);
                if (elem != null) elem.releaseContainerOwner(arr);
            }
        }
        // Skip container walks only when there are NO blessed objects AND NO
        // weak refs anywhere in the JVM (see scopeExitCleanupHash for details).
        if (!RuntimeBase.blessedObjectExists() && !WeakRefRegistry.weakRefsExist()
                && !RuntimeScalar.watcherCleanupNeeded()) return;
        // If the array has outstanding references (e.g., from \@array stored elsewhere),
        // do NOT clean up elements — the array is still alive and its elements are
        // accessible through the reference. Cleanup will happen when the last
        // reference is released (in DestroyDispatch.callDestroy).
        if (RuntimeScalar.watcherCleanupNeeded()) {
            RuntimeScalar.notifyDestroyedWatchersRecursively(arr);
        }
        if (arr.type == RuntimeArray.TIED_ARRAY && arr.elements instanceof TieArray tieArray) {
            tieArray.releaseTiedObject();
        }
        // Alias arrays such as @_ do not own their original elements. They can
        // still receive owned inserts via unshift/push before a goto &sub; in
        // that mixed case, release only those inserted elements.
        if (arr.elementsAliased) {
            if (arr.ownedAliasElements == null || arr.ownedAliasElements.isEmpty()) return;
            RuntimeScalar[] owned = arr.ownedAliasElements.toArray(new RuntimeScalar[0]);
            for (RuntimeScalar elem : owned) {
                deferDecrementRecursive(elem);
                arr.forgetOwnedAliasElement(elem);
            }
            return;
        }
        // Quick scan: check if any element either:
        //   1. Owns a refCount (was assigned via setLarge with a tracked referent), OR
        //   2. Is a direct blessed reference (blessId != 0), OR
        //   3. Is a container (array/hash ref) that might hold nested blessed refs
        // For case 3, we peek one level deep to avoid false positives for arrays
        // of plain-data arrayrefs (like the life_bitpacked @grid).
        boolean needsWalk = RuntimeScalar.watcherCleanupNeeded();
        for (RuntimeScalar elem : arr.elements) {
            if (elem == null || (elem.type & RuntimeScalarType.REFERENCE_BIT) == 0) continue;
            // Fast check: refCountOwned means this ref was properly tracked via setLarge
            if (elem.refCountOwned) { needsWalk = true; break; }
            if (!(elem.value instanceof RuntimeBase rb)) continue;
            // Direct blessed ref
            if (rb.blessId != 0 || rb.refCount >= 0) { needsWalk = true; break; }
            // Container ref — peek at children for reference-type elements
            if (rb instanceof RuntimeArray innerArr) {
                for (RuntimeScalar inner : innerArr.elements) {
                    if (inner != null && (inner.type & RuntimeScalarType.REFERENCE_BIT) != 0) {
                        needsWalk = true; break;
                    }
                }
            } else if (rb instanceof RuntimeHash innerHash) {
                for (RuntimeScalar inner : innerHash.elements.values()) {
                    if (inner != null && (inner.type & RuntimeScalarType.REFERENCE_BIT) != 0) {
                        needsWalk = true; break;
                    }
                }
            }
            if (needsWalk) break;
        }
        if (!needsWalk) return;
        for (RuntimeScalar elem : arr.elements) {
            deferDecrementRecursive(elem);
        }
    }

    public static void releaseTailCallArgs(RuntimeArray args) {
        if (args == null || !isActive() || !args.elementsOwned) return;
        if (args.elementsAliased) {
            if (args.ownedAliasElements == null || args.ownedAliasElements.isEmpty()) return;
            RuntimeScalar[] owned = args.ownedAliasElements.toArray(new RuntimeScalar[0]);
            for (RuntimeScalar elem : owned) {
                releaseTailCallArgElement(elem);
                args.forgetOwnedAliasElement(elem);
            }
            return;
        }
        for (RuntimeScalar elem : args.elements) {
            releaseTailCallArgElement(elem);
        }
        args.elementsOwned = false;
    }

    /**
     * Retire a source {@code @_} alias only when it is the referent's last
     * counted owner.  A {@code goto &sub} replaces that frame immediately, but
     * its aliases normally belong to the caller and must not be released here.
     * The last-owner restriction is what distinguishes a temporary call
     * argument from a shared value such as DBIx::Class's weak schema handle.
     */
    public static void releaseLastOwnedTailCallArgs(RuntimeArray args) {
        if (args == null || !isActive()) return;
        for (RuntimeScalar scalar : args.elements) {
            if (scalar == null
                    || !args.ownsElement(scalar)
                    || !scalar.refCountOwned
                    || (scalar.type & RuntimeScalarType.REFERENCE_BIT) == 0
                    || !(scalar.value instanceof RuntimeBase base)
                    || base.refCount != 1) {
                continue;
            }
            releaseTailCallArgElement(scalar);
        }
    }

    public static void releaseTailCallCodeRef(RuntimeScalar codeRef) {
        if (codeRef == null
                || !isActive()
                || !codeRef.refCountOwned
                || codeRef.type != RuntimeScalarType.CODE
                || !(codeRef.value instanceof RuntimeCode code)
                || code.refCount <= 0) {
            return;
        }
        releaseCountedOwnerImmediately(codeRef, "MortalList.releaseTailCallCodeRef");
    }

    private static void releaseTailCallArgElement(RuntimeScalar scalar) {
        if (scalar == null
                || !scalar.refCountOwned
                || (scalar.type & RuntimeScalarType.REFERENCE_BIT) == 0
                || !(scalar.value instanceof RuntimeBase base)
                || base.refCount <= 0) {
            return;
        }
        releaseCountedOwnerImmediately(scalar, "MortalList.releaseTailCallArgElement");
    }

    /**
     * Iteratively process a scalar value: if it holds a reference to a
     * tracked blessed object and owns a refCount, defer a decrement.
     * If it holds a reference to an unblessed container, walk into
     * its elements (iteratively with cycle detection to avoid
     * StackOverflowError on circular data structures like ExifTool's).
     */
    private static void deferDecrementRecursive(RuntimeScalar scalar) {
        if (scalar == null || (scalar.type & RuntimeScalarType.REFERENCE_BIT) == 0) return;
        if (WeakRefRegistry.isweak(scalar)) return;
        if (!(scalar.value instanceof RuntimeBase topBase)) return;

        // Fast path for leaf references (the overwhelmingly common case):
        // unblessed, untracked referents with no nested containers to walk.
        if (topBase.blessId == 0 && topBase.refCount == -1) {
            // Unblessed, untracked — nothing to do unless it's a container.
            if (topBase instanceof RuntimeArray arr) {
                // Check if any element is a tracked ref before iterating.
                boolean hasTracked = false;
                for (RuntimeScalar elem : arr.elements) {
                    if (elem != null && (elem.type & RuntimeScalarType.REFERENCE_BIT) != 0
                            && elem.value instanceof RuntimeBase eb
                            && (eb.blessId != 0 || eb.refCount >= 0)) {
                        hasTracked = true;
                        break;
                    }
                }
                if (!hasTracked) return;
            } else if (topBase instanceof RuntimeHash hash) {
                boolean hasTracked = false;
                for (RuntimeScalar val : hash.elements.values()) {
                    if (val != null && (val.type & RuntimeScalarType.REFERENCE_BIT) != 0
                            && val.value instanceof RuntimeBase vb
                            && (vb.blessId != 0 || vb.refCount >= 0)) {
                        hasTracked = true;
                        break;
                    }
                }
                if (!hasTracked) return;
            } else {
                return;  // Non-container unblessed untracked leaf — nothing to do
            }
        }

        // Slow path: tracked or blessed referents need full walk with cycle detection.
        // Use an explicit work queue + visited set to avoid stack overflow
        // on circular references (e.g., ExifTool's self-referential hashes).
        ArrayDeque<RuntimeScalar> work = new ArrayDeque<>();
        Set<RuntimeBase> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        work.add(scalar);

        while (!work.isEmpty()) {
            RuntimeScalar s = work.poll();
            if (s == null || (s.type & RuntimeScalarType.REFERENCE_BIT) == 0) continue;
            if (WeakRefRegistry.isweak(s)) continue;
            if (!(s.value instanceof RuntimeBase base)) continue;
            if (!visited.add(base)) continue;  // already visited — cycle

            if (base.blessId != 0) {
                boolean releasingLastOwner = false;
                if (s.refCountOwned && base.refCount > 0) {
                    if (base.refCountTrace) {
                        base.traceRefCount(0, "MortalList.deferDecrementRecursive (blessed, queued)");
                        base.releaseOwner(s, "deferDecrementRecursive blessed");
                    }
                    RuntimeBase.PendingOwnerRelease ownerRelease = base.queueOwnerRelease(
                            s, "MortalList.deferDecrementRecursive blessed");
                    // The selective count only covers counted edges. A live
                    // package, lexical, closure, or other explicit owner can
                    // still keep this container and its fields in use after
                    // this counted edge leaves. Walk the fields only when no
                    // current Perl owner identity remains.
                    releasingLastOwner = base.refCount == 1
                            && !base.hasPerlStrongOwnerIdentity();
                    LifecycleRuntimeState state = state();
                    markBoundaryWork(state);
                    queueDeferredBase(state, base, ownerRelease, null, s);
                } else if (base.refCount == 0) {
                    LifecycleRuntimeState state = state();
                    markBoundaryWork(state);
                    RuntimeBase.PendingOwnerRelease ownerRelease =
                            base.queueLifecycleOnlyAtZero(
                                    "MortalList.deferDecrementRecursive zero-count temporary");
                    queueDeferredBase(state, base, ownerRelease, null, s);
                    // A zero-count temporary's fields disappear with it only
                    // when no separate Perl owner still retains the container.
                    // Keep recursive cleanup aligned with the lifecycle token
                    // that will check those same owner identities at drain.
                    releasingLastOwner = !base.hasPerlStrongOwnerIdentity();
                }
                // A blessed hash/array is still a container.  When this slot
                // releases its final counted owner, its fields disappear with
                // it and must release their own referents too.  Do not walk a
                // shared object: another strong owner may still use its fields
                // (Sub::Quote keeps exactly this kind of external metadata
                // owner while its registry entry is weak).
                if (releasingLastOwner) {
                    if (base instanceof RuntimeArray arr) {
                        for (RuntimeScalar elem : arr.elements) {
                            if (elem != null) work.add(elem);
                        }
                    } else if (base instanceof RuntimeHash hash) {
                        for (RuntimeScalar val : hash.elements.values()) {
                            if (val != null) work.add(val);
                        }
                    }
                }
            } else {
                boolean hasDirectWeakElementRefs = containerHasWeakElementRefs(base);
                boolean hasCleanupTargets = hasDirectWeakElementRefs
                        || containerMayContainCleanupTargets(base);
                if (s.refCountOwned && base.refCount > 0) {
                    if (base.refCountTrace) {
                        base.traceRefCount(0, "MortalList.deferDecrementRecursive (unblessed container, queued)");
                    }
                    RuntimeBase.PendingOwnerRelease ownerRelease = base.queueOwnerRelease(
                            s, "MortalList.deferDecrementRecursive unblessed");
                    LifecycleRuntimeState state = state();
                    markBoundaryWork(state);
                    queueDeferredBase(state, base, ownerRelease, null, s);
                    if (!WeakRefRegistry.weakRefsExist() && base.refCount > 1) {
                        continue;
                    }
                    if (!hasCleanupTargets
                            || base.hasPerlStrongOwnerIdentity()) {
                        continue;
                    }
                } else if (base.refCount > 0
                        && !WeakRefRegistry.weakRefsExist()) {
                    // A non-owning copy of a still-counted unblessed container
                    // must not release that container's children. With no weak
                    // refs anywhere, there is no weak-ref cleanup that requires
                    // peeking inside before the container's own count reaches 0.
                    continue;
                } else if (base.refCount > 0
                        && (!hasCleanupTargets
                        || base.hasPerlStrongOwnerIdentity())) {
                    // This scalar is a non-owning copy of a still-live container.
                    // Cleaning it up must not walk into the shared container and
                    // release the original owner's children. A live Perl edge
                    // is identified by its owner slot; root reachability cannot
                    // reconstruct a missing edge after the fact.
                    continue;
                }
                // A registered lexical aggregate owns its element slots even
                // when another scalar reference to the aggregate is released.
                // Decrement this scalar's aggregate edge above, then leave the
                // elements for the lexical aggregate's own scope cleanup.
                if (base.localBindingExists && MyVarCleanupStack.isRegistered(base)) continue;
                // Walk into unblessed containers to find nested blessed refs
                if (base instanceof RuntimeArray arr) {
                    for (RuntimeScalar elem : arr.elements) {
                        if (elem != null) work.add(elem);
                    }
                } else if (base instanceof RuntimeHash hash) {
                    for (RuntimeScalar val : hash.elements.values()) {
                        if (val != null) work.add(val);
                    }
                }
            }
        }
    }

    static boolean containerMayContainCleanupTargets(RuntimeBase base) {
        if (!(base instanceof RuntimeArray || base instanceof RuntimeHash)) return false;
        ArrayDeque<RuntimeBase> work = new ArrayDeque<>();
        Set<RuntimeBase> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        work.add(base);

        while (!work.isEmpty()) {
            RuntimeBase cur = work.poll();
            if (cur == null || !visited.add(cur)) continue;
            if (cur instanceof RuntimeArray arr) {
                for (RuntimeScalar elem : arr.elements) {
                    if (scalarMayContainCleanupTarget(elem, work)) return true;
                }
            } else if (cur instanceof RuntimeHash hash) {
                for (RuntimeScalar value : hash.elements.values()) {
                    if (scalarMayContainCleanupTarget(value, work)) return true;
                }
            }
        }
        return false;
    }

    private static boolean scalarMayContainCleanupTarget(RuntimeScalar scalar,
                                                        ArrayDeque<RuntimeBase> work) {
        if (scalar == null) return false;
        if (WeakRefRegistry.hasWeakRefsTo(scalar)) return true;
        if ((scalar.type & RuntimeScalarType.REFERENCE_BIT) == 0) return false;
        if (!(scalar.value instanceof RuntimeBase base)) return false;
        if (WeakRefRegistry.hasWeakRefsTo(base)) return true;
        if (base.blessId != 0) return true;
        if (base instanceof RuntimeArray || base instanceof RuntimeHash) {
            work.add(base);
        } else if (base instanceof RuntimeCode && base.refCount >= 0) {
            return true;
        }
        return false;
    }

    private static boolean containerHasWeakElementRefs(RuntimeBase base) {
        if (!WeakRefRegistry.weakRefsExist()) return false;
        if (base instanceof RuntimeArray arr) {
            for (RuntimeScalar elem : arr.elements) {
                if (elem != null && WeakRefRegistry.hasWeakRefsTo(elem)) {
                    return true;
                }
            }
        } else if (base instanceof RuntimeHash hash) {
            for (RuntimeScalar value : hash.elements.values()) {
                if (value != null && WeakRefRegistry.hasWeakRefsTo(value)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Mortal-ize blessed refs with refCount==0 in a RuntimeList that will be
     * discarded (void-context call result). Without this, objects that were
     * blessed but never stored in a named variable would leak.
     * Only processes elements with refCount==0 (never-stored objects).
     */
    public static void mortalizeForVoidDiscard(RuntimeList result) {
        if (!isActive() || result == null) return;
        for (RuntimeBase elem : result.elements) {
            if (elem instanceof RuntimeScalar scalar
                    && (scalar.type & RuntimeScalarType.REFERENCE_BIT) != 0
                    && scalar.value instanceof RuntimeBase base) {
                if (scalar.hasStorageOwnerForVoidDiscard()) continue;
                boolean unblessedDiscardableContainer = base.blessId == 0
                        && (base instanceof RuntimeHash
                        || base instanceof RuntimeArray
                        || base instanceof RuntimeCode);
                boolean reachableCodeRef = base instanceof RuntimeCode
                        && scalar.isStoredInRegisteredContainerOwner();
                if (scalar.refCountOwned && base.refCount > 0 && unblessedDiscardableContainer
                        && !reachableCodeRef) {
                    if (base.refCountTrace) {
                        base.traceRefCount(0, "MortalList.mortalizeForVoidDiscard (queued owned container discard)");
                        base.releaseOwner(scalar, "mortalizeForVoidDiscard container");
                    }
                    RuntimeBase.PendingOwnerRelease ownerRelease = base.queueOwnerRelease(
                            scalar, "MortalList.mortalizeForVoidDiscard");
                    LifecycleRuntimeState state = state();
                    markBoundaryWork(state);
                    queueDeferredBase(state, base, ownerRelease, null, scalar);
                } else if (base.refCount == 0
                        && (base.blessId != 0
                        || base instanceof RuntimeHash
                        || base instanceof RuntimeArray
                        || base instanceof RuntimeCode)) {
                    RuntimeBase.PendingOwnerRelease ownerRelease;
                    if (scalar.refCountOwned) {
                        ownerRelease = base.queueOwnerRelease(
                                scalar, "MortalList.mortalizeForVoidDiscard zero-count owner");
                    } else {
                        ownerRelease = base.queueLifecycleOnlyAtZero(
                                "MortalList.mortalizeForVoidDiscard zero-count temporary");
                    }
                    LifecycleRuntimeState state = state();
                    markBoundaryWork(state);
                    queueDeferredBase(state, base, ownerRelease, null, scalar);
                }
            }
        }
    }

    // Mark stack for scoped flushing (analogous to Perl 5's SAVETMPS).
    // Each mark records the pending list size at scope entry, so that
    // popAndFlush() only processes entries added within that scope.
    private static void processDeferredEntriesFrom(
            int pendingStartIdx, int tiedReleaseStartIdx, int ioReleaseStartIdx,
            int untrackedReturnOwnerStartIdx) {
        LifecycleRuntimeState state = state();
        state.reachabilityDrainDepth++;
        try {
            int pendingIdx = pendingStartIdx;
            int tiedReleaseIdx = tiedReleaseStartIdx;
            int ioReleaseIdx = ioReleaseStartIdx;
            int untrackedReturnOwnerIdx = untrackedReturnOwnerStartIdx;
            while (pendingIdx < state.pending.size()
                    || tiedReleaseIdx < state.pendingTiedReleases.size()
                    || ioReleaseIdx < state.pendingIoReleases.size()
                    || untrackedReturnOwnerIdx
                        < state.pendingUntrackedReturnOwnerReleases.size()) {
                while (tiedReleaseIdx < state.pendingTiedReleases.size()) {
                    state.pendingTiedReleases.get(tiedReleaseIdx++).releaseTiedObject();
                }
                while (pendingIdx < state.pending.size()) {
                    int currentIndex = pendingIdx++;
                    RuntimeBase.PendingOwnerRelease ownerRelease =
                            state.pendingOwnerReleases.get(currentIndex);
                    String transientOwnerKind =
                            state.pendingTransientOwnerKinds.get(currentIndex);
                    RuntimeBase base = state.pending.get(currentIndex);
                    boolean argumentAliasOwnsBase =
                            base.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_ALIAS) != 0
                                && base.countedOwnerSlotCount() == 0;
                    if (argumentAliasOwnsBase) {
                        state.deferredArgumentOwnerReleases.add(
                                new DeferredArgumentOwnerRelease(base, ownerRelease,
                                        transientOwnerKind));
                    } else {
                        processDeferredBase(base, false, ownerRelease, transientOwnerKind);
                    }
                }
                while (ioReleaseIdx < state.pendingIoReleases.size()) {
                    RuntimeScalar.releaseIoOwner(state.pendingIoReleases.get(ioReleaseIdx++));
                }
                while (untrackedReturnOwnerIdx
                        < state.pendingUntrackedReturnOwnerReleases.size()) {
                    RuntimeScalar scalar =
                            state.pendingUntrackedReturnOwnerReleases.get(untrackedReturnOwnerIdx++);
                    if (scalar != null) scalar.releaseMortalReturnValueOwner();
                }
            }
        } finally {
            state.reachabilityDrainDepth--;
        }
        drainDeferredArgumentOwnerReleases();
    }

    static void drainDeferredArgumentOwnerReleases() {
        LifecycleRuntimeState state = state();
        if (state.deferredArgumentOwnerReleases.isEmpty()) return;
        ArrayList<DeferredArgumentOwnerRelease> pending =
                new ArrayList<>(state.deferredArgumentOwnerReleases);
        state.deferredArgumentOwnerReleases.clear();
        for (DeferredArgumentOwnerRelease deferred : pending) {
            if (deferred.base.countedOwnerSlotCount() != 0) {
                processDeferredBase(deferred.base, false, deferred.ownerRelease,
                        deferred.transientOwnerKind);
            } else if (deferred.base.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_ALIAS) != 0) {
                state.deferredArgumentOwnerReleases.add(deferred);
            } else {
                processDeferredBase(deferred.base, false, deferred.ownerRelease,
                        deferred.transientOwnerKind);
            }
        }
    }

    /**
     * Process all pending decrements. Called at statement boundaries.
     * Equivalent to Perl 5's FREETMPS.
     * <p>
     * Reentrancy guard: flush() can be called recursively when callDestroy()
     * triggers DESTROY → doCallDestroy → scopeExitCleanupHash → flush().
     * Without the guard, the inner flush() re-processes entries from the same
     * pending list that the outer flush is iterating over, causing double
     * decrements and premature destruction (e.g., DBIx::Class Schema clones
     * being destroyed mid-construction, clearing weak refs to still-live
     * objects). With the guard, only the outermost flush() processes entries;
     * new entries added by cascading DESTROY are picked up by the outer
     * loop's continuing iteration (since it checks pending.size() each pass).
     * <p>
     * Also used by {@link RuntimeList#setFromList} to suppress flushing during
     * list assignment materialization. This prevents premature destruction of
     * return values while the caller is still capturing them into variables.
     */
    /**
     * Suppress or unsuppress flushing. Used by setFromList to prevent pending
     * decrements from earlier scopes (e.g., clone's $self) being processed
     * during the materialization of list assignment (@_ → local vars).
     * Without this, return values from chained method calls like
     * {@code shift->clone->connection(@_)} can be destroyed mid-capture.
     *
     * @return the previous value of the flushing flag (for nesting).
     */
    public static boolean suppressFlush(boolean suppress) {
        LifecycleRuntimeState state = state();
        boolean prev = state.flushing;
        state.flushing = suppress;
        return prev;
    }

    // Phase B2a (refcount_alignment_52leaks_plan.md): throttled
    // auto-sweep of the weak-ref registry, gated by ModuleInitGuard.
    // Runs at statement boundaries (flush points) but skips while
    // inside require/use/do/BEGIN/eval-STRING code paths — those
    // often rely on weak-refed intermediate state that the sweep
    // would prematurely clear.
    // Tuned for DBIC-scale tests: 5s throttle. Shorter intervals
    // (100ms, 500ms) fire too frequently — 52leaks.t creates thousands
    // of weaken'd refs and each sweep's System.gc() + weak-ref cascade
    // can run for tens of seconds. 5s gives the walker time to amortize.
    /** Compatibility hook; weak-reference cleanup is driven by owner release. */
    public static void requestImmediateWeakSweep() {
    }

    /** Compatibility hook; weak-reference cleanup is driven by owner release. */
    static void requestCorrectiveWeakSweep() {
    }

    /** Compatibility hook; weak-reference cleanup is driven by owner release. */
    public static void requestTargetedWeakSweep(RuntimeBase referent) {
    }

    /** Compatibility hook retained for callers compiled against the old sweep API. */
    public static boolean requestWeakSweepsForDestroyedContainer(RuntimeBase container) {
        return false;
    }

    static void finalizeClearedAggregateOwnerAfterScopeExit(RuntimeBase base) {
        if (!isActive()
                || base == null
                || !base.clearedOwnedAggregateElement
                || !WeakRefRegistry.hasWeakRefsTo(base)) {
            return;
        }
        // Scope cleanup retires the exact lexical owner before reaching this
        // point. Package, container, and capture edges have their own owner
        // slots; temporary roots are checked separately because they are
        // evaluation scaffolding rather than Perl ownership. Use validated
        // identities so stale slots and compatibility counts cannot preserve
        // an aggregate after its final owner cell is cleared.
        if (DestroyDispatch.hasLivePerlOwnerIdentity(base)
                || temporaryRootDirectlyReferences(base)) {
            return;
        }
        // A deferred count release has already retired its live owner slot,
        // but the compatibility count is not decremented until the mortal
        // queue drains. Let that exact release decide finalization instead of
        // racing it with scope-exit cleanup.
        if (base.hasPendingOwnerRelease()) return;
        // Root reachability does not establish a Perl owner. Keep the measured
        // traversal available to the synthetic direct-registration cost test,
        // but ignore its result when deciding the aggregate's lifecycle.
        if (base.refCount <= 0 && activeReachabilityQueryStats() != null) {
            isReachableFromExternalRootCached(base);
        }
        base.refCount = Integer.MIN_VALUE;
        DestroyDispatch.callDestroy(base);
    }

    // D-W6.18 perf: cached reachable-set, valid for the duration of a
    // single flush() invocation. The walker BFS is O(globals); without
    // this cache, calling it once per pending target turns flush into
    // O(targets × globals). DBIC stresses this hard because many
    // blessed objects are stored-in-package-global AND have weak refs
    // (Schema/ResultSource back-refs) — every flush would re-walk the
    // full global graph for each one. Computed lazily on first need.
    private static boolean isReachableCached(RuntimeBase base) {
        LifecycleRuntimeState state = state();
        if (state.flushReachableCache == null) {
            ReachabilityWalker w = new ReachabilityWalker();
            state.flushReachableCache = w.walk();
        }
        return state.flushReachableCache.contains(base);
    }

    static boolean isReachableFromExternalRootCached(RuntimeBase base) {
        if (ReachabilityWalker.isReachableFromTemporaryRoots(base)) {
            return true;
        }
        return isReachableFromPersistentRootCached(base);
    }

    static boolean isReachableFromPersistentRootCached(RuntimeBase base) {
        LifecycleRuntimeState state = state();
        if (state.externalRootSnapshot == null) {
            state.externalRootSnapshot = new ReachabilityWalker.ExternalRootSnapshot();
        }
        if (state.externalRootSnapshot.isReachableFromNonLexicalRoot(base)) {
            return true;
        }
        if (!state.externalRootSnapshot.complete()) {
            // An incomplete external-root walk cannot prove this target has
            // no other owner. Keep it alive without rebuilding the broader
            // full-root graph, which also cannot preserve this query's
            // direct-lexical exclusion semantics.
            return true;
        }
        if (state.liveRootSnapshot == null) {
            state.liveRootSnapshot = new ReachabilityWalker.LiveRootSnapshot();
        }
        return state.liveRootSnapshot.isReachable(base);
    }

    static void invalidateExternalRootSnapshot() {
        state().externalRootSnapshot = null;
        state().fullRootSnapshot = null;
        state().unblessedAggregateRootSnapshot = null;
        state().scalarRootSnapshot = null;
    }

    static void invalidateAllRootSnapshots() {
        invalidateExternalRootSnapshot();
        invalidateLiveRootSnapshot();
    }

    private static boolean isReachableFromFullRootSnapshot(RuntimeBase base) {
        LifecycleRuntimeState state = state();
        if (state.fullRootSnapshot == null) {
            state.fullRootSnapshot = ReachabilityWalker.reachableFromRootsSnapshotWithStatus();
        }
        return state.fullRootSnapshot.isReachable(base);
    }

    static boolean isUnblessedAggregateReachableDuringDrain(RuntimeBase base) {
        LifecycleRuntimeState state = state();
        boolean drainActive = state.flushing || state.reachabilityDrainDepth > 0;
        if (drainActive && state.unblessedAggregateDrainSnapshotIncomplete) return true;
        if (drainActive && state.unblessedAggregateDrainRoots.contains(base)) return true;
        if (state.fullRootSnapshot != null) {
            return recordUnblessedAggregateSnapshotResult(
                    state, state.fullRootSnapshot, base, drainActive);
        }
        if (state.unblessedAggregateRootSnapshot != null) {
            return recordUnblessedAggregateSnapshotResult(
                    state, state.unblessedAggregateRootSnapshot, base, drainActive);
        }
        if (ReachabilityWalker.isReachableFromTemporaryRoots(base)) return true;
        if (state.externalRootSnapshot != null
                && state.externalRootSnapshot.isReachableFromNonLexicalRoot(base)) {
            if (drainActive) state.unblessedAggregateDrainRoots.add(base);
            return true;
        }
        if (state.liveRootSnapshot != null && state.liveRootSnapshot.isReachable(base)) {
            if (drainActive) state.unblessedAggregateDrainRoots.add(base);
            return true;
        }
        // The package-root and live-lexical snapshots cover the same strong
        // roots without tying their lifetimes together. Scope exits invalidate
        // the lexical view frequently, while package roots often stay stable;
        // rebuilding a combined graph here repeats the full package walk for
        // each cleanup batch during module loading and request handling.
        boolean reachable = isReachableFromExternalRootCached(base);
        if (drainActive) {
            boolean incomplete = state.externalRootSnapshot == null
                    || !state.externalRootSnapshot.complete()
                    || (state.liveRootSnapshot != null
                        && !state.liveRootSnapshot.complete());
            if (incomplete) {
                state.unblessedAggregateDrainSnapshotIncomplete = true;
                state.unblessedAggregateDrainRoots.clear();
            } else if (reachable && !state.unblessedAggregateDrainRoots.contains(base)) {
                if (state.unblessedAggregateDrainRoots.size()
                        >= MAX_UNBLESSED_AGGREGATE_DRAIN_ROOTS) {
                    state.unblessedAggregateDrainSnapshotIncomplete = true;
                    state.unblessedAggregateDrainRoots.clear();
                } else {
                    state.unblessedAggregateDrainRoots.add(base);
                    ReachabilityQueryStats stats = activeReachabilityQueryStats();
                    if (stats != null) {
                        stats.unblessedAggregateDrainPositiveProofsCached++;
                    }
                }
            }
        }
        return reachable;
    }

    private static boolean recordUnblessedAggregateSnapshotResult(
            LifecycleRuntimeState state,
            ReachabilityWalker.RootReachabilitySnapshot snapshot,
            RuntimeBase base,
            boolean drainActive) {
        boolean reachable = snapshot.isReachable(base);
        if (drainActive) {
            if (!snapshot.complete()) {
                state.unblessedAggregateDrainSnapshotIncomplete = true;
                state.unblessedAggregateDrainRoots.clear();
            } else if (reachable && !state.unblessedAggregateDrainRoots.contains(base)) {
                if (state.unblessedAggregateDrainRoots.size()
                        >= MAX_UNBLESSED_AGGREGATE_DRAIN_ROOTS) {
                    state.unblessedAggregateDrainSnapshotIncomplete = true;
                    state.unblessedAggregateDrainRoots.clear();
                } else {
                    state.unblessedAggregateDrainRoots.add(base);
                    ReachabilityQueryStats stats = activeReachabilityQueryStats();
                    if (stats != null) {
                        stats.unblessedAggregateDrainPositiveProofsCached++;
                    }
                }
            }
        }
        return reachable;
    }

    /**
     * Check an owner scalar through its containing aggregate using the shared
     * drain snapshot. Owner scalars nested below package roots are otherwise
     * verified by a separate full root walk for every deferred referent.
     */
    static boolean isScalarReachableThroughRootedContainer(RuntimeScalar scalar) {
        LifecycleRuntimeState state = state();
        if ((!state.flushing && state.reachabilityDrainDepth == 0)
                || scalar == null || WeakRefRegistry.isweak(scalar)) {
            return false;
        }
        RuntimeBase container = scalar.containerOwner;
        boolean containsScalar;
        if (container instanceof RuntimeHash hash) {
            containsScalar = hash.elements.containsValue(scalar);
        } else if (container instanceof RuntimeArray array) {
            containsScalar = array.elements.contains(scalar);
        } else {
            containsScalar = false;
        }
        return containsScalar && isReachableFromFullRootSnapshot(container);
    }

    static void invalidateLiveRootSnapshot() {
        state().liveRootSnapshot = null;
        state().fullRootSnapshot = null;
        state().unblessedAggregateRootSnapshot = null;
        state().scalarRootSnapshot = null;
    }

    static void invalidateFullRootSnapshotIfReachable(RuntimeBase changedNode) {
        PerlRuntime runtime = PerlRuntime.currentOrNull();
        if (runtime == null) return;
        LifecycleRuntimeState state = runtime.lifecycleState;
        ReachabilityWalker.LiveRootSnapshot liveSnapshot = state.liveRootSnapshot;
        boolean liveReachable = liveSnapshot != null && liveSnapshot.mayContain(changedNode);
        ReachabilityWalker.RootReachabilitySnapshot snapshot = state.fullRootSnapshot;
        boolean fullReachable = snapshot != null && snapshot.isReachable(changedNode);
        ReachabilityWalker.RootReachabilitySnapshot unblessedSnapshot =
                state.unblessedAggregateRootSnapshot;
        boolean unblessedReachable = unblessedSnapshot != null
                && unblessedSnapshot.isReachable(changedNode);
        if ((snapshot != null && (!snapshot.complete() || fullReachable))
                || (unblessedSnapshot != null
                    && (!unblessedSnapshot.complete() || unblessedReachable))) {
            state.fullRootSnapshot = null;
            state.unblessedAggregateRootSnapshot = null;
        }
        if (liveReachable) state.liveRootSnapshot = null;
    }

    static void invalidateFullRootSnapshotForScalarClear() {
        PerlRuntime runtime = PerlRuntime.currentOrNull();
        if (runtime != null) {
            runtime.lifecycleState.liveRootSnapshot = null;
            runtime.lifecycleState.fullRootSnapshot = null;
            runtime.lifecycleState.unblessedAggregateRootSnapshot = null;
        }
    }

    /**
     * Reuse scalar-owner reachability across one cleanup drain. Return
     * {@code null} outside a drain so callers retain the target-specific walk
     * and its original lifetime semantics.
     */
    static Boolean isScalarReachableFromDrainSnapshot(RuntimeScalar scalar) {
        LifecycleRuntimeState state = state();
        if (!state.flushing && state.reachabilityDrainDepth == 0) return null;
        if (state.scalarRootSnapshot == null) {
            state.scalarRootSnapshot =
                    ReachabilityWalker.scalarReachabilitySnapshot();
        }
        return state.scalarRootSnapshot.isReachable(scalar);
    }

    private static void invalidateDrainReachabilityCaches() {
        LifecycleRuntimeState state = state();
        state.flushReachableCache = null;
        state.scalarRootSnapshot = null;
        state.fullRootSnapshot = null;
        if (!state.flushing && state.reachabilityDrainDepth == 0) {
            state.unblessedAggregateDrainRoots.clear();
            state.unblessedAggregateDrainSnapshotIncomplete = false;
        }
    }

    private static void processDeferredBase(RuntimeBase base, boolean clearWeakRefsForLocalBinding,
                                            RuntimeBase.PendingOwnerRelease ownerRelease,
                                            String transientOwnerKind) {
        if (base.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_ALIAS) != 0
                && base.countedOwnerSlotCount() == 0) {
            state().deferredArgumentOwnerReleases.add(
                    new DeferredArgumentOwnerRelease(base, ownerRelease,
                            transientOwnerKind));
            return;
        }
        ReachabilityQueryStats queryStats = activeReachabilityQueryStats();
        if (queryStats != null) queryStats.deferredBasesProcessed++;
        if (ownerRelease != null
                && !base.completeQueuedOwnerRelease(
                        ownerRelease, "MortalList.processDeferredBase")) {
            // A release token can be encountered through more than one nested
            // drain. Its count transition and lifecycle decision are one
            // obligation, so a completed token must not decrement the referent
            // again.
            return;
        }
        base.releaseTransientTraceOwner(transientOwnerKind,
                "MortalList.processDeferredBase");
        boolean hasWeakRefs = WeakRefRegistry.hasWeakRefsTo(base);
        // Production edge releases carry an exact slot token. A lifecycle-only
        // boundary carries completion provenance but no released edge; its
        // cleanup decision must follow current owner identities, not a root walk.
        // Reachability repair remains available only to the legacy unpaired
        // decrement entry point. A lifecycle-only token is not a released Perl
        // edge and must not trigger reconstruction from a root walk; its
        // cleanup decision follows the current owner identities below.
        boolean legacyUnpairedDecrement = ownerRelease == null;
        boolean finalRelease = false;
        if (base.refCount > 0) {
            finalRelease = base.releaseTrackedOwnerCount(
                    "MortalList.flush (deferred decrement)");
        } else if (base.refCount == 0 && ownerRelease != null) {
            // The queue token may represent a real edge released after a
            // transient undercount, or an explicit zero-count temporary
            // finalization request. Process that lifecycle boundary directly;
            // do not manufacture a positive refCount just to enter this path.
            finalRelease = true;
        }
        if (finalRelease) {
            boolean aggregateReferent = base instanceof RuntimeArray || base instanceof RuntimeHash;
            // Aggregate owner slots have their own deferred-release rule below.
            // Refresh scalar-cell owners here for other referents before the
            // final counted release checks whether any owner slot remains.
            boolean hasUntrackedReferenceOwner = !aggregateReferent
                    && base.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE) != 0
                    && base.hasUntrackedReferenceOwnerSlotIdentity();
            if (base instanceof RuntimeCode code && code.hasActiveCodeOwner()) {
                code.deferFinalReleaseToActiveCode();
            } else if (base.hasSemanticCaptureOwner()) {
                // Both native and legacy closure-pad slots identify the Perl
                // edge that keeps this referent alive. The direct PerlIO::via
                // hold is runtime scaffolding around PUSHED; when POPPED drops
                // it, retain the tracked zero count and let the closure slot
                // carry the remaining lifecycle. Ordinary scalar weakening
                // continues to use WEAKLY_TRACKED while a semantic capture is
                // the only remaining strong path.
                boolean releasedViaLayerOwner = ownerRelease != null
                        && ownerRelease.ownerSlotRelease != null
                        && ownerRelease.ownerSlotRelease.kind()
                                == PerlOwnerSlot.Kind.IO_VIA_LAYER;
                if (ownerRelease == null || releasedViaLayerOwner) {
                    base.deferZeroCountFinalReleaseToUncountedOwnerSlots();
                } else {
                    base.deferFinalReleaseToUncountedOwnerSlots();
                }
            } else if (base instanceof RuntimeScalar scalar
                    && scalar.referencedByScalarReference
                    && (scalar.ownerSlotCount(PerlOwnerSlot.Kind.ARRAY_ALIAS_CELL) != 0
                        || scalar.ownerSlotCount(PerlOwnerSlot.Kind.HASH_ALIAS_CELL) != 0)) {
                // Refalias aggregate slots own scalar cells without adding a
                // legacy reference count. Keep the zero-count cell alive
                // until its last alias slot releases and runs scalar cleanup.
                base.deferZeroCountFinalReleaseToUncountedOwnerSlots();
            } else if (base instanceof RuntimeScalar scalar
                    && scalar.referencedByScalarReference
                    && base.hasScalarCellStorageOwnerSlotIdentity()
                    && base.hasUncountedPerlOwnerSlotIdentity()) {
                // A registered lexical/package scalar cell is itself the
                // referent of \$cell. Retain that lifetime at refCount zero
                // through its exact storage identity; localBindingExists is
                // metadata and cannot stand in for the Perl edge.
                base.deferZeroCountFinalReleaseToUncountedOwnerSlots();
            } else if (base.hasTemporaryRootOwnerSlotIdentity()) {
                // A snapshot root is a runtime lease, not a Perl strong edge.
                // Keep its cloned weak observers intact for the lease lifetime
                // without fabricating a positive refCount. Releasing the last
                // lease completes the pending weak cleanup through the slot.
                base.deferFinalReleaseToTemporaryRootSlots();
            } else if ((ownerRelease != null
                            || base.ownerSlotCount(PerlOwnerSlot.Kind.CODE_STATE) != 0)
                    && base.hasCurrentUncountedPerlOwnerSlotIdentity()) {
                // An exact release token makes current uncounted Perl edges
                // authoritative. Persistent CODE state is also a direct owner
                // when a legacy bridge decrement has no token. Other tokenless
                // synthetic releases retain the compatibility recovery below
                // until their producers are migrated. Temporary roots and IO
                // resource leases are excluded by the Perl-owner predicate.
                base.deferFinalReleaseToUncountedOwnerSlots();
            } else if (base.localBindingExists && !(base instanceof RuntimeCode)) {
                if (base instanceof RuntimeScalar scalar
                        && scalar.referencedByScalarReference
                        && !scalar.hasClosureCaptureOwner()
                        && !MyVarCleanupStack.isRegistered(scalar)) {
                    // The declaring lexical is gone and this was the last
                    // counted scalar reference.  A returned blessed CODE
                    // scalar can miss the normal binding cleanup because the
                    // compiler conservatively treats it as captured while its
                    // subroutine exits.  Do not let that stale lexical marker
                    // suppress DESTROY after the final container deletion.
                    base.localBindingExists = false;
                    base.refCount = Integer.MIN_VALUE;
                    DestroyDispatch.callDestroy(base);
                    return;
                }
                // Named container: local variable may still exist. Skip callDestroy.
                // Cleanup will happen at scope exit (scopeExitCleanupHash/Array).
                //
                // Do NOT clear weak refs here for normal flush paths:
                // localBindingExists=true means the container is still alive via
                // its lexical slot. Test op/hashassign.t 218 (bug #76716,
                // "undef %hash should not zap weak refs") requires that
                // `is $p, \%tb; undef %tb;` does not zap the weak ref $p to %tb.
                if (clearWeakRefsForLocalBinding) {
                    WeakRefRegistry.clearWeakRefsTo(base);
                }
            } else if (base.ownerSlotCount(PerlOwnerSlot.Kind.PACKAGE_GLOB_SLOT) != 0) {
                // A named IO stash slot directly owns its typeglob even though
                // that edge does not participate in the legacy referent count.
                // Keep weak observers until the exact stash entry is removed.
                base.deferFinalReleaseToUncountedOwnerSlots();
            } else if (!legacyUnpairedDecrement
                    && base.hasCurrentUncountedPerlOwnerSlotIdentity()) {
                // A current lexical, package, alias, or resource slot can keep
                // the referent alive without contributing to the selective
                // count. Carry its lifecycle obligation on that exact identity.
                base.deferFinalReleaseToUncountedOwnerSlots();
            } else if (isFinalCountedReferentRelease(base, ownerRelease)) {
                // This decrement retired an identified scalar/element edge.
                // Every other migrated edge is already absent from the ledger;
                // a cached root snapshot must not invent a replacement owner.
                base.refCount = Integer.MIN_VALUE;
                DestroyDispatch.callDestroyFromCountedOwner(base);
            } else if (!(ownerRelease == null && transientOwnerKind == null
                            && hasPackageRootedCurrentAggregateOwner(base))
                    && base.restoreVerifiedCurrentCountedOwnerSlotRefCount()) {
                // Exact active scalar, element, invocant, or native slots are
                // the ownership proof here. This narrow count-alignment bridge
                // does not inspect roots or walk the runtime object graph.
            } else if (legacyUnpairedDecrement && hasWeakRefs
                    && restoreReachableOwnerCount(base)) {
                // Preserve the legacy unpaired hook's diagnostic/recovery walk
                // while its callers migrate. Production releases carry an
                // exact owner-slot token and bypass this compatibility path.
            } else if (base.blessId != 0
                    && base.restoreUnregisteredScalarCellOwnerRefCount()) {
                // Last compatibility bridge: an unregistered standalone
                // scalar cell has no storage registry to prove its edge.
            } else if (base instanceof RuntimeScalarReadOnly
                    && RuntimeCode.isInstalledPadConstant(base)) {
                // A readonly scalar referenced through \("literal") has an
                // explicit owner slot from each live CV optree that contains it.
                // Defer lifecycle cleanup to those slots instead of fabricating
                // a legacy count that has no corresponding Perl reference.
                base.deferFinalReleaseToUncountedOwnerSlots();
            } else if (!legacyUnpairedDecrement
                    && (base.hasNonPackageAggregateUncountedOwnerSlotIdentity()
                        || (aggregateReferent
                            ? (base.blessId != 0 && !hasWeakRefs
                                && base.hasUntrackedReferenceOwnerSlotIdentity())
                            : hasUntrackedReferenceOwner))) {
                // Counted owner recovery is complete above. A surviving
                // non-package aggregate slot, or an untracked package owner of
                // a blessed aggregate without direct weak observers, defers
                // cleanup until its release. Blessed aggregates with direct
                // weak observers still use the root proof below.
                base.deferFinalReleaseToUncountedOwnerSlots();
            } else if (legacyUnpairedDecrement
                    && base.blessId == 0
                    && (base instanceof RuntimeArray || base instanceof RuntimeHash)
                    && isUnblessedAggregateReachableDuringDrain(base)) {
                // Keep legacy unpaired decrements on the shared drain proof.
                // Ordinary aggregate cleanup carries its owner-slot provenance
                // and decides lifetime from current Perl owners instead.
                base.refCount = 1;
            } else if (legacyUnpairedDecrement
                    && base.blessId != 0
                    && hasWeakRefs
                    && (isReachableFromExternalRootCached(base)
                    || isReachableFromFullRootSnapshot(base))) {
                // A legacy unpaired release may temporarily consume the
                // selective count of an object retained below a package root.
                // Mojolicious::Lite keeps its route tree in a package
                // singleton; a mounted route also has a weak parent link from
                // the embedded application's route root. When the request Match
                // object is destroyed, its endpoint release can make that live
                // route dip to zero even though the singleton's children array
                // still owns it. The inherited Mojo::Base::DESTROY method makes
                // the ordinary no-DESTROY guard inapplicable, and recursively
                // destroying the route tears down the mounted renderer helper
                // namespace. Preserve only objects proven reachable from a
                // live Perl root; final owner removal still destroys them at
                // a later boundary.
                base.refCount = 1;
            } else if (legacyUnpairedDecrement
                    && base.blessId != 0
                    && hasWeakRefs
                    && !blessedClassHasDestroy(base)
                    && (isReachableFromExternalRootCached(base)
                    || isReachableFromFullRootSnapshot(base))) {
                // A legacy unpaired release can make the selective count reach
                // zero while an ordinary blessed object is still held by a
                // live lexical. Test::Refcount exercises this shape; explicit
                // production releases rely on current owner identities.
                base.refCount = 1;
            } else if (legacyUnpairedDecrement
                    && base.blessId != 0
                    && base.storedInPackageGlobal
                    && hasWeakRefs
                    && isReachableCached(base)) {
                // Module-global metadata such as Moose/Class::MOP metaclasses
                // can transiently hit zero in the selective refcount model.
                // If the walker can still reach the object from Perl-visible
                // roots, keep weak links to it intact.
            } else {
                // Call depth is not ownership. If every counted and direct
                // owner check above is empty, a weak referent is final here;
                // leaving it for a later sweep would strand the weak scalars.
                base.refCount = Integer.MIN_VALUE;
                DestroyDispatch.callDestroy(base);
            }
        }
        // A prior compatibility branch may preserve refCount at zero while a
        // nested scope is unwinding. The exact owner release is still pending
        // in this call, so recheck its ledger after that branch: once this was
        // the final trusted slot and no other Perl binding remains, finish the
        // release here instead of relying on a later reachability sweep.
        if (base.refCount == 0
                && isFinalCountedReferentRelease(base, ownerRelease)
                && !base.localBindingExists
                && !base.hasClosureCaptureOwner()
                && !base.hasPendingReturnOwner()
                && !GlobalVariable.isPackageRootedAggregate(base)
                && !(base instanceof RuntimeCode code
                    && (code.hasActiveCodeOwner()
                        || code.hasStashCodeSlotOwner()))) {
            base.refCount = Integer.MIN_VALUE;
            DestroyDispatch.callDestroyFromCountedOwner(base);
        }
        if (base instanceof RuntimeCode code) code.finishActiveCodeOwnerRelease();
        if (base instanceof RuntimeScalar scalar) {
            PerlOwnerSlot.finishDeferredScalarCellRelease(scalar);
        }
    }

    private static boolean blessedClassHasDestroy(RuntimeBase base) {
        String className = NameNormalizer.getBlessStr(base.blessId);
        return className != null && DestroyDispatch.classHasDestroy(base.blessId, className);
    }

    static boolean canTrustFinalCountedRelease(RuntimeBase base) {
        boolean supportedReferent = base instanceof RuntimeArray || base instanceof RuntimeHash
                || (base instanceof RuntimeScalar scalar
                    && !(scalar instanceof RuntimeScalarReadOnly)
                    && !scalar.hasClosureCaptureOwner())
                || (base instanceof RuntimeCode code
                    && (code.hasCountedCodeSlots || !code.hasStashCodeSlotOwner())
                    && !SpecialBlock.hasPendingEndBlock(code));
        return supportedReferent && !base.hasOwnerSlots();
    }

    private static boolean isFinalCountedReferentRelease(
            RuntimeBase base, RuntimeBase.PendingOwnerRelease release) {
        if (release == null || release.ownerSlotRelease == null) return false;
        PerlOwnerSlot.Kind kind = release.ownerSlotRelease.kind();
        if (kind == PerlOwnerSlot.Kind.IO_VIA_LAYER) return !base.hasOwnerSlots();
        if (!canTrustFinalCountedRelease(base)) return false;
        return switch (kind) {
            case SCALAR_CELL, CODE_SLOT, ARRAY_ELEMENT, HASH_ELEMENT, ARGUMENT_VALUE,
                    ARGUMENT_CONTAINER, RETURN_VALUE, TIE_HANDLER,
                    ARGUMENT_TIE_HANDLER, SCALAR_REFERENCE_CONTENTS -> true;
            default -> false;
        };
    }

    private static boolean restoreReachableOwnerCount(RuntimeBase base) {
        int ownerCount = base.reachableOwnerCount();
        if (ownerCount == 0) return false;
        base.refCount = ownerCount;
        return true;
    }

    private static boolean hasPackageRootedCurrentAggregateOwner(RuntimeBase base) {
        for (PerlOwnerSlot slot : base.ownerSlotSnapshot()) {
            if (!slot.isActive() || slot.referent() != base
                    || (slot.kind() != PerlOwnerSlot.Kind.SCALAR_CELL
                        && slot.kind() != PerlOwnerSlot.Kind.ARRAY_ELEMENT
                        && slot.kind() != PerlOwnerSlot.Kind.HASH_ELEMENT)) {
                continue;
            }
            RuntimeScalar owner = slot.countedScalarOwner();
            if (owner == null) continue;
            boolean currentOwner = slot.kind() == PerlOwnerSlot.Kind.SCALAR_CELL
                    ? owner.isCurrentCountedScalarOwner(base, slot)
                    : owner.isCurrentAggregateElementOwner(base, slot);
            if (!currentOwner) continue;
            RuntimeBase aggregate = owner.getContainerOwner();
            if (aggregate != null && (aggregate.storedInPackageGlobal
                    || GlobalVariable.isPackageRootedAggregate(aggregate))) {
                return true;
            }
        }
        return false;
    }

    public static void flush() {
        LifecycleRuntimeState state = state();
        if (!state.mortalActive) return;
        if (state.flushing) return;
        if (state.sweepPendingAfterOuterDestroy) {
            // Preserve the old diagnostic counters for explicit post-DESTROY
            // requests. This snapshots CODE captures only; it never clears
            // weak refs, dispatches DESTROY, or changes owner state.
            state.sweepPendingAfterOuterDestroy = false;
            ReachabilityWalker.inspectWeakBackrefCapturesForDiagnostics();
        }
        if (state.pending.isEmpty() && state.pendingTiedReleases.isEmpty()
                && state.pendingIoReleases.isEmpty()
                && state.pendingUntrackedReturnOwnerReleases.isEmpty()) {
            processReadyDeferredCaptures(state);
            refreshBoundaryWork(state);
            return;
        }
        invalidateDrainReachabilityCaches();
        state.flushing = true;
        try {
            processDeferredEntriesFrom(0, 0, 0, 0);
            state.pending.clear();
            state.pendingOwnerReleases.clear();
            state.pendingOwnerScalars.clear();
            state.pendingArgumentFrames.clear();
            state.pendingTransientOwnerKinds.clear();
            state.pendingTiedReleases.clear();
            state.pendingIoReleases.clear();
            state.pendingUntrackedReturnOwnerReleases.clear();
            state.markDepth = 0; // All entries drained; marks are meaningless now
        } finally {
            state.flushing = false;
            invalidateDrainReachabilityCaches();
        }
        processReadyDeferredCaptures(state);
        refreshBoundaryWork(state);
    }

    /**
     * Phase 3 (refcount_alignment_plan.md): Return the current pending-queue
     * size. Used by {@link DestroyDispatch#doCallDestroy} to snapshot the
     * pending list before invoking the Perl DESTROY body, so that the
     * entries added during DESTROY can be drained after it returns without
     * waiting for the outer {@link #flush} to run.
     */
    public static int pendingSize() {
        return state().pending.size();
    }

    /**
     * Phase 3 (refcount_alignment_plan.md): Process pending entries added
     * after a specific checkpoint, regardless of whether an outer
     * {@link #flush} is already running. Used by
     * {@link DestroyDispatch#doCallDestroy} to flush the deferred
     * decrements queued by a DESTROY body (shift @_, $self scope exit)
     * so the post-DESTROY refCount accurately reflects resurrection.
     *
     * @param startIdx the {@link #pendingSize} captured before apply()
     */
    public static void drainPendingSince(int startIdx) {
        drainPendingSince(startIdx, true);
    }

    private static void drainPendingSince(
            int startIdx, boolean clearWeakRefsForLocalBinding) {
        if (!isActive()) return;
        if (startIdx < 0) startIdx = 0;
        LifecycleRuntimeState state = state();
        if (startIdx >= state.pending.size()) return;
        invalidateDrainReachabilityCaches();
        state.reachabilityDrainDepth++;
        // Loop because DESTROY may add further entries
        int i = startIdx;
        try {
            while (i < state.pending.size()) {
                processDeferredBase(state.pending.get(i), clearWeakRefsForLocalBinding,
                        state.pendingOwnerReleases.get(i),
                        state.pendingTransientOwnerKinds.get(i));
                i++;
            }
        } finally {
            state.reachabilityDrainDepth--;
            invalidateDrainReachabilityCaches();
        }
        // Truncate the pending list back to startIdx to mark these entries
        // as processed. Outer flush won't re-process them.
        while (state.pending.size() > startIdx) {
            state.pending.remove(state.pending.size() - 1);
            state.pendingOwnerReleases.remove(state.pendingOwnerReleases.size() - 1);
            state.pendingOwnerScalars.remove(state.pendingOwnerScalars.size() - 1);
            state.pendingArgumentFrames.remove(state.pendingArgumentFrames.size() - 1);
            state.pendingTransientOwnerKinds.remove(state.pendingTransientOwnerKinds.size() - 1);
        }
    }

    /**
     * Drain deferred decrements for marker-owned aliases after a completed
     * {@code goto &sub} handoff. The source call's expression temporaries may
     * be queued below the caller's mortal mark, so flushing that whole scope
     * would also release unrelated deferred metadata (for example Sub::Quote
     * captures) or borrowed caller arguments. Restrict the drain to the
     * marker's ownership-only alias carrier.
     */
    public static void drainPendingTailCallArgs(
            RuntimeArray args, Object argumentFrame, RuntimeBase returnedResult) {
        if (!isActive() || args == null) return;
        java.util.Set<RuntimeScalar> owners =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        owners.addAll(args.elements);

        LifecycleRuntimeState state = state();
        if (state.flushing) return;
        invalidateDrainReachabilityCaches();
        state.flushing = true;
        try {
            for (int i = state.pending.size() - 1; i >= 0; i--) {
                java.lang.ref.WeakReference<RuntimeScalar> ownerRef =
                        state.pendingOwnerScalars.get(i);
                RuntimeScalar owner = ownerRef == null ? null : ownerRef.get();
                RuntimeBase pending = state.pending.get(i);
                boolean directOwner = owner != null && owners.contains(owner);
                boolean frameOwner = argumentFrame != null
                        && state.pendingArgumentFrames.get(i) == argumentFrame;
                boolean abandonedBirthTemporary = owner == null
                        && "bless mortal temporary".equals(state.pendingTransientOwnerKinds.get(i))
                        && args.elements.stream().anyMatch(argument -> argument != null
                                && argument.value == pending);
                // A CORE::GLOBAL wrapper can tail-call the bless override with
                // a newly blessed argument. Keep its mortal decrement queued
                // while either the tail-call argument list or the completed
                // result still carries that reference; the receiving call
                // boundary will retire it after the handoff.
                if (abandonedBirthTemporary && directlyReferences(returnedResult, pending)) {
                    continue;
                }
                if ((!directOwner && !frameOwner && !abandonedBirthTemporary)
                        || (owner == null && !abandonedBirthTemporary)) continue;
                RuntimeBase.PendingOwnerRelease ownerRelease =
                        state.pendingOwnerReleases.get(i);
                String transientOwnerKind = state.pendingTransientOwnerKinds.get(i);
                state.pending.remove(i);
                state.pendingOwnerReleases.remove(i);
                state.pendingOwnerScalars.remove(i);
                state.pendingArgumentFrames.remove(i);
                state.pendingTransientOwnerKinds.remove(i);
                processDeferredBase(pending, true, ownerRelease, transientOwnerKind);
            }
        } finally {
            state.flushing = false;
            invalidateDrainReachabilityCaches();
        }
        refreshBoundaryWork(state);
    }

    /**
     * Push a mark recording the current pending list size.
     * Called before scope-exit cleanup for ordinary scopes, or at block
     * entry when the complete block lifetime must be flushed. popAndFlush()
     * then processes only entries added after that dynamic boundary.
     * Also called at function entry (RuntimeCode.apply) to establish
     * a function-scoped mortal boundary — entries from the caller's
     * scope stay below the mark and are not processed by statement-
     * boundary flushes inside the callee.
     * Analogous to Perl 5's SAVETMPS.
     */
    public static void pushMark() {
        pushMark(state());
    }

    static void pushMark(LifecycleRuntimeState state) {
        if (!state.mortalActive) return;
        state.pushMortalMark(state.pending.size(), state.pendingTiedReleases.size(),
                state.pendingIoReleases.size(),
                state.pendingUntrackedReturnOwnerReleases.size());
    }

    /** Push a block boundary even when its first tracked value is created later. */
    public static void pushScopeMark() {
        LifecycleRuntimeState state = state();
        state.pushMortalMark(state.pending.size(), state.pendingTiedReleases.size(),
                state.pendingIoReleases.size(),
                state.pendingUntrackedReturnOwnerReleases.size());
    }

    /** Current nesting depth for exception boundaries that must unwind marks. */
    public static int markDepth() {
        return state().markDepth;
    }

    /**
     * Discard marks opened after {@code depth} without processing their
     * entries. Exception cleanup releases lexical owners and drains the
     * resulting queue separately.
     */
    public static void restoreMarkDepth(int depth) {
        LifecycleRuntimeState state = state();
        if (depth < 0) {
            throw new IllegalArgumentException("Invalid mortal mark depth: " + depth
                    + " (current " + state.markDepth + ")");
        }
        if (depth > state.markDepth) return;
        state.markDepth = depth;
    }

    /**
     * Pop the most recent mark without flushing.
     * Called at function return to remove the function-scoped boundary.
     * Entries that were above the mark "fall" into the caller's scope
     * and will be processed by the caller's flushAboveMark() at the
     * next statement boundary.
     */
    public static void popMark() {
        popMark(state());
    }

    static void popMark(LifecycleRuntimeState state) {
        if (!state.mortalActive || !state.hasMortalMarks()) return;
        state.popMortalMark();
    }

    /**
     * Flush entries above the top mark without popping it.
     * Used at statement boundaries (FREETMPS equivalent) to process
     * deferred decrements from the current function scope only.
     * Entries below the mark (from caller scopes) are untouched,
     * preventing premature DESTROY of method chain temporaries like
     * {@code Foo->new()->method()} where the bless mortal entry
     * must survive until the caller's statement boundary.
     * <p>
     * If no mark exists (top-level code), behaves like {@link #flush()}.
     */
    public static void flushAboveMark() {
        if (runtimesWithBoundaryWork.get() == 0) return;
        LifecycleRuntimeState state = state();
        if (!state.mortalActive) return;
        if (state.flushing) return;
        if (state.pending.isEmpty() && state.pendingTiedReleases.isEmpty()
                && state.pendingIoReleases.isEmpty()
                && state.pendingUntrackedReturnOwnerReleases.isEmpty()) {
            processReadyDeferredCaptures(state);
            refreshBoundaryWork(state);
            return;
        }
        int mark = state.hasMortalMarks() ? state.marks[state.markDepth - 1] : 0;
        int tiedMark = state.hasMortalMarks()
                ? state.tiedReleaseMarks[state.markDepth - 1] : 0;
        int ioMark = state.hasMortalMarks()
                ? state.ioReleaseMarks[state.markDepth - 1] : 0;
        int untrackedReturnOwnerMark = state.hasMortalMarks()
                ? state.untrackedReturnOwnerMarks[state.markDepth - 1] : 0;
        if (state.pending.size() <= mark && state.pendingTiedReleases.size() <= tiedMark
                && state.pendingIoReleases.size() <= ioMark
                && state.pendingUntrackedReturnOwnerReleases.size() <= untrackedReturnOwnerMark) {
            processReadyDeferredCaptures(state);
            refreshBoundaryWork(state);
            return;
        }
        invalidateDrainReachabilityCaches();
        state.flushing = true;
        try {
            processDeferredEntriesFrom(mark, tiedMark, ioMark, untrackedReturnOwnerMark);
            // Remove only entries above the mark
            while (state.pending.size() > mark) {
                state.pending.removeLast();
                state.pendingOwnerReleases.removeLast();
                state.pendingOwnerScalars.removeLast();
                state.pendingArgumentFrames.removeLast();
                state.pendingTransientOwnerKinds.removeLast();
            }
            while (state.pendingTiedReleases.size() > tiedMark) {
                state.pendingTiedReleases.removeLast();
            }
            while (state.pendingIoReleases.size() > ioMark) {
                state.pendingIoReleases.removeLast();
            }
            while (state.pendingUntrackedReturnOwnerReleases.size() > untrackedReturnOwnerMark) {
                state.pendingUntrackedReturnOwnerReleases.removeLast();
            }
        } finally {
            state.flushing = false;
            invalidateDrainReachabilityCaches();
        }
        processReadyDeferredCaptures(state);
        refreshBoundaryWork(state);
    }

    /**
     * Pop the most recent mark and flush only entries added since it.
     * Called after scope-exit cleanup. Entries before the mark are left
     * for the next full flush() (at apply/setLarge).
     * Analogous to Perl 5's FREETMPS after LEAVE.
     */
    public static void popAndFlush() {
        LifecycleRuntimeState state = state();
        if (!state.mortalActive || !state.hasMortalMarks()) return;
        int markIndex = --state.markDepth;
        int mark = state.marks[markIndex];
        int tiedMark = state.tiedReleaseMarks[markIndex];
        int ioMark = state.ioReleaseMarks[markIndex];
        int untrackedReturnOwnerMark = state.untrackedReturnOwnerMarks[markIndex];
        if (state.pending.size() <= mark && state.pendingTiedReleases.size() <= tiedMark
                && state.pendingIoReleases.size() <= ioMark
                && state.pendingUntrackedReturnOwnerReleases.size() <= untrackedReturnOwnerMark) {
            // Even if no mortal entries to process, check deferred captures
            // that may have become ready after their final closure owner left during
            // scope cleanup.
            processReadyDeferredCaptures(state);
            DestroyDispatch.processRescuedObjects();
            return;
        }
        invalidateDrainReachabilityCaches();
        // Process entries from mark onwards (DESTROY may add new entries)
        processDeferredEntriesFrom(mark, tiedMark, ioMark, untrackedReturnOwnerMark);
        // Remove only the entries we processed (keep entries before mark)
        while (state.pending.size() > mark) {
            state.pending.removeLast();
            state.pendingOwnerReleases.removeLast();
            state.pendingOwnerScalars.removeLast();
            state.pendingArgumentFrames.removeLast();
            state.pendingTransientOwnerKinds.removeLast();
        }
        while (state.pendingTiedReleases.size() > tiedMark) {
            state.pendingTiedReleases.removeLast();
        }
        while (state.pendingIoReleases.size() > ioMark) {
            state.pendingIoReleases.removeLast();
        }
        while (state.pendingUntrackedReturnOwnerReleases.size() > untrackedReturnOwnerMark) {
            state.pendingUntrackedReturnOwnerReleases.removeLast();
        }
        invalidateDrainReachabilityCaches();
        // After processing mortals (which may have triggered releaseCaptures
        // via callDestroy), check if any deferred captures are now ready.
        processReadyDeferredCaptures(state);
        DestroyDispatch.processRescuedObjects();
    }

    /** Pop a mark opened by {@link #pushScopeMark()}, including before tracking activates. */
    public static void popScopeMarkAndFlush() {
        LifecycleRuntimeState state = state();
        if (!state.hasMortalMarks()) return;
        if (!state.mortalActive) {
            state.popMortalMark();
            return;
        }
        popAndFlush();
    }
}
