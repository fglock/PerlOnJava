package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class UnblessedAggregateReachabilitySnapshotTest {
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
