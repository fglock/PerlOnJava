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
import java.util.WeakHashMap;

/** Runtime-owned mortal, weak-reference, DESTROY, and reachability sweep state. */
final class LifecycleRuntimeState {
    final AtomicBoolean boundaryWorkRegistered = new AtomicBoolean();
    boolean mortalActive = true;
    final ArrayList<RuntimeBase> pending = new ArrayList<>();
    final ArrayList<TiedVariableBase> pendingTiedReleases = new ArrayList<>();
    final ArrayList<RuntimeScalar> deferredCaptures = new ArrayList<>();
    final IdentityHashMap<RuntimeScalar, Integer> deferredCapturesSet = new IdentityHashMap<>();
    boolean deferredCapturesMayBeReady;
    final ArrayDeque<RuntimeBase> temporaryRoots = new ArrayDeque<>();
    final IdentityHashMap<RuntimeBase, Integer> suspendedRoots = new IdentityHashMap<>();
    final ArrayList<Integer> marks = new ArrayList<>();
    final ArrayList<Integer> tiedReleaseMarks = new ArrayList<>();
    boolean flushing;
    long lastAutoSweepNanos;
    boolean inAutoSweep;
    boolean immediateWeakSweepRequested;
    final Set<RuntimeBase> targetedWeakSweepReferents =
            Collections.newSetFromMap(new IdentityHashMap<>());
    Set<RuntimeBase> flushReachableCache;
    ReachabilityWalker.ExternalRootSnapshot externalRootSnapshot;
    ReachabilityWalker.LiveRootSnapshot liveRootSnapshot;

    final Set<RuntimeScalar> weakScalars = Collections.newSetFromMap(new IdentityHashMap<>());
    final IdentityHashMap<RuntimeBase, Set<RuntimeScalar>> referentToWeakRefs = new IdentityHashMap<>();
    volatile boolean weakRefsExist;

    final BitSet destroyClasses = new BitSet();
    final ConcurrentHashMap<Integer, RuntimeScalar> destroyMethodCache = new ConcurrentHashMap<>();
    final Set<RuntimeBase> destroyableObjects =
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
    final Map<RuntimeScalar, Boolean> scalarRegistry = new WeakHashMap<>();
    final Map<RuntimeScalar, Throwable> scalarRegisterStacks = new WeakHashMap<>();

    void clear() {
        mortalActive = true;
        pending.clear();
        pendingTiedReleases.clear();
        deferredCaptures.clear();
        deferredCapturesSet.clear();
        deferredCapturesMayBeReady = false;
        temporaryRoots.clear();
        suspendedRoots.clear();
        marks.clear();
        tiedReleaseMarks.clear();
        flushing = false;
        lastAutoSweepNanos = 0;
        inAutoSweep = false;
        immediateWeakSweepRequested = false;
        targetedWeakSweepReferents.clear();
        flushReachableCache = null;
        externalRootSnapshot = null;
        liveRootSnapshot = null;
        weakScalars.clear();
        referentToWeakRefs.clear();
        weakRefsExist = false;
        destroyClasses.clear();
        destroyMethodCache.clear();
        destroyableObjects.clear();
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
