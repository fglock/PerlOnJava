package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class ScalarCaptureReferentOwnerTest {

    @Test
    void scalarReferentHoldIsNotCountedAsAnAggregatePadCapture() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            RuntimeScalar scalarReference = referent.createAnonymousReference();
            RuntimeCode scalarCapture = new RuntimeCode(null, List.of());
            PerlOwnerSlot scalarCaptureSlot =
                    scalarReference.retainClosureCaptureSlot(scalarCapture);

            assertEquals(0, referent.captureCount);
            assertEquals(1, referent.ownerSlotCount(PerlOwnerSlot.Kind.CLOSURE_PAD));

            RuntimeCode aggregateCapture = new RuntimeCode(null, List.of());
            PerlOwnerSlot aggregateCaptureSlot = referent.retainClosureCaptureOwner(aggregateCapture);
            assertEquals(1, referent.captureCount);
            assertEquals(2, referent.ownerSlotCount(PerlOwnerSlot.Kind.CLOSURE_PAD));
            assertDoesNotThrow(() -> referent.verifyClosureCaptureOwnerCount(
                    PerlOwnerSlot.Kind.CLOSURE_PAD));

            referent.releaseClosureCaptureOwner(aggregateCaptureSlot);
            scalarReference.releaseClosureCaptureSlot(scalarCaptureSlot);

            assertEquals(0, referent.captureCount);
            assertEquals(0, referent.ownerSlotCount(PerlOwnerSlot.Kind.CLOSURE_PAD));
        }
    }
}
