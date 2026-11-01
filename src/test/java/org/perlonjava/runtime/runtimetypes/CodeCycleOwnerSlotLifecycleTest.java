package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class CodeCycleOwnerSlotLifecycleTest {
    @Test
    void restoresTheCurrentSelfCaptureOwnerWithoutWalkingTheObjectGraph() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeCode code = new RuntimeCode((args, context) -> new RuntimeList(), null);
            code.refCount = 0;
            RuntimeScalar capture = new RuntimeScalar();
            PerlOwnerSlot captureSlot = capture.retainClosureCaptureSlot(code);
            capture.set(new RuntimeScalar(code));
            code.capturedScalars = new RuntimeScalar[] { capture };
            code.capturedScalarOwnerSlots = new PerlOwnerSlot[] { captureSlot };
            code.captureFieldsRecorded = true;

            RuntimeScalar weakObserver = new RuntimeScalar(code);
            WeakRefRegistry.weaken(weakObserver);
            assertTrue(WeakRefRegistry.isweak(weakObserver));
            assertEquals(1, code.countedOwnerSlotCount());

            // Exercise the legacy undercount bridge while the real self-capture
            // slot remains current. Normal Perl cycle lifetime is covered by
            // weak_self_retaining_closure_cycle.t and self_capture_cycle_owner.t.
            code.refCount = Integer.MIN_VALUE;
            try (MortalList.ReachabilityQueryMeasurement measurement =
                         MortalList.measureReachabilityQueries()) {
                DestroyDispatch.callDestroy(code);

                assertEquals(1, code.refCount,
                        "the current self-capture edge restores the compatibility count");
                assertEquals(1, measurement.stats().codeOwnerSlotRestorations);
                assertEquals(0, measurement.stats().rootQueries);
                assertEquals(0, measurement.stats().snapshotsBuilt);
                assertTrue(WeakRefRegistry.isweak(weakObserver));
            }

            capture.set(new RuntimeScalar());
            assertFalse(WeakRefRegistry.isweak(weakObserver),
                    "removing the final self-capture edge releases the weak observer");
        }
    }
}
