package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

@Tag("unit")
class FinalCodeOwnerCaptureReleaseFastPathTest {
    @Test
    void finalScalarOwnerReleasesCapturesWithoutRootWalk() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeCode closure = new RuntimeCode((args, context) -> new RuntimeList(), null);
            closure.refCount = 0;
            RuntimeScalar capturedPad = new RuntimeScalar();
            PerlOwnerSlot captureSlot = capturedPad.retainClosureCaptureSlot(closure);
            closure.capturedScalars = new RuntimeScalar[]{capturedPad};
            closure.capturedScalarOwnerSlots = new PerlOwnerSlot[]{captureSlot};

            RuntimeScalar codeOwner = new RuntimeScalar();
            codeOwner.set(new RuntimeScalar(closure));
            assertEquals(1, closure.refCount);

            try (MortalList.ReachabilityQueryMeasurement measurement =
                         MortalList.measureReachabilityQueries()) {
                codeOwner.set(new RuntimeScalar());

                assertNull(closure.capturedScalars,
                        "the final scalar owner releases the closure's captured pads");
                assertNull(closure.capturedScalarOwnerSlots);
                assertEquals(0, capturedPad.captureCount);
                assertFalse(captureSlot.isActive());
                assertEquals(0, measurement.stats().rootQueries);
                assertEquals(0, measurement.stats().snapshotsBuilt);
            }
        }
    }
}
