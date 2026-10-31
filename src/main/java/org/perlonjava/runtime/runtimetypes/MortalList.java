package org.perlonjava.runtime.runtimetypes;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
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

    public static void retainSuspendedRoot(RuntimeBase root) {
        if (root != null) {
            state().suspendedRoots.merge(root, 1, Integer::sum);
            invalidateAllRootSnapshots();
        }
    }

    public static void releaseSuspendedRoot(RuntimeBase root) {
        if (root == null) return;
        LifecycleRuntimeState state = state();
        Integer count = state.suspendedRoots.get(root);
        if (count == null) return;
        if (count <= 1) state.suspendedRoots.remove(root);
        else state.suspendedRoots.put(root, count - 1);
        invalidateAllRootSnapshots();
    }

    public static java.util.List<RuntimeBase> snapshotSuspendedRoots() {
        return new java.util.ArrayList<>(state().suspendedRoots.keySet());
    }

    public static void clearSuspendedRoots() {
        state().suspendedRoots.clear();
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
     * Schedule a deferred refCount decrement for a tracked referent.
     * Called from delete() when removing a tracked blessed reference
     * from a container.
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

    /** Queue the deferred release together with the array scalar that owned it. */
    public static void deferDecrementFromArraySlot(
            RuntimeBase base, RuntimeScalar ownerScalar, String releaseSite) {
        if (base.refCountTrace) {
            base.traceRefCount(0, releaseSite + " (array-slot decrement queued)");
        }
        LifecycleRuntimeState state = state();
        markBoundaryWork(state);
        RuntimeBase.PendingOwnerRelease ownerRelease =
                base.queueOwnerRelease(ownerScalar, releaseSite);
        base.releaseActiveOwner(ownerScalar);
        queueDeferredBase(state, base, ownerRelease, null, ownerScalar);
    }

    /** Queue a closure-capture bridge decrement with owner-slot provenance. */
    static void deferOwnerSlotDecrement(RuntimeBase base, String transientOwnerKind,
                                        PerlOwnerSlot.PendingRelease slotRelease) {
        if (base.refCountTrace) {
            base.traceRefCount(0, "MortalList.deferOwnerSlotDecrement (queued)");
        }
        LifecycleRuntimeState state = state();
        markBoundaryWork(state);
        RuntimeBase.PendingOwnerRelease ownerRelease = base.queueOwnerSlotRelease(slotRelease);
        queueDeferredBase(state, base, ownerRelease, transientOwnerKind);
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
        if (!state.mortalActive || scalar == null || !scalar.ioOwner) return;
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
     * could not be decremented because {@code captureCount > 0}.
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
        if (scalar.captureCount == 0 && scalar.scopeExited) {
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
     * Process deferred captures whose captureCount has already reached 0.
     * Called from {@link #popAndFlush()} at block scope exit, AFTER the
     * mortal list has been processed (which may trigger callDestroy →
     * releaseCaptures → captureCount decrements on captured variables).
     * <p>
     * This bridges the gap between deferred capture registration (at scope
     * exit when captureCount > 0) and flushDeferredCaptures (after the main
     * script returns). Without this, objects whose captures are fully
     * released at block exit still appear "alive" to leak tracers like
     * DBIC's assert_empty_weakregistry, which runs inside the main script.
     * <p>
     * Only processes entries where captureCount == 0 AND scopeExited == true,
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
            if (scalar.captureCount == 0 && scalar.scopeExited) {
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
                scalar.refCountOwned = false;
                if (base.refCountTrace) {
                    base.traceRefCount(0, "MortalList.deferDecrementIfTracked (queued, scalar.refCountOwned->false)");
                }
                LifecycleRuntimeState state = state();
                markBoundaryWork(state);
                RuntimeBase.PendingOwnerRelease ownerRelease = base.queueOwnerRelease(
                        scalar, "MortalList.deferDecrementIfTracked");
                base.releaseActiveOwner(scalar);
                queueDeferredBase(state, base, ownerRelease, null, scalar);
            } else if (base.refCount == 0
                    && base.clearedOwnedAggregateElement
                    && WeakRefRegistry.hasWeakRefsTo(base)) {
                // A nested-call reachability guard may have preserved this object
                // at a selective count of zero. The scalar still represents a live
                // Perl owner even though there is no count left to decrement. Drop
                // that stale ownership marker and ask the reachability walker to
                // decide at the next safe (non-nested) boundary whether the weak
                // observers should be cleared.
                scalar.refCountOwned = false;
                if (base.refCountTrace) {
                    base.releaseOwner(scalar, "deferDecrementIfTracked zero-count owner");
                }
                base.releaseActiveOwner(scalar);
                requestCorrectiveWeakSweep();
            }
            // Note: WEAKLY_TRACKED (-2) objects are NOT scheduled for destruction
            // on scope exit. We can't count strong refs for non-DESTROY objects
            // (refs created before weaken() weren't tracked), so scope exit of
            // ONE reference doesn't mean there are no other strong refs (e.g.,
            // symbol table entries). Weak refs for these objects are cleared only
            // via explicit undefine() of the referent's last known reference.
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
     * by a closure ({@code captureCount > 0}).
     * Used by the explicit {@code return} bytecode path which bypasses
     * {@link RuntimeScalar#scopeExitCleanup}.
     */
    public static void deferDecrementIfNotCaptured(RuntimeScalar scalar) {
        if (!isActive() || scalar == null) return;
        if (scalar.captureCount > 0
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
            if (scalar != null && scalar.untrackedReferenceOwnerSlot() != null) {
                scalar.refreshUntrackedReferenceOwner();
            }
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
        boolean hadLocalBinding = hash.localBindingExists;
        hash.localBindingExists = false;
        hash.releaseUnownedClosureCaptures();
        if (!finalRelease && hash.captureCount > 0) {
            hash.scopeExited = true;
            return;
        }
        // A live reference keeps the container and its IO element slots alive.
        if (!finalRelease && (hash.refCount > 0 || hash.hasOwnerSlots()
                || temporaryRootDirectlyReferences(hash))) return;
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
        // Refcount drift can miss an escaped hash reference. DBIC's
        // source_registrations hash is assigned into the live schema while the
        // original hash later exits; walking it here would destroy ResultSources
        // still reachable through the schema.
        if (!finalRelease && hadLocalBinding
                && hash.refCount >= 0
                && hash.hadCountedReference
                && isReachableFromExternalRootCached(hash)) return;
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
        arr.releaseUnownedClosureCaptures();
        if (!finalRelease && arr.captureCount > 0) {
            arr.scopeExited = true;
            return;
        }
        // Alias arrays such as @_ do not own their original element slots.
        if (!finalRelease && (arr.refCount > 0 || arr.hasOwnerSlots()
                || temporaryRootDirectlyReferences(arr) || directlyReferences(returned, arr))) return;
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

        codeRef.refCountOwned = false;
        if (code.refCountTrace) {
            code.traceRefCount(-1, "MortalList.releaseTailCallCodeRef");
            code.releaseOwner(codeRef, "releaseTailCallCodeRef");
        }
        code.releaseActiveOwner(codeRef);

        if (--code.refCount > 0) return;

        if (code.stashRefCount > 0) {
            code.refCount = 0;
            return;
        }

        if (code.localBindingExists) {
            code.refCount = 1;
            return;
        }

        code.refCount = Integer.MIN_VALUE;
        DestroyDispatch.callDestroy(code);
    }

    private static void releaseTailCallArgElement(RuntimeScalar scalar) {
        if (scalar == null
                || !scalar.refCountOwned
                || (scalar.type & RuntimeScalarType.REFERENCE_BIT) == 0
                || !(scalar.value instanceof RuntimeBase base)
                || base.refCount <= 0) {
            return;
        }

        scalar.refCountOwned = false;
        if (base.refCountTrace) {
            base.traceRefCount(-1, "MortalList.releaseTailCallArgElement");
            base.releaseOwner(scalar, "releaseTailCallArgElement");
        }
        base.releaseActiveOwner(scalar);

        if (--base.refCount > 0) return;

        // Counted owner slots are authoritative even when the compatibility
        // refcount transiently undercounts them during a tail-call handoff.
        // Restore from those identities before allowing final release.
        if (base.restoreCountedOwnerSlotRefCount()) return;

        if (base.localBindingExists) {
            base.refCount = 1;
            return;
        }
        if (base.hasUncountedPerlOwnerSlotIdentity()) {
            base.deferFinalReleaseToUncountedOwnerSlots();
            return;
        }
        base.refCount = Integer.MIN_VALUE;
        DestroyDispatch.callDestroy(base);
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
                    s.refCountOwned = false;
                    if (base.refCountTrace) {
                        base.traceRefCount(0, "MortalList.deferDecrementRecursive (blessed, queued)");
                        base.releaseOwner(s, "deferDecrementRecursive blessed");
                    }
                    RuntimeBase.PendingOwnerRelease ownerRelease = base.queueOwnerRelease(
                            s, "MortalList.deferDecrementRecursive blessed");
                    base.releaseActiveOwner(s);
                    // refCount is the authoritative ownership count here.
                    // activeOwners is diagnostic and can retain a stale
                    // call-frame alias until the enclosing sub returns.
                    releasingLastOwner = base.refCount == 1;
                    LifecycleRuntimeState state = state();
                    markBoundaryWork(state);
                    queueDeferredBase(state, base, ownerRelease, null, s);
                } else if (base.refCount == 0) {
                    if (base.refCountTrace) {
                        base.traceRefCount(+1, "MortalList.deferDecrementRecursive (blessed never-stored bump+queue)");
                    }
                    base.refCount = 1;
                    LifecycleRuntimeState state = state();
                    markBoundaryWork(state);
                    queueDeferredBase(state, base, null, null, s);
                    // A zero-count blessed container returned from a helper
                    // has no counted owner to release, but its fields still
                    // disappear when this temporary dies.
                    releasingLastOwner = true;
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
                    s.refCountOwned = false;
                    if (base.refCountTrace) {
                        base.traceRefCount(0, "MortalList.deferDecrementRecursive (unblessed container, queued)");
                    }
                    RuntimeBase.PendingOwnerRelease ownerRelease = base.queueOwnerRelease(
                            s, "MortalList.deferDecrementRecursive unblessed");
                    base.releaseActiveOwner(s);
                    LifecycleRuntimeState state = state();
                    markBoundaryWork(state);
                    queueDeferredBase(state, base, ownerRelease, null, s);
                    if (!WeakRefRegistry.weakRefsExist() && base.refCount > 1) {
                        continue;
                    }
                    if (!hasCleanupTargets
                            || base.activeOwnerCount() > 0
                            || (base.refCount > 1
                            && !hasDirectWeakElementRefs
                            && isReachableFromExternalRootCached(base))) {
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
                        || base.activeOwnerCount() > 0
                        || (!hasDirectWeakElementRefs
                        && isReachableFromExternalRootCached(base)))) {
                    // This scalar is a non-owning copy of a still-live container.
                    // Cleaning it up must not walk into the shared container and
                    // release the original owner's children.
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
                    scalar.refCountOwned = false;
                    if (base.refCountTrace) {
                        base.traceRefCount(0, "MortalList.mortalizeForVoidDiscard (queued owned container discard)");
                        base.releaseOwner(scalar, "mortalizeForVoidDiscard container");
                    }
                    RuntimeBase.PendingOwnerRelease ownerRelease = base.queueOwnerRelease(
                            scalar, "MortalList.mortalizeForVoidDiscard");
                    base.releaseActiveOwner(scalar);
                    LifecycleRuntimeState state = state();
                    markBoundaryWork(state);
                    queueDeferredBase(state, base, ownerRelease, null, scalar);
                } else if (base.refCount == 0
                        && (base.blessId != 0
                        || base instanceof RuntimeHash
                        || base instanceof RuntimeArray
                        || base instanceof RuntimeCode)) {
                    if (base.refCountTrace) {
                        base.traceRefCount(+1, "MortalList.mortalizeForVoidDiscard (refCount=1 bump+queue)");
                    }
                    base.refCount = 1;
                    LifecycleRuntimeState state = state();
                    markBoundaryWork(state);
                    queueDeferredBase(state, base, null);
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
                    // Releasing a tie handler changes the graph represented by
                    // the per-drain tied-reachability snapshot.
                    state.flushTiedReachableCache = null;
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
                || base.refCount != 0
                || !base.clearedOwnedAggregateElement
                || !WeakRefRegistry.hasWeakRefsTo(base)) {
            return;
        }
        // Scope cleanup retires the exact lexical owner before reaching this
        // point. Package, container, and capture edges have their own owner
        // slots; temporary roots are checked separately because they are
        // evaluation scaffolding rather than Perl ownership. Prefer these
        // identities, retaining the cached graph proof only for ownerless
        // compatibility cases that have not yet migrated.
        if (base.hasPerlStrongOwner() || temporaryRootDirectlyReferences(base)) {
            return;
        }
        if (isReachableFromExternalRootCached(base)) return;
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
        state.flushTiedReachableCache = null;
        state.scalarRootSnapshot = null;
        state.fullRootSnapshot = null;
        if (!state.flushing && state.reachabilityDrainDepth == 0) {
            state.unblessedAggregateDrainRoots.clear();
            state.unblessedAggregateDrainSnapshotIncomplete = false;
        }
    }

    private static boolean isReachableThroughTiedHashCached(RuntimeBase base) {
        LifecycleRuntimeState state = state();
        if (state.flushTiedReachableCache == null) {
            state.flushTiedReachableCache =
                    ReachabilityWalker.reachableThroughTiedHashes();
        }
        return state.flushTiedReachableCache.contains(base);
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
        base.completeQueuedOwnerRelease(ownerRelease, "MortalList.processDeferredBase");
        base.releaseTransientTraceOwner(transientOwnerKind,
                "MortalList.processDeferredBase");
        boolean hasWeakRefs = WeakRefRegistry.hasWeakRefsTo(base);
        if (base.refCount > 0) {
            base.traceRefCount(-1, "MortalList.flush (deferred decrement)");
        }
        if (base.refCount > 0 && --base.refCount == 0) {
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
                // Captured aggregate pads use native owner slots. Keep the
                // legacy count at zero while one is active; its final release
                // will perform the lifecycle transition. Scalar captures still
                // have a legacy bridge count and retain the old compatibility
                // sentinel behavior.
                if (base.nativeCaptureOwnerCount() == 0) base.refCount = 1;
            } else if (base.localBindingExists) {
                if (base instanceof RuntimeScalar scalar
                        && scalar.referencedByScalarReference
                        && scalar.captureCount == 0
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
            } else if (isFinalCountedReferentRelease(base, ownerRelease)) {
                // This decrement retired an identified scalar/element edge.
                // Every other migrated edge is already absent from the ledger;
                // a cached root snapshot must not invent a replacement owner.
                base.refCount = Integer.MIN_VALUE;
                DestroyDispatch.callDestroyFromCountedOwner(base);
            } else if (base.blessId != 0 && base.restoreCountedOwnerSlotRefCount()) {
                // For blessed referents, remaining counted slots prove that
                // the transient zero was an undercount. Unblessed referents
                // use stricter current-storage checks below.
            } else if (base.restoreVerifiedCurrentCountedOwnerSlotRefCount()) {
                // Verified current scalar cells and array/hash elements jointly
                // restore the surviving owner count without a root walk.
            } else if (base instanceof RuntimeCode code
                    && hasWeakRefs
                    && (RuntimeCode.isActiveCode(code)
                    || (!code.hadStashRef && RuntimeCode.argsStackDepth() > 1))) {
                // Sub::Defer stores a weak self-reference in the deferred
                // CODE's captured info array. Selective refcounts can reach
                // zero while the wrapper is still executing or nested
                // generation is wiring Moo metadata. Clearing then makes
                // nested dispatch call undefer_sub(undef).
                base.refCount = 1;
            } else if (base instanceof RuntimeScalarReadOnly
                    && RuntimeCode.isInstalledPadConstant(base)) {
                // A readonly scalar referenced through \("literal") has an
                // explicit owner slot from each live CV optree that contains it.
                // Defer lifecycle cleanup to those slots instead of fabricating
                // a legacy count that has no corresponding Perl reference.
                base.deferFinalReleaseToUncountedOwnerSlots();
            } else if (base.hasNonPackageAggregateUncountedOwnerSlotIdentity()
                    || (aggregateReferent
                        ? base.hasUntrackedReferenceOwnerSlotIdentity(
                                RuntimeScalarType.REFERENCE)
                        : hasUntrackedReferenceOwner)) {
                // Counted owner recovery is complete above. A surviving
                // aggregate or untracked scalar slot is enough to defer cleanup
                // until its release, even when no weak observer currently exists.
                base.deferFinalReleaseToUncountedOwnerSlots();
            } else if (hasWeakRefs && restoreReachableOwnerCount(base)) {
                // A counted owner that is still reachable through a real
                // Perl root is authoritative, including for classes with
                // DESTROY. Unmigrated slots still need this compatibility
                // fallback until their owner identities are complete.
            } else if (base.blessId == 0
                    && (base instanceof RuntimeArray || base instanceof RuntimeHash)
                    && isUnblessedAggregateReachableDuringDrain(base)) {
                // Unblessed aggregates can reach zero while a parser is
                // assembling a tree, before it installs the first weak parent
                // edge. A temporary reference can make the selective count
                // reach zero even though an enclosing live aggregate already
                // owns the node. Consult the shared drain snapshot before a
                // later weaken() sees a destroyed referent. The next cleanup
                // boundary can still reclaim a tree after its Perl root goes
                // away.
                base.refCount = 1;
            } else if (hasWeakRefs
                    && base.possiblyStoredInTiedHandler
                    && isReachableThroughTiedHashCached(base)) {
                // The active-owner set starts recording at the first weaken,
                // so a handler slot populated just before weaken can be absent
                // from it. Confirm the missing ownership through the narrower
                // tied-handler root walk before protecting DESTROY objects.
                base.refCount = 1;
            } else if (base.blessId == 0
                    && hasWeakRefs
                    && ReachabilityWalker.hasStrongCycle(base)) {
                // Perl keeps unblessed strong cycles alive even without an
                // external root. Decide this before deferring to a registry
                // sweep: the cycle itself proves the target is still alive.
                base.refCount = 1;
            } else if (base.blessId == 0
                    && hasWeakRefs
                    && (ReachabilityWalker.isReachableFromLiveCodeCaptures(base)
                    || ReachabilityWalker.isReachableFromGlobalCodeCaptures(base))) {
                // A positive capture query is conclusive for this unblessed
                // target. Keep it alive without requesting a registry-wide
                // sweep; unknown targets still use the complete-boundary path.
                base.refCount = 1;
            } else if (base.blessId == 0
                    && hasWeakRefs
                    && RuntimeCode.argsStackDepth() > 1) {
                // A returned aggregate can be reparented into another aggregate
                // while a nested call still owns the only compiler-visible
                // temporary. Mojo::DOM58 does this when it parses replacement
                // markup, weakens child-to-parent links, and splices the parsed
                // nodes into the live DOM. Selective refcounts can hit zero as
                // the temporary parser tree unwinds even though the outer call
                // already holds the parent strongly. Defer the unblessed weak
                // target just as we do for ordinary blessed objects below; a
                // later top-level sweep can still clear a genuinely dead tree.
                base.refCount = 1;
            } else if (base.blessId != 0
                    && hasWeakRefs
                    && (isReachableFromExternalRootCached(base)
                    || isReachableFromFullRootSnapshot(base))) {
                // A nested request can temporarily consume the selective owner
                // count of an object that is still retained below a package
                // root. Mojolicious::Lite keeps its route tree in a package
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
            } else if (base.blessId != 0
                    && hasWeakRefs
                    && !blessedClassHasDestroy(base)
                    && (isReachableFromExternalRootCached(base)
                    || isReachableFromFullRootSnapshot(base))) {
                // A weakened probe copy can make the selective count reach
                // zero while an ordinary blessed object is still held by a
                // live lexical. Test::Refcount exercises this shape; clearing
                // weak refs here drops callback invocants that should remain
                // valid. Real roots and explicit method-invocant holds still
                // protect live objects. Classes with DESTROY keep the stricter
                // path.
                base.refCount = 1;
            } else if (base.blessId != 0
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
                && base.captureCount == 0
                && !base.hasPendingReturnOwner()
                && !GlobalVariable.isPackageRootedAggregate(base)
                && !(base instanceof RuntimeCode code
                    && (code.hasActiveCodeOwner()
                        || code.stashRefCount > 0
                        || GlobalVariable.installedGlobalCodeRefsView().contains(code)))) {
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
                    && !(scalar instanceof RuntimeScalarReadOnly) && scalar.captureCount == 0)
                || (base instanceof RuntimeCode code
                    && (code.hasCountedCodeSlots || (!code.hadStashRef && code.stashRefCount <= 0))
                    && code.activeEndBlockExecutions == 0
                    && !SpecialBlock.hasPendingEndBlock(code));
        return supportedReferent && !base.hasOwnerSlots();
    }

    private static boolean isFinalCountedReferentRelease(
            RuntimeBase base, RuntimeBase.PendingOwnerRelease release) {
        if (!canTrustFinalCountedRelease(base)
                || release == null || release.ownerSlotRelease == null) return false;
        return switch (release.ownerSlotRelease.kind()) {
            case SCALAR_CELL, CODE_SLOT, ARRAY_ELEMENT, HASH_ELEMENT, ARGUMENT_VALUE, RETURN_VALUE,
                    TIE_HANDLER -> true;
            default -> false;
        };
    }

    private static boolean restoreReachableOwnerCount(RuntimeBase base) {
        int ownerCount = base.reachableOwnerCount();
        if (ownerCount == 0) return false;
        base.refCount = ownerCount;
        return true;
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
        boolean topLevel = !state.hasMortalMarks();
        if (state.pending.isEmpty() && state.pendingTiedReleases.isEmpty()
                && state.pendingIoReleases.isEmpty()
                && state.pendingUntrackedReturnOwnerReleases.isEmpty()) {
            processReadyDeferredCaptures(state);
            refreshBoundaryWork(state);
            return;
        }
        invalidateDrainReachabilityCaches();
        state.flushing = true;
        if (topLevel) state.topLevelFlushDepth++;
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
            if (topLevel) state.topLevelFlushDepth--;
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
                processDeferredBase(state.pending.get(i), true,
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
                // a newly blessed argument and return that same reference.
                // Its bless temporary is the return value's only owner until
                // the caller materializes the result, so keep the pending
                // decrement queued for the caller's normal scope cleanup.
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
     * Called before scope-exit cleanup so that popAndFlush() only
     * processes entries added by the cleanup (not earlier entries
     * from outer scopes or prior operations).
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
        boolean topLevel = !state.hasMortalMarks();
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
        if (topLevel) state.topLevelFlushDepth++;
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
            if (topLevel) state.topLevelFlushDepth--;
            state.flushing = false;
            invalidateDrainReachabilityCaches();
        }
        processReadyDeferredCaptures(state);
        refreshBoundaryWork(state);
    }

    /**
     * Whether cleanup is currently running at a top-level Perl statement
     * boundary.  A top-level temporary can be finalized immediately before
     * {@code ${^GLOBAL_PHASE}} changes to {@code DESTRUCT}; Perl still treats
     * a destructor resurrection there as global-destruction resurrection.
     */
    public static boolean isDrainingTopLevelTemporary() {
        return state().topLevelFlushDepth > 0;
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
            // that may have become ready (captureCount reached 0) during
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
}
