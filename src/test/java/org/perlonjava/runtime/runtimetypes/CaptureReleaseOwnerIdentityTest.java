package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class CaptureReleaseOwnerIdentityTest {
    @Test
    void scalarCaptureReleaseValidatesItsPadBeforeRetiringTheSlot() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeCode closure = new RuntimeCode((args, context) -> new RuntimeList(), null);
            RuntimeScalar actualPad = new RuntimeScalar(1);
            RuntimeScalar wrongPad = new RuntimeScalar(2);
            PerlOwnerSlot owner = actualPad.retainClosureCaptureSlot(closure);

            assertThrows(IllegalStateException.class,
                    () -> wrongPad.releaseClosureCaptureSlot(owner));

            assertTrue(owner.isActive(), "a mismatched release must preserve the actual pad slot");
            assertSame(actualPad, owner.referent());
            assertEquals(1, actualPad.captureCount);
            assertEquals(0, wrongPad.captureCount);

            actualPad.releaseClosureCaptureSlot(owner);
            assertFalse(owner.isActive());
            assertEquals(0, actualPad.captureCount);
        }
    }

    @Test
    void aggregateCaptureReleaseValidatesItsPadBeforeRetiringTheSlot() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeCode closure = new RuntimeCode((args, context) -> new RuntimeList(), null);
            RuntimeHash actualPad = new RuntimeHash();
            RuntimeArray wrongPad = new RuntimeArray();
            PerlOwnerSlot owner = actualPad.retainClosureCaptureOwner(closure);

            assertThrows(IllegalStateException.class,
                    () -> wrongPad.releaseClosureCaptureOwner(owner));

            assertTrue(owner.isActive(), "a mismatched release must preserve the actual pad slot");
            assertSame(actualPad, owner.referent());
            assertEquals(1, actualPad.captureCount);
            assertEquals(0, wrongPad.captureCount);

            actualPad.releaseClosureCaptureOwner(owner);
            assertFalse(owner.isActive());
            assertEquals(0, actualPad.captureCount);
        }
    }
}
