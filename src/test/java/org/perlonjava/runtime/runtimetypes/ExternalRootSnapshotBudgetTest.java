package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ExternalRootSnapshotBudgetTest {
    @Test
    void oversizedTargetRootWalkStopsAndRetainsUnknownTargets() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash largeRoot = new RuntimeHash();
            for (int i = 0; i <= ReachabilityWalker.MAX_ROOT_SNAPSHOT_EDGES; i++) {
                largeRoot.elements.put("edge-" + i, null);
            }
            RuntimeArray global = new RuntimeArray();
            global.elements.add(largeRoot.createReference());
            String globalName = "ExternalRootSnapshotBudgetTest::targetLargeRoot";
            GlobalVariable.globalArrays.put(globalName, global);
            RuntimeArray unresolved = new RuntimeArray();

            try {
                ReachabilityQueryStats stats;
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    assertTrue(ReachabilityWalker.isReachableFromRoots(unresolved),
                            "an incomplete targeted walk cannot prove an object dead");
                    stats = measurement.stats();
                }

                assertEquals(1, stats.rootQueries);
                assertTrue(stats.edgesInspected <= ReachabilityWalker.MAX_ROOT_SNAPSHOT_EDGES,
                        "the targeted root walk stays within its edge budget");
            } finally {
                GlobalVariable.globalArrays.remove(globalName);
                MortalList.invalidateAllRootSnapshots();
            }
        }
    }

    @Test
    void oversizedExternalRootWalkStopsAtEdgeBudgetAndRetainsUnknownTargets() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash largeRoot = new RuntimeHash();
            for (int i = 0; i <= ReachabilityWalker.MAX_ROOT_SNAPSHOT_EDGES; i++) {
                largeRoot.elements.put("edge-" + i, null);
            }
            RuntimeArray global = new RuntimeArray();
            global.elements.add(largeRoot.createReference());
            String globalName = "ExternalRootSnapshotBudgetTest::largeRoot";
            GlobalVariable.globalArrays.put(globalName, global);
            RuntimeArray unresolved = new RuntimeArray();

            try {
                ReachabilityWalker.ExternalRootSnapshot snapshot;
                ReachabilityQueryStats stats;
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    snapshot = new ReachabilityWalker.ExternalRootSnapshot(false);
                    stats = measurement.stats();
                }

                assertEquals(ReachabilityWalker.MAX_ROOT_SNAPSHOT_EDGES,
                        stats.externalRootSnapshotEdgesInspected,
                        "the external-root walk stops inspecting edges at its cap");
                assertFalse(snapshot.complete(),
                        "a capped external-root walk cannot prove an object dead");
                assertTrue(snapshot.isReachable(unresolved),
                        "unknown targets remain protected when the root proof is incomplete");
            } finally {
                GlobalVariable.globalArrays.remove(globalName);
                MortalList.invalidateAllRootSnapshots();
            }
        }
    }
}
