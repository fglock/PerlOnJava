package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class PerlOwnerSlotLegacyBridgeTest {
    @Test
    void queuedCaptureReleaseRetainsSlotProvenanceUntilDrain() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            referent.refCount = 0;
            referent.localBindingExists = true;
            PerlOwnerSlot slot = new PerlOwnerSlot(PerlOwnerSlot.Kind.CLOSURE_PAD);

            slot.acquireLegacyCapture(referent);
            assertEquals(1, referent.refCount);
            assertEquals(1, slot.legacyCaptureCount());

            MortalList.pushMark();
            PerlOwnerSlot.PendingRelease release = slot.deferLegacyCaptureRelease(referent);
            slot.release();
            assertNotNull(release);
            assertEquals(0, slot.legacyCaptureCount());
            assertEquals(1, referent.refCount);
            assertEquals(slot.identity(), release.ownerSlotIdentity());
            assertEquals(1, release.sequence());
            assertEquals(PerlOwnerSlot.Kind.CLOSURE_PAD, release.kind());
            assertFalse(release.isCompleted());

            MortalList.flushAboveMark();

            assertEquals(0, referent.refCount);
            assertTrue(release.isCompleted());
            MortalList.popMark();
        }
    }
}
