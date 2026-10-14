package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class UnblessedAggregateReachabilitySnapshotTest {
    @Test
    void oversizedRootWalkStopsAtEdgeBudgetAndTreatsUnknownTargetsAsReachable() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash largeRoot = new RuntimeHash();
            for (int i = 0; i <= ReachabilityWalker.MAX_ROOT_SNAPSHOT_EDGES; i++) {
                largeRoot.elements.put("edge-" + i, new RuntimeScalar(i));
            }
            RuntimeArray global = new RuntimeArray();
            global.elements.add(largeRoot.createReference());
            String globalName =
                    "UnblessedAggregateReachabilitySnapshotTest::largeRoot";
            GlobalVariable.globalArrays.put(globalName, global);
            RuntimeArray unresolved = new RuntimeArray();

            try {
                ReachabilityWalker.RootReachabilitySnapshot snapshot =
                        ReachabilityWalker.reachableFromRootsSnapshotWithStatus();

                assertEquals(ReachabilityWalker.MAX_ROOT_SNAPSHOT_EDGES,
                        snapshot.edgesVisited(), "the full-root walk must stop at its edge budget");
                assertFalse(snapshot.complete(), "a capped root walk cannot prove an object dead");
                assertTrue(snapshot.isReachable(unresolved),
                        "unknown targets remain protected when the root proof is incomplete");
            } finally {
                GlobalVariable.globalArrays.remove(globalName);
                MortalList.invalidateAllRootSnapshots();
            }
        }
    }

    @Test
    void initialWeakParentRegistrationPreservesAnUntrackedRootedAggregate() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray global = new RuntimeArray();
            String globalName = "UnblessedAggregateReachabilitySnapshotTest::weakParentRoot";
            GlobalVariable.globalArrays.put(globalName, global);
            RuntimeArray target = new RuntimeArray();
            RuntimeScalar strong = target.createAnonymousReference();
            target.refCount = -1;
            global.elements.add(strong);
            RuntimeScalar weak = target.createAnonymousReference();
            target.refCount = -1;

            try (MortalList.ReachabilityQueryMeasurement measurement =
                         MortalList.measureReachabilityQueries()) {
                try {
                    WeakRefRegistry.weaken(weak);
                    assertTrue(WeakRefRegistry.isweak(weak));
                    assertEquals(1, target.refCount,
                            "the root proof protects an untracked aggregate before its first weak edge");
                    assertEquals(1, measurement.stats().rootQueries,
                            "the initial weak-parent transition checks the current live root");
                } finally {
                    WeakRefRegistry.unweaken(weak);
                    GlobalVariable.globalArrays.remove(globalName);
                    MortalList.invalidateAllRootSnapshots();
                }
            }
        }
    }

    @Test
    void liveWeaklyObservedAggregatesSurviveOneSharedRootSnapshotPerDrain() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray global = new RuntimeArray();
            String globalName = "UnblessedAggregateReachabilitySnapshotTest::liveAggregates";
            GlobalVariable.globalArrays.put(globalName, global);
            RuntimeArray[] targets = new RuntimeArray[3];
            RuntimeScalar[] weakRefs = new RuntimeScalar[3];

            try {
                MortalList.pushMark();
                for (int i = 0; i < targets.length; i++) {
                    RuntimeArray target = new RuntimeArray();
                    target.refCount = 1;
                    global.elements.add(target.createAnonymousReference());
                    RuntimeScalar weak = target.createAnonymousReference();
                    WeakRefRegistry.weaken(weak);
                    targets[i] = target;
                    weakRefs[i] = weak;
                    MortalList.deferDecrement(target);
                }

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    MortalList.popAndFlush();

                    ReachabilityQueryStats stats = measurement.stats();
                    assertEquals(3, stats.deferredBasesProcessed);
                    assertEquals(1, stats.externalRootSnapshotsBuilt,
                            "live unblessed aggregates share one package-root snapshot per drain");
                    assertEquals(0, stats.rootQueries,
                            "aggregate preservation must not launch one root query per target");
                    assertTrue(stats.externalRootSnapshotEdgesInspected >= targets.length,
                            "the snapshot must inspect the rooted aggregate edges");
                    assertTrue(stats.externalRootSnapshotNodesVisited >= targets.length + 1,
                            "the snapshot must visit the global root and all targets");
                    for (RuntimeArray target : targets) {
                        assertEquals(1, target.refCount,
                                "a strong global owner preserves the weakly observed aggregate");
                    }
                }
            } finally {
                for (RuntimeScalar weak : weakRefs) {
                    if (weak != null) WeakRefRegistry.unweaken(weak);
                }
                GlobalVariable.globalArrays.remove(globalName);
                MortalList.invalidateAllRootSnapshots();
            }
        }
    }

    @Test
    void deferredUnblessedAggregateOwnersShareOneMeasuredRootWalk() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray global = new RuntimeArray();
            RuntimeArray nestedOwners = new RuntimeArray();
            global.elements.add(nestedOwners.createReference());
            for (int i = 0; i < 256; i++) {
                global.elements.add(new RuntimeHash().createReference());
            }
            String globalName = "UnblessedAggregateReachabilitySnapshotTest::global";
            GlobalVariable.globalArrays.put(globalName, global);

            try {
                MortalList.pushMark();
                RuntimeHash[] targets = new RuntimeHash[3];
                for (int i = 0; i < targets.length; i++) {
                    RuntimeHash target = new RuntimeHash();
                    target.activateOwnerTracking();
                    RuntimeScalar owner = target.createAnonymousReference();
                    RuntimeScalar.incrementRefCountForContainerStore(owner);
                    nestedOwners.elements.add(owner);
                    WeakRefRegistry.weaken(target.createAnonymousReference());
                    targets[i] = target;
                    MortalList.deferDecrement(target);
                }

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    MortalList.popAndFlush();

                    ReachabilityQueryStats stats = measurement.stats();
                    assertEquals(3, stats.deferredBasesProcessed);
                    assertEquals(3, stats.scalarReachabilityQueries,
                            "each unblessed aggregate validates its counted owner");
                    assertEquals(0, stats.scalarReachabilityRootWalks,
                            "nested owner checks must use the drain snapshot");
                    assertEquals(0, stats.rootQueries,
                            "the drain must not start a target-specific root walk");
                    assertEquals(1, stats.snapshotsBuilt,
                            "all three aggregate checks share one full-root snapshot");
                    assertTrue(stats.rootsSeeded > 0,
                            "the snapshot must seed package roots");
                    assertTrue(stats.edgesInspected >= 256,
                            "the snapshot must account for the unrelated package-root edges");
                    assertTrue(stats.nodesVisited >= 257,
                            "the snapshot must account for visiting the root and its children");
                    for (RuntimeHash target : targets) {
                        assertEquals(1, target.refCount,
                                "a strong package-root owner preserves its weakly observed target");
                    }
                }
            } finally {
                GlobalVariable.globalArrays.remove(globalName);
                MortalList.invalidateAllRootSnapshots();
            }
        }
    }

    @Test
    void repeatedOwnerScalarChecksShareOneRootSnapshotPerDrain() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray global = new RuntimeArray();
            String globalName =
                    "UnblessedAggregateReachabilitySnapshotTest::scalarOwners";
            GlobalVariable.globalArrays.put(globalName, global);
            RuntimeHash target = new RuntimeHash();
            target.activateOwnerTracking();
            RuntimeScalar[] owners = new RuntimeScalar[3];
            RuntimeScalar weak = null;
            try {
                for (int i = 0; i < 256; i++) {
                    global.elements.add(new RuntimeHash().createReference());
                }
                for (int i = 0; i < owners.length; i++) {
                    RuntimeScalar owner = target.createAnonymousReference();
                    RuntimeScalar.incrementRefCountForContainerStore(owner);
                    global.elements.add(owner);
                    // Exercise the graph fallback instead of the direct
                    // package-array/container-owner shortcut.
                    owner.containerOwner = null;
                    owners[i] = owner;
                }
                weak = target.createAnonymousReference();
                WeakRefRegistry.weaken(weak);

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    for (int i = 0; i < owners.length; i++) {
                        MortalList.deferDecrement(target);
                    }
                    MortalList.flush();

                    ReachabilityQueryStats stats = measurement.stats();
                    assertEquals(3, stats.scalarReachabilityQueries,
                            "all active owner scalars are checked at the zero transition");
                    assertEquals(1, stats.scalarReachabilityRootWalks,
                            "the package/live root graph is traversed once per drain");
                    assertEquals(1, stats.snapshotsBuilt,
                            "subsequent owner checks reuse the same identity snapshot");
                    assertTrue(stats.rootsSeeded > 0);
                    assertTrue(stats.edgesInspected >= 259);
                    assertTrue(stats.nodesVisited >= 260);
                    assertEquals(3, target.refCount,
                            "all three package-rooted owners restore the referent count");
                }
            } finally {
                if (weak != null) WeakRefRegistry.unweaken(weak);
                GlobalVariable.globalArrays.remove(globalName);
                MortalList.invalidateAllRootSnapshots();
            }
        }
    }
}
