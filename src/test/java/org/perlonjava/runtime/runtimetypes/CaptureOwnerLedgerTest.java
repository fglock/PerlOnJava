package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class CaptureOwnerLedgerTest {
    @Test
    void scalarCaptureAcquiresAndReleasesOneMatchingOwnerSlot() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeScalar scalar = new RuntimeScalar();
            RuntimeCode owner = code();

            PerlOwnerSlot slot = scalar.retainClosureCaptureSlot(owner);

            assertEquals(1, scalar.captureCount);
            assertEquals(1, scalar.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_CAPTURE));
            assertEquals(0, scalar.legacyCaptureOwnerCount());
            scalar.verifyClosureCaptureOwnerCount(PerlOwnerSlot.Kind.SCALAR_CAPTURE);

            scalar.releaseClosureCaptureSlot(slot);

            assertEquals(0, scalar.captureCount);
            assertEquals(0, scalar.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_CAPTURE));
            scalar.verifyClosureCaptureOwnerCount(PerlOwnerSlot.Kind.SCALAR_CAPTURE);
        }
    }

    @Test
    void aggregateCaptureAcquiresAndReleasesOneMatchingOwnerSlot() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeArray array = new RuntimeArray();
            RuntimeCode owner = code();

            PerlOwnerSlot slot = array.retainClosureCaptureOwner(owner);

            assertEquals(1, array.captureCount);
            assertEquals(1, array.ownerSlotCount(PerlOwnerSlot.Kind.CLOSURE_PAD));
            assertEquals(0, array.legacyCaptureOwnerCount());
            array.verifyClosureCaptureOwnerCount(PerlOwnerSlot.Kind.CLOSURE_PAD);

            array.releaseClosureCaptureOwner(slot);

            assertEquals(0, array.captureCount);
            assertEquals(0, array.ownerSlotCount(PerlOwnerSlot.Kind.CLOSURE_PAD));
            array.verifyClosureCaptureOwnerCount(PerlOwnerSlot.Kind.CLOSURE_PAD);
        }
    }

    @Test
    void directLegacyCaptureCountKeepsExplicitProvenance() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeScalar scalar = new RuntimeScalar();

            scalar.retainClosureCapture();

            assertEquals(1, scalar.captureCount);
            assertEquals(0, scalar.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_CAPTURE));
            assertEquals(1, scalar.legacyCaptureOwnerCount());
            scalar.verifyClosureCaptureOwnerCount(PerlOwnerSlot.Kind.SCALAR_CAPTURE);

            scalar.releaseClosureCapture();

            assertEquals(0, scalar.captureCount);
            assertEquals(0, scalar.legacyCaptureOwnerCount());
            scalar.verifyClosureCaptureOwnerCount(PerlOwnerSlot.Kind.SCALAR_CAPTURE);
        }
    }

    @Test
    void staleRawCaptureCountFailsTheOwnerInvariant() {
        RuntimeScalar scalar = new RuntimeScalar();
        scalar.captureCount = 1;

        assertThrows(IllegalStateException.class,
                () -> scalar.verifyClosureCaptureOwnerCount(PerlOwnerSlot.Kind.SCALAR_CAPTURE));
    }

    private static RuntimeCode code() {
        return new RuntimeCode((args, context) -> new RuntimeList(), null);
    }
}
