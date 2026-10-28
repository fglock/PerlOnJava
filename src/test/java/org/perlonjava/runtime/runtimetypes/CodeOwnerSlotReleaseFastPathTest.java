package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@Tag("unit")
class CodeOwnerSlotReleaseFastPathTest {
    @Test
    void anotherScalarOwnerSkipsClosureCaptureRootWalk() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeCode closure = new RuntimeCode((args, context) -> new RuntimeList(), null);
            closure.refCount = 0;
            RuntimeScalar pad = new RuntimeScalar();
            closure.capturedScalars = new RuntimeScalar[] {pad};
            closure.capturedScalarOwnerSlots = new PerlOwnerSlot[] {
                    pad.retainClosureCaptureSlot(closure)
            };

            RuntimeScalar external = new RuntimeScalar();
            external.set(new RuntimeScalar(closure));
            RuntimeScalar local = new RuntimeScalar();
            local.set(new RuntimeScalar(closure));
            assertEquals(2, closure.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_CELL));
            assertEquals(0, closure.ownerSlotCount(PerlOwnerSlot.Kind.CODE_SLOT));

            try (MortalList.ReachabilityQueryMeasurement measurement =
                         MortalList.measureReachabilityQueries()) {
                local.set(new RuntimeScalar());

                assertNotNull(closure.capturedScalars,
                        "the other scalar owner keeps closure captures alive");
                assertEquals(1, closure.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_CELL));
                assertEquals(0, measurement.stats().rootQueries,
                        "an explicit owner slot replaces the closure-root query");
            }

            external.set(new RuntimeScalar());
            assertNull(closure.capturedScalars,
                    "the final scalar owner still releases closure captures");
        }
    }
}
