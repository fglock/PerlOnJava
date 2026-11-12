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
            }
        }
    }

    @Test
    void initialWeakParentRegistrationUsesItsScalarOwnerSlot() {
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
                    assertEquals(WeakRefRegistry.WEAKLY_TRACKED, target.refCount,
                            "the exact untracked owner slot keeps the aggregate alive");
                    assertEquals(1, target.ownerSlotCount(
                            PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));
                    assertEquals(0, measurement.stats().rootQueries,
                            "the first weak transition does not walk package roots");
                } finally {
                    WeakRefRegistry.unweaken(weak);
                    GlobalVariable.globalArrays.remove(globalName);
                }
            }
        }
    }

    @Test
    void liveWeaklyObservedAggregatesSurviveThroughCountedElementSlots() {
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
                    RuntimeScalar packageOwner = CountedOwnerFixture.reference(
                            target, PerlOwnerSlot.Kind.ARRAY_ELEMENT);
                    global.elements.add(packageOwner);
                    RuntimeScalar weak = target.createAnonymousReference();
                    WeakRefRegistry.weaken(weak);
                    targets[i] = target;
                    weakRefs[i] = weak;
                    RuntimeScalar temporaryOwner =
                            CountedOwnerFixture.temporaryReference(target);
                    CountedOwnerFixture.queueRelease(temporaryOwner);
                }

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    MortalList.popAndFlush();

                    ReachabilityQueryStats stats = measurement.stats();
                    assertEquals(3, stats.deferredBasesProcessed);
                    assertEquals(0, stats.externalRootSnapshotsBuilt,
                            "current package element slots require no root snapshot");
                    assertEquals(0, stats.rootQueries,
                            "aggregate preservation uses exact owner identities");
                    assertEquals(0, stats.externalRootSnapshotEdgesInspected);
                    assertEquals(0, stats.externalRootSnapshotNodesVisited);
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
            }
        }
    }

    @Test
    void deferredUnblessedAggregateOwnersNeedNoMeasuredRootWalk() {
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
                    RuntimeScalar temporaryOwner =
                            CountedOwnerFixture.temporaryReference(target);
                    CountedOwnerFixture.queueRelease(temporaryOwner);
                }

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    MortalList.popAndFlush();

                    ReachabilityQueryStats stats = measurement.stats();
                    assertEquals(3, stats.deferredBasesProcessed);
                    assertEquals(0, stats.scalarReachabilityQueries);
                    assertEquals(0, stats.scalarReachabilityRootWalks,
                            "nested owner checks must use the drain snapshot");
                    assertEquals(0, stats.rootQueries,
                            "the drain must not start a target-specific root walk");
                    assertEquals(0, stats.snapshotsBuilt,
                            "current counted slots decide cleanup directly");
                    for (RuntimeHash target : targets) {
                        assertEquals(1, target.refCount,
                                "a strong package-root owner preserves its weakly observed target");
                    }
                }
            } finally {
                GlobalVariable.globalArrays.remove(globalName);
            }
        }
    }

    @Test
    void repeatedPackageOwnerSlotChecksNeedNoRootSnapshot() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray global = new RuntimeArray();
            String globalName =
                    "UnblessedAggregateReachabilitySnapshotTest::scalarOwners";
            GlobalVariable.globalArrays.put(globalName, global);
            RuntimeHash target = new RuntimeHash();
            RuntimeScalar[] owners = new RuntimeScalar[3];
            RuntimeScalar weak = null;
            try {
                for (int i = 0; i < 256; i++) {
                    global.elements.add(new RuntimeHash().createReference());
                }
                for (int i = 0; i < owners.length; i++) {
                    RuntimeScalar owner = CountedOwnerFixture.reference(
                            target, PerlOwnerSlot.Kind.ARRAY_ELEMENT);
                    global.elements.add(owner);
                    owners[i] = owner;
                }
                weak = target.createAnonymousReference();
                WeakRefRegistry.weaken(weak);

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    for (int i = 0; i < owners.length; i++) {
                        CountedOwnerFixture.queueRelease(
                                CountedOwnerFixture.temporaryReference(target));
                    }
                    MortalList.flush();

                    ReachabilityQueryStats stats = measurement.stats();
                    assertEquals(3, stats.deferredBasesProcessed);
                    assertEquals(0, stats.rootQueries);
                    assertEquals(0, stats.scalarReachabilityQueries);
                    assertEquals(0, stats.scalarReachabilityRootWalks);
                    assertEquals(0, stats.snapshotsBuilt);
                    assertEquals(3, target.refCount,
                            "the three current element slots remain counted Perl owners");
                    assertTrue(WeakRefRegistry.isweak(weak));
                }
            } finally {
                if (weak != null) WeakRefRegistry.unweaken(weak);
                GlobalVariable.globalArrays.remove(globalName);
            }
        }
    }

}
