package org.perlonjava.runtime.runtimetypes;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class WeakBackrefCodeCaptureSweepTest {
    @Test
    void quietSweepSharesWeakCodeCaptureWalkAcrossAggregateTargets() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray capturedParent = new RuntimeArray();
            RuntimeArray[] targets = new RuntimeArray[12];
            RuntimeScalar[] weakTargets = new RuntimeScalar[targets.length];
            for (int i = 0; i < targets.length; i++) {
                RuntimeArray target = new RuntimeArray();
                capturedParent.elements.add(target.createAnonymousReference());
                targets[i] = target;
                weakTargets[i] = target.createAnonymousReference();
                WeakRefRegistry.weaken(weakTargets[i]);
            }

            RuntimeCode code = new RuntimeCode(null, List.of());
            code.capturedAggregates = new RuntimeBase[] {capturedParent};
            code.captureFieldsRecorded = true;
            RuntimeScalar weakCode = new RuntimeScalar(code);
            WeakRefRegistry.weaken(weakCode);

            runtime.lifecycleState.sweepPendingAfterOuterDestroy = true;
            try {
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    // Drive the same quiet sweep through the lifecycle flush
                    // used after an outer DESTROY, without depending on cadence.
                    MortalList.requestImmediateWeakSweep();
                    MortalList.flush();

                    ReachabilityQueryStats stats = measurement.stats();
                    assertEquals(1, stats.weakBackrefCaptureRootsWalked,
                            "one weak CODE capture graph should be walked for the whole sweep");
                    assertTrue(stats.weakBackrefCaptureNodesVisited >= targets.length,
                            "the shared walk should visit every captured aggregate");
                    assertTrue(stats.weakBackrefCaptureEdgesInspected >= targets.length,
                            "the shared walk should inspect every captured aggregate edge");
                    assertEquals(1, stats.weakBackrefCaptureSnapshotsBuilt);
                    for (RuntimeScalar weakTarget : weakTargets) {
                        assertTrue(WeakRefRegistry.isweak(weakTarget),
                                "a weak backref CODE capture protects each aggregate target");
                    }
                }
            } finally {
                runtime.lifecycleState.sweepPendingAfterOuterDestroy = false;
                WeakRefRegistry.unweaken(weakCode);
                for (RuntimeScalar weakTarget : weakTargets) {
                    WeakRefRegistry.unweaken(weakTarget);
                }
                MortalList.invalidateAllRootSnapshots();
            }
        }
    }
}
