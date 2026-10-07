package org.perlonjava.runtime.runtimetypes;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runtime-owned mortal, weak-reference, DESTROY, and reachability sweep state. */
final class LifecycleRuntimeState {
    final AtomicBoolean boundaryWorkRegistered = new AtomicBoolean();
    boolean mortalActive = true;
    final ArrayList<RuntimeBase> pending = new ArrayList<>();
    // Parallel to pending.  A non-null entry retains trace-only provenance for
    // an owner token whose scalar was cleared when its decrement was queued.
    final ArrayList<RuntimeBase.PendingOwnerRelease> pendingOwnerReleases = new ArrayList<>();
    // Parallel to pending.  Weak provenance permits a tail-call handoff to
    // drain only the decrement whose owning argument it actually replaces.
    final ArrayList<java.lang.ref.WeakReference<RuntimeScalar>> pendingOwnerScalars = new ArrayList<>();
    // Parallel to pending. A non-scalar transient owner kind is recorded only
    // for trace attribution; it never changes runtime reachability.
    final ArrayList<String> pendingTransientOwnerKinds = new ArrayList<>();
    final ArrayList<TiedVariableBase> pendingTiedReleases = new ArrayList<>();
    final ArrayList<RuntimeScalar> pendingIoReleases = new ArrayList<>();
    final ArrayList<RuntimeScalar> deferredCaptures = new ArrayList<>();
    final IdentityHashMap<RuntimeScalar, Integer> deferredCapturesSet = new IdentityHashMap<>();
    boolean deferredCapturesMayBeReady;
    final ArrayDeque<RuntimeBase> temporaryRoots = new ArrayDeque<>();
    final IdentityHashMap<RuntimeBase, Integer> suspendedRoots = new IdentityHashMap<>();
    final ArrayList<Integer> marks = new ArrayList<>();
    final ArrayList<Integer> tiedReleaseMarks = new ArrayList<>();
    final ArrayList<Integer> ioReleaseMarks = new ArrayList<>();
    boolean flushing;
    int topLevelFlushDepth;
    // Start within the configured quiet-sweep throttle window. A zero value
    // would trigger a full root walk at the first statement boundary, often
    // between callbacks that are expected to dispatch together.
    long lastAutoSweepNanos = System.nanoTime();
    boolean inAutoSweep;
    boolean immediateWeakSweepRequested;
    boolean deferCaptureReachabilityToBoundarySweep;
    final ArrayList<RuntimeBase> deferredCaptureSweepCandidates = new ArrayList<>();
    long boundaryWeakSweepGeneration;
    long deferredCaptureSweepGeneration;
    final Set<RuntimeBase> targetedWeakSweepReferents =
            Collections.newSetFromMap(new IdentityHashMap<>());
    Set<RuntimeBase> flushReachableCache;
    Set<RuntimeBase> flushTiedReachableCache;
    ReachabilityWalker.ExternalRootSnapshot externalRootSnapshot;
    ReachabilityWalker.LiveRootSnapshot liveRootSnapshot;
    Set<RuntimeBase> weakSweepLiveReferents;

    ReachabilityWalker.RootReachabilitySnapshot fullRootSnapshot;
    final IdentityHashMap<RuntimeBase, ReachabilityWalker.WeakRootWitness>
            weakSweepRootWitnesses = new IdentityHashMap<>();

    final Set<RuntimeScalar> weakScalars = Collections.newSetFromMap(new IdentityHashMap<>());
    final IdentityHashMap<RuntimeBase, Set<RuntimeScalar>> referentToWeakRefs = new IdentityHashMap<>();
    volatile boolean weakRefsExist;

    final BitSet destroyClasses = new BitSet();
    final BitSet destroyClassesChecked = new BitSet();
    final ConcurrentHashMap<Integer, RuntimeScalar> destroyMethodCache = new ConcurrentHashMap<>();
    final Set<RuntimeBase> destroyableObjects =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    final Set<RuntimeBase> statementBoundaryDestroyableObjects =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    RuntimeBase currentDestroyTarget;
    boolean destroyTargetRescued;
    boolean sweepPendingAfterOuterDestroy;
    final List<RuntimeBase> rescuedObjects = Collections.synchronizedList(new ArrayList<>());
    final BitSet walkerGateClasses = new BitSet();
    final BitSet walkerGateChecked = new BitSet();
    boolean blessedObjectExists;
    final Map<RuntimeBase, LinkedHashMap<Integer, String>> traceOwners =
            Collections.synchronizedMap(new IdentityHashMap<>());
    final ScalarRefRegistry.WeakIdentityMap<Boolean> scalarRegistry =
            new ScalarRefRegistry.WeakIdentityMap<>();
    final ScalarRefRegistry.WeakIdentityMap<Throwable> scalarRegisterStacks =
            new ScalarRefRegistry.WeakIdentityMap<>();

    void clear() {
        mortalActive = true;
        for (int i = 0; i < pending.size(); i++) {
            RuntimeBase.PendingOwnerRelease release = pendingOwnerReleases.get(i);
            if (release != null) {
                pending.get(i).cancelQueuedOwnerRelease(release,
                        "LifecycleRuntimeState.clear");
            }
            pending.get(i).releaseTransientTraceOwner(pendingTransientOwnerKinds.get(i),
                    "LifecycleRuntimeState.clear");
        }
        pending.clear();
        pendingOwnerReleases.clear();
        pendingOwnerScalars.clear();
        pendingTransientOwnerKinds.clear();
        pendingTiedReleases.clear();
        pendingIoReleases.clear();
        deferredCaptures.clear();
        deferredCapturesSet.clear();
        deferredCapturesMayBeReady = false;
        temporaryRoots.clear();
        suspendedRoots.clear();
        marks.clear();
        tiedReleaseMarks.clear();
        ioReleaseMarks.clear();
        flushing = false;
        topLevelFlushDepth = 0;
        lastAutoSweepNanos = System.nanoTime();
        inAutoSweep = false;
        fullRootSnapshot = null;
        weakSweepRootWitnesses.clear();
        immediateWeakSweepRequested = false;
        deferCaptureReachabilityToBoundarySweep = false;
        deferredCaptureSweepCandidates.clear();
        boundaryWeakSweepGeneration = 0;
        deferredCaptureSweepGeneration = 0;
        targetedWeakSweepReferents.clear();
        flushReachableCache = null;
        flushTiedReachableCache = null;
        externalRootSnapshot = null;
        liveRootSnapshot = null;
        weakSweepLiveReferents = null;
        weakScalars.clear();
        referentToWeakRefs.clear();
        weakRefsExist = false;
        destroyClasses.clear();
        destroyClassesChecked.clear();
        destroyMethodCache.clear();
        destroyableObjects.clear();
        statementBoundaryDestroyableObjects.clear();
        currentDestroyTarget = null;
        destroyTargetRescued = false;
        sweepPendingAfterOuterDestroy = false;
        rescuedObjects.clear();
        walkerGateClasses.clear();
        walkerGateChecked.clear();
        blessedObjectExists = false;
        traceOwners.clear();
        scalarRegistry.clear();
        scalarRegisterStacks.clear();
    }
}
