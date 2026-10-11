package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class UnblessedAggregateReachabilitySnapshotTest {
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
                    assertEquals(1, stats.snapshotsBuilt,
                            "live unblessed aggregates share one root snapshot per drain");
                    assertEquals(0, stats.rootQueries,
                            "aggregate preservation must not launch one root query per target");
                    assertTrue(stats.rootsSeeded > 0);
                    assertTrue(stats.edgesInspected >= targets.length,
                            "the snapshot must inspect the rooted aggregate edges");
                    assertTrue(stats.nodesVisited >= targets.length + 1,
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
}
