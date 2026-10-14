package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class UnblessedAggregateDrainPositiveProofCacheTest {
    @Test
    void cachesOnlyPositiveTargetsInsteadOfCopyingEveryReachableRoot() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray global = new RuntimeArray();
            for (int i = 0; i < 128; i++) {
                global.elements.add(new RuntimeHash().createAnonymousReference());
            }
            String globalName =
                    "UnblessedAggregateDrainPositiveProofCacheTest::global";
            GlobalVariable.globalArrays.put(globalName, global);
            RuntimeArray first = new RuntimeArray();
            RuntimeArray second = new RuntimeArray();
            RuntimeArray third = new RuntimeArray();
            first.refCount = 1;
            second.refCount = 1;
            third.refCount = 1;
            MortalList.pushMark();
            MortalList.deferDecrement(first);
            MortalList.deferDecrement(second);
            MortalList.deferDecrement(third);
            try (MortalList.ReachabilityQueryMeasurement measurement =
                         MortalList.measureReachabilityQueries()) {
                MortalList.popAndFlush();

                assertEquals(3, measurement.stats().deferredBasesProcessed);
                assertTrue(measurement.stats().externalRootSnapshotNodesVisited > 128,
                        "the cleanup snapshot examines the populated package root");
                assertEquals(1, measurement.stats().externalRootSnapshotsBuilt,
                        "all three cleanup targets reuse one package-root snapshot");
                assertEquals(0,
                        measurement.stats().unblessedAggregateDrainPositiveProofsCached,
                        "unreachable cleanup targets create no positive drain proofs");
            } finally {
                GlobalVariable.globalArrays.remove(globalName);
                MortalList.invalidateAllRootSnapshots();
            }
        }
    }
}
