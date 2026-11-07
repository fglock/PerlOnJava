package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class WeakBackrefOwnerSlotFastPathTest {
    @Test
    void elementOwnerAvoidsWeakCodeCaptureGraphWalk() {
        Assumptions.assumeTrue(ReachabilityWalker.correctiveSweepsEnabled(),
                "this compatibility path only runs when corrective sweeps are enabled");

        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray capturedParent = new RuntimeArray();
            RuntimeArray target = new RuntimeArray();
            capturedParent.addClonedElement(target.createAnonymousReference());
            assertEquals(1, target.ownerSlotCount(PerlOwnerSlot.Kind.ARRAY_ELEMENT));

            RuntimeCode weakBackrefCode = new RuntimeCode(null, List.of());
            weakBackrefCode.capturedAggregates = new RuntimeBase[] {capturedParent};
            weakBackrefCode.captureFieldsRecorded = true;
            RuntimeScalar weakCode = new RuntimeScalar(weakBackrefCode);
            WeakRefRegistry.weaken(weakCode);

            RuntimeScalar weakTarget = target.createAnonymousReference();
            WeakRefRegistry.weaken(weakTarget);
            try {
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    ReachabilityWalker.sweepWeakRefs(true, false);

                    assertTrue(WeakRefRegistry.isweak(weakTarget),
                            "the active element owner keeps the weakly observed target alive");
                    assertEquals(0, measurement.stats().weakBackrefCaptureSnapshotsBuilt,
                            "an owner-slot proof avoids the weak CODE capture graph walk");
                }
            } finally {
                WeakRefRegistry.unweaken(weakTarget);
                WeakRefRegistry.unweaken(weakCode);
            }
        }
    }
}
