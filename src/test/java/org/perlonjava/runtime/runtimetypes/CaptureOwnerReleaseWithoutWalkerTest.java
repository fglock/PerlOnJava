package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class CaptureOwnerReleaseWithoutWalkerTest {
    @Test
    void explicitCodeOwnerSlotReleasesItsCapturedAggregateDirectly() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray referent = new RuntimeArray();
            referent.scopeExited = true;

            RuntimeCode closure = new RuntimeCode((args, context) -> new RuntimeList(), null);
            closure.setTrackedRefCount(1);
            PerlOwnerSlot capture = referent.retainClosureCaptureOwner(closure);
            closure.capturedAggregates = new RuntimeBase[] {referent};
            closure.capturedAggregateOwnerSlots = new PerlOwnerSlot[] {capture};

            try (MortalList.ReachabilityQueryMeasurement measurement =
                         MortalList.measureReachabilityQueries()) {
                assertTrue(capture.isActive());
                closure.releaseCaptures();

                assertFalse(capture.isActive(),
                        "releasing the CODE owner retires its capture slot");
                assertEquals(0, referent.semanticCaptureOwnerCount());
                assertEquals(0, measurement.stats().rootQueries,
                        "capture release requires no reachability walk");
                assertEquals(0, measurement.stats().snapshotsBuilt);
            }
        }
    }
}
