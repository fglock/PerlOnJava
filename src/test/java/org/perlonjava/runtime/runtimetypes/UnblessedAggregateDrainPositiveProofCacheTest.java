package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class UnblessedAggregateDrainPositiveProofCacheTest {
    @Test
    void unrootedTargetsFinalizeWithoutBuildingAReachabilityCache() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray global = new RuntimeArray();
            for (int i = 0; i < 128; i++) {
                global.elements.add(new RuntimeHash().createAnonymousReference());
            }
            String globalName =
                    "UnblessedAggregateDrainPositiveProofCacheTest::global";
            GlobalVariable.globalArrays.put(globalName, global);
            RuntimeArray[] targets = {
                    new RuntimeArray(), new RuntimeArray(), new RuntimeArray()
            };
            RuntimeScalar[] weak = new RuntimeScalar[targets.length];

            try {
                MortalList.pushMark();
                for (int i = 0; i < targets.length; i++) {
                    weak[i] = targets[i].createAnonymousReference();
                    WeakRefRegistry.weaken(weak[i]);
                    CountedOwnerFixture.queueRelease(
                            CountedOwnerFixture.temporaryReference(targets[i]));
                }
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    MortalList.popAndFlush();

                    assertEquals(3, measurement.stats().deferredBasesProcessed);
                    assertEquals(0, measurement.stats().rootQueries);
                    assertEquals(0, measurement.stats().externalRootSnapshotsBuilt);
                    assertEquals(0, measurement.stats().externalRootSnapshotNodesVisited);
                    assertEquals(0,
                            measurement.stats().unblessedAggregateDrainPositiveProofsCached);
                    for (RuntimeScalar observer : weak) {
                        assertEquals(RuntimeScalarType.UNDEF, observer.type);
                    }
                }
            } finally {
                for (RuntimeScalar observer : weak) {
                    if (observer != null && WeakRefRegistry.isweak(observer)) {
                        WeakRefRegistry.unweaken(observer);
                    }
                }
                GlobalVariable.globalArrays.remove(globalName);
            }
        }
    }


}
