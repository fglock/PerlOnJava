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
    private static final AtomicInteger runtimesWithBoundaryWork = new AtomicInteger();
    private static final ThreadLocal<ReachabilityQueryStats> activeReachabilityQueryStats =
            new ThreadLocal<>();

    static final class ReachabilityQueryMeasurement implements AutoCloseable {
        private final ReachabilityQueryStats stats;
        private final ReachabilityQueryStats previous;

        private ReachabilityQueryMeasurement(
                ReachabilityQueryStats stats, ReachabilityQueryStats previous) {
            this.stats = stats;
            this.previous = previous;
        }

        ReachabilityQueryStats stats() {
            return stats;
        }

        @Override
        public void close() {
            if (activeReachabilityQueryStats.get() == stats) {
                if (previous == null) activeReachabilityQueryStats.remove();
                else activeReachabilityQueryStats.set(previous);
            }
        }
    }

    static ReachabilityQueryMeasurement measureReachabilityQueries() {
        ReachabilityQueryStats previous = activeReachabilityQueryStats.get();
        ReachabilityQueryStats stats = new ReachabilityQueryStats();
        activeReachabilityQueryStats.set(stats);
        return new ReachabilityQueryMeasurement(stats, previous);
    }

    static ReachabilityQueryStats activeReachabilityQueryStats() {
        return activeReachabilityQueryStats.get();
    }

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

    // Always-on: refCount tracking for birth-tracked objects (anonymous hashes,
    // arrays, closures with captures) requires balanced increment/decrement.
    // The increment side fires unconditionally in setLarge() when count tracking is active,
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
                    || !state.pendingTransientListAssignmentRhsByFrame.isEmpty()
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
            state().temporaryRoots.push(new LifecycleRuntimeState.TemporaryRootLease(root));
        }
    }

    public static void popTemporaryRoot(RuntimeBase root) {
        if (root != null) {
            LifecycleRuntimeState state = state();
            ArrayDeque<LifecycleRuntimeState.TemporaryRootLease> roots = state.temporaryRoots;
            if (!roots.isEmpty() && roots.peek().root() == root) {
                roots.pop().release();
                return;
            }
            var iterator = roots.iterator();
            while (iterator.hasNext()) {
                LifecycleRuntimeState.TemporaryRootLease lease = iterator.next();
                if (lease.root() == root) {
                    iterator.remove();
                    lease.release();
                    return;
                }
            }
        }
    }

    public static boolean hasTemporaryRoots() {
        return !state().temporaryRoots.isEmpty();
    }

    /** Current expression-root depth for an exception boundary. */
    public static int temporaryRootDepth() {
        return state().temporaryRoots.size();
    }

    /** Drop temporary roots added after an exception boundary was entered. */
    public static void restoreTemporaryRootDepth(int depth) {
        if (depth < 0) {
            throw new IllegalArgumentException("Invalid temporary root depth: " + depth);
        }
        ArrayDeque<LifecycleRuntimeState.TemporaryRootLease> roots = state().temporaryRoots;
        while (roots.size() > depth) roots.pop().release();
    }

    /** Diagnostic direct-edge query; lifecycle decisions do not call this method. */
    static boolean temporaryRootDirectlyReferences(RuntimeBase target) {
        if (target == null) return false;
        if (target.hasTemporaryRootOwnerSlotIdentity()) return true;
        for (LifecycleRuntimeState.TemporaryRootLease lease : state().temporaryRoots) {
            if (directlyReferencesTemporaryRootValue(lease.root(), target)) return true;
        }
        return false;
    }

    private static boolean directlyReferencesTemporaryRootValue(
            RuntimeBase value, RuntimeBase target) {
        if (value == target) return true;
        if (value instanceof RuntimeScalar scalar) {
            return !WeakRefRegistry.isweak(scalar)
                    && (scalar.type & RuntimeScalarType.REFERENCE_BIT) != 0
                    && scalar.value == target;
        }
        if (value instanceof RuntimeArray array) {
            if (array.elements == null) return false;
            for (RuntimeScalar element : array.elements) {
                if (element == target || element != null
                        && !WeakRefRegistry.isweak(element)
                        && (element.type & RuntimeScalarType.REFERENCE_BIT) != 0
                        && element.value == target) return true;
            }
            return false;
        }
        if (value instanceof RuntimeHash hash) {
            if (hash.elements == null) return false;
            for (java.util.Map.Entry<String, RuntimeScalar> entry : hash.elements.entrySet()) {
                RuntimeScalar element = entry.getValue();
                if (element == target || element != null
                        && !WeakRefRegistry.isweak(element)
                        && (element.type & RuntimeScalarType.REFERENCE_BIT) != 0
                        && element.value == target) return true;
            }
            return false;
        }
        if (value instanceof RuntimeList list) {
            if (list instanceof RuntimeControlFlowList flow
                    && directlyReferencesTemporaryRootValue(flow.getReturnValue(), target)) {
                return true;
            }
            for (RuntimeBase element : list.elements) {
                if (element == target || element instanceof RuntimeScalar scalar
                        && directlyReferencesTemporaryRootValue(scalar, target)) return true;
            }
        }
        return false;
    }

    public static void clearTemporaryRoots() {
        LifecycleRuntimeState state = state();
        ArrayDeque<LifecycleRuntimeState.TemporaryRootLease> roots = state.temporaryRoots;
        while (!roots.isEmpty()) roots.pop().release();
    }

    /**
     * Retain reference values in an evaluated list source until its foreach
     * finishes. The returned depth is the matching cleanup point.
     */
    public static int pushForeachSourceOwners(RuntimeBase source) {
        LifecycleRuntimeState state = state();
        int depth = state.foreachSourceOwners.size();
        if (!(source instanceof RuntimeList) && !(source instanceof RuntimeArray)) return depth;

        LifecycleRuntimeState.ForeachSourceOwnerLease lease =
                new LifecycleRuntimeState.ForeachSourceOwnerLease();
        try {
            if (source instanceof RuntimeArray array) {
                // A durable array slot already owns its element references.
                // Temporary result arrays need direct item owners because
                // their container slots do not outlive the statement flush
                // that precedes the first foreach iteration.
                if (!array.hasPersistentPerlStrongOwnerIdentity()) {
                    for (RuntimeScalar value : array.elements) lease.retain(value);
                    lease.retainReferent(array);
                }
            } else {
                RuntimeList list = (RuntimeList) source;
                for (java.util.Iterator<RuntimeScalar> values = list.iterator(); values.hasNext();) {
                    lease.retain(values.next());
                }
            }
            state.foreachSourceOwners.push(lease);
            return depth;
        } catch (RuntimeException | Error failure) {
            try {
                lease.release();
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /** Current foreach source owner depth for frame and eval cleanup. */
    public static int foreachSourceOwnerDepth() {
        return state().foreachSourceOwners.size();
    }

    /** Release foreach source owners acquired after {@code depth}. */
    public static void restoreForeachSourceOwnerDepth(int depth) {
        if (depth < 0) {
            throw new IllegalArgumentException("Invalid foreach source owner depth: " + depth);
        }
        LifecycleRuntimeState state = state();
        if (state.foreachSourceOwners.size() < depth) {
            // An awaited callback can resume and complete a foreach whose
            // source lease was part of this caller's saved depth. The resumed
            // continuation already released that lease; this older Java frame
            // must not try to restore a stack depth from before the await.
            return;
        }
        while (state.foreachSourceOwners.size() > depth) {
            state.foreachSourceOwners.pop().release();
        }
    }

    public static boolean isDeferredCapture(RuntimeScalar scalar) {
        if (scalar == null) return false;
        return state().deferredCapturesSet.containsKey(scalar);
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

    /** Whether this referent still has its first-bless mortal owner queued. */
    public static boolean hasPendingBlessTemporaryFor(RuntimeBase referent) {
        if (referent == null) return false;
        LifecycleRuntimeState state = state();
        for (int i = 0; i < state.pending.size(); i++) {
            if (state.pending.get(i) == referent
                    && "bless mortal temporary".equals(
                            state.pendingTransientOwnerKinds.get(i))) {
                return true;
            }
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
        if (state.flushing || state.flushSuppressed) return;

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
        if (base.isPerlDestroyed()) {
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

        PerlOwnerSlot.PendingRelease release =
                owner.releaseCountedOwnerIdentity(referent, releaseSite, true);
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
            if (base.currentlyDestroying || !base.isRefCountTracked()
                    || base.isPerlDestroyed()) {
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
        if (base.refCount <= 0) {
            release.complete();
            return;
        }
        if (base.currentlyDestroying) {
            // This temporary call edge has ended, even though the referent is
            // still inside DESTROY. Retire its count without starting another
            // destruction pass; the active DESTROY boundary decides whether
            // any newly acquired owners resurrected the object.
            base.releaseTrackedOwnerCount(releaseSite);
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
        PerlOwnerSlot.PendingRelease release =
                owner.releaseCountedOwnerIdentity(base, releaseSite, true);
        if (release == null) {
            throw new IllegalStateException(
                    "counted argument owner lost its release token at " + releaseSite);
        }
        if (base.refCount <= 0) {
            release.complete();
            return;
        }
        if (base.currentlyDestroying) {
            // The storage edge is gone. Keep the no-reentrant-DESTROY guard,
            // but do not leave its legacy count behind if this object is
            // rescued before the current destructor returns.
            base.releaseTrackedOwnerCount(releaseSite);
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
        PerlOwnerSlot.PendingRelease release =
                owner.releaseCountedOwnerIdentity(base, releaseSite, true);
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

    /** Retire a one-shot list-assignment RHS at its enclosing Perl mortal boundary. */
    static void deferTransientListAssignmentRhs(RuntimeArray rhs) {
        if (rhs == null) return;
        LifecycleRuntimeState state = state();
        if (!state.mortalActive) {
            releaseTransientListAssignmentRhs(rhs);
            return;
        }
        RuntimeCode executionCode = RuntimeCode.getActiveCodeAt(0);
        int executionDepth = RuntimeCode.currentExecutionDepth();
        boolean topLevel = RuntimeCode.isCurrentExecutionTopLevel();
        int drainAtExecutionDepth = topLevel
                ? executionDepth : Math.max(0, executionDepth - 1);
        RuntimeCode drainAtExecutionCode = drainAtExecutionDepth == executionDepth
                ? executionCode : RuntimeCode.getActiveCodeAt(1);
        LifecycleRuntimeState.TransientListAssignmentFrame frame =
                new LifecycleRuntimeState.TransientListAssignmentFrame(
                        drainAtExecutionCode, drainAtExecutionDepth);
        long drainAtStatementBoundary = state.statementBoundarySequence + 1;
        markBoundaryWork(state);
        LifecycleRuntimeState.TransientListAssignmentQueue queue =
                state.pendingTransientListAssignmentRhsByFrame.get(frame);
        if (queue == null) {
            boolean reusing = state.reusableTransientListAssignmentQueues != null;
            queue = state.obtainTransientListAssignmentQueue();
            if (reusing) LifecycleHotPathDiagnostics.transientRhsFrameQueueReused();
            else LifecycleHotPathDiagnostics.frameQueueAllocated();
            state.pendingTransientListAssignmentRhsByFrame.put(frame, queue);
        }
        queue.append(rhs, ++state.nextTransientListAssignmentSequence,
                drainAtStatementBoundary);
        LifecycleHotPathDiagnostics.transientRhsAppended();
    }

    /** Hand pending returned-list copies to the caller when their frame exits. */
    static void handoffTransientListAssignmentRhs(
            LifecycleRuntimeState state,
            RuntimeCode executionCode, int executionDepth,
            RuntimeCode callerCode, int callerDepth) {
        if (state == null || executionDepth <= 0) return;
        LifecycleRuntimeState.TransientListAssignmentFrame source =
                new LifecycleRuntimeState.TransientListAssignmentFrame(
                        executionCode, executionDepth);
        LifecycleRuntimeState.TransientListAssignmentQueue sourceQueue =
                state.pendingTransientListAssignmentRhsByFrame.remove(source);
        if (sourceQueue == null || sourceQueue.isEmpty()) {
            if (sourceQueue != null) state.recycleTransientListAssignmentQueue(sourceQueue);
            return;
        }

        LifecycleRuntimeState.TransientListAssignmentFrame target =
                new LifecycleRuntimeState.TransientListAssignmentFrame(
                        callerCode, Math.max(0, callerDepth));
        long nextBoundary = state.statementBoundarySequence + 1;
        LifecycleRuntimeState.TransientListAssignmentQueue targetQueue =
                state.pendingTransientListAssignmentRhsByFrame.get(target);
        if (targetQueue == null) {
            sourceQueue.retagAll(nextBoundary);
            state.pendingTransientListAssignmentRhsByFrame.put(target, sourceQueue);
        } else {
            sourceQueue.handoffTo(targetQueue, nextBoundary);
            state.recycleTransientListAssignmentQueue(sourceQueue);
        }
    }

    static void releaseTransientListAssignmentRhs(RuntimeArray rhs) {
        if (rhs == null) return;
        if (rhs.elements != null) {
            for (RuntimeScalar cell : rhs.elements) {
                if (cell != null && cell.getContainerOwner() == rhs) {
                    cell.releaseContainerOwner(rhs);
                }
            }
            rhs.elements.clear();
        }
        rhs.transientListAssignmentRhs = false;
    }
    private static void releaseTransientListAssignmentRhsAtBoundary(
            LifecycleRuntimeState state) {
        RuntimeCode executionCode = RuntimeCode.getActiveCodeAt(0);
        int executionDepth = RuntimeCode.currentExecutionDepth();
        LifecycleRuntimeState.TransientListAssignmentFrame frame =
                new LifecycleRuntimeState.TransientListAssignmentFrame(
                        executionCode, executionDepth);
        LifecycleRuntimeState.TransientListAssignmentQueue queue =
                state.pendingTransientListAssignmentRhsByFrame.get(frame);
        if (queue == null) return;
        queue.drainAtBoundary(state.statementBoundarySequence);
        if (queue.isEmpty()) {
            state.pendingTransientListAssignmentRhsByFrame.remove(frame);
            state.recycleTransientListAssignmentQueue(queue);
        }
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
        if (!state.deferredCaptures.isEmpty()) {
            for (RuntimeScalar scalar : state.deferredCaptures) {
                deferDecrementIfTracked(scalar);
            }
            state.deferredCaptures.clear();
            state.deferredCapturesSet.clear();
            state.deferredCapturesMayBeReady = false;
            flush();
        }

        // Release rescued lifecycle bookkeeping from current Perl owner slots
        // after END. The queue itself is not a Perl owner.
        DestroyDispatch.processRescuedObjects();

    }

    /** Process rescued objects from current Perl owner slots before END callbacks. */
    public static void flushDeferredCapturesBeforeEnd() {
        DestroyDispatch.processRescuedObjects();
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
            } else if (base.isRefCountTracked() && base.refCount == 0
                    && base.clearedOwnedAggregateElement
                    && WeakRefRegistry.hasWeakRefsTo(base)) {
                // A nested-call guard may preserve this object at a selective
                // count of zero. Retire the stale scalar owner identity; scope
                // exit or the final remaining owner slot performs cleanup.
                base.retireZeroCountOwnerIdentity(
                        scalar, "deferDecrementIfTracked zero-count owner");
            }
            // Note: weak-tracked objects are NOT scheduled for destruction
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
        if (state.flushing || state.flushSuppressed || state.deferredReleaseDrainDepth != 0) {
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
        PerlOwnerSlot countedOwner = scalar.countedOwnerSlot();
        if (scalar.refCountOwned
                && ((scalar.type & RuntimeScalarType.REFERENCE_BIT) != 0
                    || scalar.type == RuntimeScalarType.GLOB)
                && scalar.value instanceof RuntimeBase referent
                && countedOwner != null
                && (countedOwner.kind() == PerlOwnerSlot.Kind.ARRAY_ELEMENT
                    || countedOwner.kind() == PerlOwnerSlot.Kind.HASH_ELEMENT)
                && scalar.isCurrentAggregateElementOwner(referent, countedOwner)) {
            // A foreach lexical aliases the aggregate's scalar cell. Scope
            // cleanup must not retire that cell's persistent container edge
            // as though the alias owned a second reference.
            return;
        }
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
        scopeExitCleanupHash(hash, null, false);
    }

    /** Scope-exit cleanup with a return value whose hash references must survive. */
    public static void scopeExitCleanupHash(RuntimeHash hash, RuntimeBase returned) {
        scopeExitCleanupHash(hash, returned, false);
    }

    static void scopeExitCleanupDestroyedHash(RuntimeHash hash) {
        scopeExitCleanupHash(hash, null, true);
    }

    private static void scopeExitCleanupHash(
            RuntimeHash hash, RuntimeBase returned, boolean finalRelease) {
        if (!isActive() || hash == null) return;
        // An aggregate borrowed through @_ belongs to its caller. Its current
        // frame can end while the caller's lexical is still live.
        if (!finalRelease && RuntimeCode.deferCleanupForActiveArgumentAggregate(hash)) return;
        scopeExitCleanupHashBody(hash, returned, finalRelease);
    }

    private static void scopeExitCleanupHashBody(
            RuntimeHash hash, RuntimeBase returned, boolean finalRelease) {
        // Clear localBindingExists: the named variable's scope is ending.
        // This allows subsequent refCount==0 events (from setLargeRefCounted
        // or flush) to correctly trigger callDestroy, since the local
        // variable no longer holds a strong reference.
        hash.localBindingExists = false;
        if (!finalRelease) RuntimeCode.releaseActiveLexicalOwner(hash);
        hash.releaseUnownedClosureCaptures();
        hash.recheckProxyValueOwnersIfUnowned();
        if (!finalRelease && hash.hasClosureCaptureOwner()) {
            hash.scopeExited = true;
            return;
        }
        // A live reference keeps the container and its IO element slots alive.
        if (!finalRelease && (hash.refCount > 0 || hash.hasCurrentPerlOwnerSlotIdentity())) return;
        if (hash instanceof RuntimeStash stash) {
            stash.releaseSavedNamespaceMove();
        }
        for (RuntimeScalar val : hash.elements.values()) {
            RuntimeScalar.releaseIoOwner(val);
            if (val != null) val.releaseContainerOwner(hash);
        }
        if (!RuntimeBase.blessedObjectExists() && !WeakRefRegistry.weakRefsExist()
                && !RuntimeScalar.watcherCleanupNeeded()) return;
        if (RuntimeScalar.watcherCleanupNeeded()) {
            RuntimeScalar.notifyDestroyedWatchersRecursively(hash);
        }
        if (hash.type == RuntimeHash.TIED_HASH && hash.elements instanceof TieHash tieHash) {
            tieHash.releaseTiedObject();
        }
        // Retire the direct storage slots. A final child-container owner
        // triggers cleanup of that child's own outgoing slots.
        for (RuntimeScalar val : hash.elements.values()) {
            if (preserveEscapedScalarCellValueOwner(val)) continue;
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
        arr.recheckProxyValueOwnersIfUnowned();
        if (!finalRelease && arr.hasClosureCaptureOwner()) {
            arr.scopeExited = true;
            return;
        }
        // Alias arrays such as @_ do not own their original element slots.
        if (!finalRelease && (arr.refCount > 0 || arr.hasCurrentPerlOwnerSlotIdentity())) return;
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
        if (!RuntimeBase.blessedObjectExists() && !WeakRefRegistry.weakRefsExist()
                && !RuntimeScalar.watcherCleanupNeeded()) return;
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
                if (preserveEscapedScalarCellValueOwner(elem)) {
                    arr.forgetOwnedAliasElement(elem);
                    continue;
                }
                deferDecrementRecursive(elem);
                arr.forgetOwnedAliasElement(elem);
            }
            return;
        }
        // Array element values are released from the end toward the beginning.
        TieArray tiedStorage = arr.type == RuntimeArray.TIED_ARRAY
                && arr.elements instanceof TieArray tieArray ? tieArray : null;
        int storageSize = tiedStorage == null ? arr.elements.size() : tiedStorage.rawStorageSize();
        for (int index = storageSize - 1; index >= 0; index--) {
            RuntimeScalar elem = tiedStorage == null
                    ? arr.elements.get(index) : tiedStorage.rawStorageGet(index);
            if (preserveEscapedScalarCellValueOwner(elem)) continue;
            deferDecrementRecursive(elem);
        }
    }

    /**
     * Keep a scalar cell's existing value edge after its aggregate storage
     * goes away if a Perl scalar reference still owns that cell. The counted
     * slot moves from aggregate storage to the cell itself; the final cell
     * owner release will then retire the same count through scope cleanup.
     */
    private static boolean preserveEscapedScalarCellValueOwner(RuntimeScalar cell) {
        if (cell == null) return false;
        if (!cell.referencedByScalarReference
                || (!cell.localBindingExists
                    && !cell.hasPendingReturnOwner()
                    && !cell.hasScalarCellStorageOwnerSlotIdentity())) {
            return false;
        }

        PerlOwnerSlot slot = cell.countedOwnerSlot();
        if (!cell.refCountOwned || slot == null || !slot.isActive()
                || slot.countedScalarOwner() != cell || slot.referent() != cell.value) {
            return false;
        }
        if (slot.kind() == PerlOwnerSlot.Kind.ARRAY_ELEMENT
                || slot.kind() == PerlOwnerSlot.Kind.HASH_ELEMENT) {
            slot.reclassifyCountedIdentity(PerlOwnerSlot.Kind.SCALAR_CELL);
            return true;
        }
        return slot.kind() == PerlOwnerSlot.Kind.SCALAR_CELL;
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

    /** Release only this scalar's direct owner edge. Aggregate destruction
     * releases its own outgoing element slots through the storage-owner path. */
    private static void deferDecrementRecursive(RuntimeScalar scalar) {
        if (!isActive() || scalar == null || scalar.hasCodeSlotBinding()
                || (scalar.type & RuntimeScalarType.REFERENCE_BIT) == 0
                || WeakRefRegistry.isweak(scalar)
                || !(scalar.value instanceof RuntimeBase base)) return;
        if (scalar.refCountOwned) {
            if (base.isPerlDestroyed() && base.refCount <= 0) {
                base.retireZeroCountOwnerIdentity(
                        scalar, "MortalList.deferDecrementRecursive destroyed referent");
            } else {
                deferDecrementIfTracked(scalar);
            }
        } else if (base.blessId != 0 && base.isRefCountTracked()
                && base.refCount == 0) {
            LifecycleRuntimeState state = state();
            markBoundaryWork(state);
            RuntimeBase.PendingOwnerRelease ownerRelease = base.queueLifecycleOnlyAtZero(
                    "MortalList.deferDecrementRecursive zero-count temporary");
            queueDeferredBase(state, base, ownerRelease, null, scalar);
        }
    }

    /** Reusable scratch for the cleanup-target pre-scan of nested aggregates. */
    static final class CleanupTargetProbe {
        private ArrayDeque<RuntimeBase> work;
        private Set<RuntimeBase> visited;

        boolean mayContainCleanupTargets(RuntimeBase base) {
            if (!(base instanceof RuntimeArray || base instanceof RuntimeHash)) return false;
            if (work == null) {
                work = new ArrayDeque<>();
                visited = Collections.newSetFromMap(new IdentityHashMap<>());
                LifecycleHotPathDiagnostics.aggregateReleaseProbeWorksetAllocated();
            }
            LifecycleHotPathDiagnostics.aggregateReleaseProbeRootProcessed();
            work.add(base);

            try {
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
            } finally {
                work.clear();
                visited.clear();
            }
        }
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
        } else if (base instanceof RuntimeCode && base.isRefCountTracked()) {
            return true;
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
                    }
                    RuntimeBase.PendingOwnerRelease ownerRelease = base.queueOwnerRelease(
                            scalar, "MortalList.mortalizeForVoidDiscard");
                    LifecycleRuntimeState state = state();
                    markBoundaryWork(state);
                    queueDeferredBase(state, base, ownerRelease, null, scalar);
                } else if (base.isRefCountTracked() && base.refCount == 0
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
        state.deferredReleaseDrainDepth++;
        try {
            int pendingIdx = pendingStartIdx;
            int tiedReleaseIdx = tiedReleaseStartIdx;
            int ioReleaseIdx = ioReleaseStartIdx;
            int untrackedReturnOwnerIdx = untrackedReturnOwnerStartIdx;
            while (pendingIdx < state.pending.size()
                    || tiedReleaseIdx < state.pendingTiedReleases.size()
                    || ioReleaseIdx < state.pendingIoReleases.size()
                    || untrackedReturnOwnerIdx < state.pendingUntrackedReturnOwnerReleases.size()) {
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
            state.deferredReleaseDrainDepth--;
        }
        drainDeferredArgumentOwnerReleases();
    }

    /** Drain the selected pending range depth-first, preserving frame LIFO order. */
    private static void processDeferredOwnerEntriesLifoFrom(
            int startIdx, boolean clearWeakRefsForLocalBinding) {
        LifecycleRuntimeState state = state();
        int resumeDepth = state.pendingReleaseResumeDepth;
        int floor = startIdx;
        int cursor = state.pending.size() - 1;
        int observedSize = state.pending.size();
        try {
            while (true) {
                int liveSize = state.pending.size();
                if (cursor >= liveSize) cursor = liveSize - 1;
                if (floor > liveSize) floor = liveSize;
                if (observedSize > liveSize) observedSize = liveSize;
                if (cursor < floor) {
                    if (state.pendingReleaseResumeDepth > resumeDepth) {
                        int resumeIndex = --state.pendingReleaseResumeDepth;
                        int offset = resumeIndex * 2;
                        cursor = state.pendingReleaseResumeStack[offset];
                        floor = state.pendingReleaseResumeStack[offset + 1];
                        continue;
                    }
                    int currentSize = state.pending.size();
                    if (currentSize > observedSize) {
                        floor = observedSize;
                        cursor = currentSize - 1;
                        observedSize = currentSize;
                        continue;
                    }
                    observedSize = currentSize;
                    break;
                }

                int currentIndex = cursor--;
                int previousSize = state.pending.size();
                RuntimeBase.PendingOwnerRelease ownerRelease =
                        state.pendingOwnerReleases.get(currentIndex);
                if (ownerRelease != null && ownerRelease.completed.get()) continue;
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
                    processDeferredBase(base, clearWeakRefsForLocalBinding,
                            ownerRelease, transientOwnerKind);
                }

                int currentSize = state.pending.size();
                if (currentSize > previousSize) {
                    state.pushPendingReleaseResume(cursor, floor);
                    floor = previousSize;
                    cursor = currentSize - 1;
                    observedSize = currentSize;
                } else if (currentSize < observedSize) {
                    // A nested exact drain may have truncated its own tail.
                    // Allow later appends to reuse those queue positions.
                    observedSize = currentSize;
                }
            }
        } finally {
            state.pendingReleaseResumeDepth = resumeDepth;
        }
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
        boolean prev = state.flushSuppressed;
        state.flushSuppressed = suppress;
        return prev;
    }

    /** Return whether deferred-owner flushing is currently suppressed. */
    public static boolean isFlushSuppressed() {
        return state().flushSuppressed;
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
        if (DestroyDispatch.hasLivePerlOwnerIdentity(base)) return;
        // A deferred count release has already retired its live owner slot,
        // but the compatibility count is not decremented until the mortal
        // queue drains. Let that exact release decide finalization instead of
        // racing it with scope-exit cleanup.
        if (base.hasPendingOwnerRelease()) return;
        DestroyDispatch.callDestroy(base);
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
        // A lifecycle-only-at-zero token requests cleanup only if the referent
        // is still unowned when the queue drains. If a real owner has acquired
        // it since queueing, completing that token must leave the new count
        // untouched.
        boolean finalRelease = false;
        if (base.refCount > 0) {
            if (ownerRelease == null || !ownerRelease.isLifecycleOnlyAtZero()) {
                finalRelease = base.releaseTrackedOwnerCount(
                        "MortalList.flush (deferred decrement)");
            }
        } else if (base.isRefCountTracked() && base.refCount == 0
                && ownerRelease != null) {
            // The queue token may represent a real edge released after a
            // transient undercount, or an explicit zero-count temporary
            // finalization request. Process that lifecycle boundary directly;
            // do not manufacture a positive refCount just to enter this path.
            finalRelease = true;
        }
        // Counted slots are detached before their queued decrement runs. Their
        // slot-release callback therefore still sees the old count as a live
        // parent owner. Recheck proxy-backed scalar values after the count
        // transition, when the parent may have lost its final Perl owner.
        if (base instanceof RuntimeArray || base instanceof RuntimeHash) {
            base.recheckProxyValueOwnersIfUnowned();
        }
        if (finalRelease) {
            boolean aggregateReferent = base instanceof RuntimeArray || base instanceof RuntimeHash;
            // Aggregate owner slots have their own deferred-release rule below.
            // Refresh scalar-cell owners here for other referents before the
            // final counted release checks whether any owner slot remains.
            boolean hasUntrackedReferenceOwner = !aggregateReferent
                    && base.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE) != 0
                    && base.hasUntrackedReferenceOwnerSlotIdentity();
            // A closure pad can outlive its CV's final Perl owner until the
            // captured referent next reaches a lifecycle boundary. Retire only
            // pads whose CODE owner ledger is already empty before deciding
            // that the capture itself keeps this referent alive.
            if (base.hasSemanticCaptureOwner()) {
                base.releaseUnownedClosureCaptures();
            }
            if (base instanceof RuntimeCode code && code.hasActiveCodeOwner()) {
                code.deferFinalReleaseToActiveCode();
            } else if (base.hasSemanticCaptureOwner()) {
                // Both native and legacy closure-pad slots identify the Perl
                // edge that keeps this referent alive. The direct PerlIO::via
                // hold is runtime scaffolding around PUSHED; when POPPED drops
                // it, retain the tracked zero count and let the closure slot
                // carry the remaining lifecycle. Ordinary scalar weakening
                // continues to use weak-tracked state while a semantic capture is
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
            } else if (base.hasCurrentUncountedPerlOwnerSlotIdentity()) {
                // A current lexical, package, alias, or resource slot proves
                // that the referent still has a Perl owner.
                // Carry the lifecycle obligation on the exact identity instead
                // of searching for another owner.
                base.deferFinalReleaseToUncountedOwnerSlots();
            } else if (isFinalCountedReferentRelease(base, ownerRelease)) {
                // This decrement retired an identified scalar/element edge.
                // Every other migrated edge is already absent from the ledger;
                // there is no remaining Perl owner to preserve.
                DestroyDispatch.callDestroyFromCountedOwner(base);
            } else if (base instanceof RuntimeScalarReadOnly
                    && RuntimeCode.isInstalledPadConstant(base)) {
                // A readonly scalar referenced through \("literal") has an
                // explicit owner slot from each live CV optree that contains it.
                // Defer lifecycle cleanup to those slots instead of fabricating
                // a legacy count that has no corresponding Perl reference.
                base.deferFinalReleaseToUncountedOwnerSlots();
            } else if (base.hasNonPackageAggregateUncountedOwnerSlotIdentity()
                        || (aggregateReferent
                            ? (base.blessId != 0 && !hasWeakRefs
                                && base.hasUntrackedReferenceOwnerSlotIdentity())
                            : hasUntrackedReferenceOwner)) {
                // A surviving non-package aggregate slot, or an untracked
                // package owner of a blessed aggregate without direct weak
                // observers, defers cleanup until its release.
                base.deferFinalReleaseToUncountedOwnerSlots();
            } else {
                // Call depth is not ownership. If every counted and direct
                // owner check above is empty, a weak referent is final here;
                // deferring cleanup would strand the weak scalars.
                DestroyDispatch.callDestroy(base);
            }
        }
        // A nested scope may defer destruction while another active owner
        // remains. Recheck the ledger after the final counted release so the
        // last trusted slot completes cleanup here.
        if (base.isRefCountTracked() && base.refCount == 0
                && isFinalCountedReferentRelease(base, ownerRelease)
                && !base.localBindingExists
                && !base.hasClosureCaptureOwner()
                && !base.hasPendingReturnOwner()
                && !GlobalVariable.isPackageRootedAggregate(base)
                && !(base instanceof RuntimeCode code
                    && (code.hasActiveCodeOwner()
                        || code.hasStashCodeSlotOwner()))) {
            DestroyDispatch.callDestroyFromCountedOwner(base);
        }
        if (base instanceof RuntimeCode code) code.finishActiveCodeOwnerRelease();
        if (base instanceof RuntimeScalar scalar) {
            PerlOwnerSlot.finishDeferredScalarCellRelease(scalar);
        }
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

    public static void flush() {
        LifecycleRuntimeState state = state();
        if (!state.mortalActive) return;
        if (state.flushing || state.flushSuppressed) return;
        if (state.pending.isEmpty() && state.pendingTiedReleases.isEmpty()
                && state.pendingIoReleases.isEmpty()
                && state.pendingUntrackedReturnOwnerReleases.isEmpty()
                && state.pendingTransientListAssignmentRhsByFrame.isEmpty()) {
            processReadyDeferredCaptures(state);
            refreshBoundaryWork(state);
            return;
        }
        state.flushing = true;
        try {
            releaseTransientListAssignmentRhsAtBoundary(state);
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
        state.deferredReleaseDrainDepth++;
        int pendingIdx = startIdx;
        try {
            // Exact operation-boundary releases keep queue order. Frame marks
            // use depth-first LIFO draining; this path can be nested inside a
            // destructor's count probe and must preserve its established order.
            while (pendingIdx < state.pending.size()) {
                int currentIndex = pendingIdx++;
                processDeferredBase(state.pending.get(currentIndex),
                        clearWeakRefsForLocalBinding,
                        state.pendingOwnerReleases.get(currentIndex),
                        state.pendingTransientOwnerKinds.get(currentIndex));
            }
        } finally {
            state.deferredReleaseDrainDepth--;
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
        if (state.flushing || state.flushSuppressed) return;
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
                if (abandonedBirthTemporary
                        && (pending.hasCurrentPerlOwnerSlotIdentity()
                            || pending.hasPendingReturnOwner())) {
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
        if (state.flushing || state.flushSuppressed) return;
        if (state.pending.isEmpty() && state.pendingTiedReleases.isEmpty()
                && state.pendingIoReleases.isEmpty()
                && state.pendingUntrackedReturnOwnerReleases.isEmpty()
                && state.pendingTransientListAssignmentRhsByFrame.isEmpty()) {
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
                && state.pendingUntrackedReturnOwnerReleases.size() <= untrackedReturnOwnerMark
                && state.pendingTransientListAssignmentRhsByFrame.isEmpty()) {
            processReadyDeferredCaptures(state);
            refreshBoundaryWork(state);
            return;
        }
        state.flushing = true;
        try {
            releaseTransientListAssignmentRhsAtBoundary(state);
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
        }
        processReadyDeferredCaptures(state);
        refreshBoundaryWork(state);
    }

    /** Advance Perl statement-lifetime temporaries at a compiled statement end. */
    public static void flushStatementBoundary() {
        if (runtimesWithBoundaryWork.get() == 0) return;
        LifecycleRuntimeState state = state();
        if (!state.mortalActive) return;
        state.statementBoundarySequence++;
        flushAboveMark();
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
        // A surrounding deferred-entry drain owns the queue cursor and will
        // process entries appended by this nested cleanup after the callback
        // returns. Flush suppression is separate: a scoped mark can still
        // safely drain only the entries created after that mark.
        if (state.deferredReleaseDrainDepth > 0) {
            state.popMortalMark();
            return;
        }
        popAndFlush();
    }
}
