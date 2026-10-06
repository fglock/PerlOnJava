package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class PerlOwnerSlotNativeCaptureTest {
    @Test
    void capturedAggregateUsesTheOwnerSlotCountWithoutChangingLegacyCount() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            referent.refCount = 4;
            PerlOwnerSlot slot = new PerlOwnerSlot(PerlOwnerSlot.Kind.CLOSURE_PAD);

            slot.acquireCapture(referent);

            assertEquals(4, referent.refCount);
            assertEquals(0, slot.legacyCaptureCount());
            assertEquals(1, referent.semanticCaptureOwnerCount());
            assertEquals(1, referent.nativeCaptureOwnerCount());
            assertEquals(1, runtime.positiveOwnerSlotReferentCount(referent));
            assertTrue(runtime.retainsPositiveOwnerSlotReferent(referent));

            slot.release();

            assertEquals(0, referent.semanticCaptureOwnerCount());
            assertEquals(0, referent.nativeCaptureOwnerCount());
            assertEquals(0, runtime.positiveOwnerSlotReferentCount(referent));
            assertFalse(runtime.retainsPositiveOwnerSlotReferent(referent));
        }
    }
}
