package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class PerlOwnerSlotTest {
    @Test
    void acquireTransferAndReleasePreserveOneSlotIdentity() {
        RuntimeHash first = new RuntimeHash();
        RuntimeHash second = new RuntimeHash();
        PerlOwnerSlot slot = new PerlOwnerSlot(PerlOwnerSlot.Kind.CLOSURE_PAD);

        assertFalse(slot.isActive());
        slot.transferTo(first);
        assertTrue(slot.isActive());
        assertSame(first, slot.referent());
        assertEquals(1, first.semanticCaptureOwnerCount());

        slot.transferTo(second);
        assertEquals(0, first.semanticCaptureOwnerCount());
        assertEquals(1, second.semanticCaptureOwnerCount());
        assertSame(second, slot.referent());

        slot.release();
        slot.release();
        assertFalse(slot.isActive());
        assertEquals(0, second.semanticCaptureOwnerCount());
    }
}
