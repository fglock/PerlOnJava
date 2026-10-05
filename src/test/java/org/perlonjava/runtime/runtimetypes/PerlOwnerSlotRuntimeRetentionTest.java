package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class PerlOwnerSlotRuntimeRetentionTest {
    @Test
    void positiveOwnerSlotsRetainReferentsWithinTheirPerlRuntime() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash first = new RuntimeHash();
            RuntimeHash second = new RuntimeHash();
            PerlOwnerSlot firstSlot = new PerlOwnerSlot(PerlOwnerSlot.Kind.CLOSURE_PAD);
            PerlOwnerSlot secondSlot = new PerlOwnerSlot(PerlOwnerSlot.Kind.CLOSURE_PAD);

            assertFalse(runtime.retainsPositiveOwnerSlotReferent(first));
            firstSlot.transferTo(first);
            secondSlot.transferTo(first);
            assertEquals(2, runtime.positiveOwnerSlotReferentCount(first));
            assertTrue(runtime.retainsPositiveOwnerSlotReferent(first));

            firstSlot.release();
            assertEquals(1, runtime.positiveOwnerSlotReferentCount(first));
            assertTrue(runtime.retainsPositiveOwnerSlotReferent(first));

            secondSlot.transferTo(second);
            assertFalse(runtime.retainsPositiveOwnerSlotReferent(first));
            assertTrue(runtime.retainsPositiveOwnerSlotReferent(second));
            secondSlot.release();
            assertEquals(0, runtime.positiveOwnerSlotReferentCount(second));
        }
    }
}
