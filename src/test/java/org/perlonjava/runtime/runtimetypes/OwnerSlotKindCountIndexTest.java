package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class OwnerSlotKindCountIndexTest {
    @Test
    void everyOwnerKindCountTracksAddReleaseAndReclassification() {
        RuntimeHash referent = new RuntimeHash();

        for (PerlOwnerSlot.Kind kind : PerlOwnerSlot.Kind.values()) {
            PerlOwnerSlot first = new PerlOwnerSlot(kind);
            PerlOwnerSlot second = new PerlOwnerSlot(kind);

            first.transferTo(referent);
            second.transferTo(referent);
            assertEquals(2, referent.ownerSlotCount(kind), "two " + kind + " slots");

            first.release();
            assertEquals(1, referent.ownerSlotCount(kind), "one " + kind + " slot remains");
            second.release();
            assertEquals(0, referent.ownerSlotCount(kind), "all " + kind + " slots released");
        }

        PerlOwnerSlot reclassified = new PerlOwnerSlot(PerlOwnerSlot.Kind.SCALAR_CELL);
        reclassified.transferTo(referent);
        referent.reclassifyOwnerSlotKind(
                reclassified, PerlOwnerSlot.Kind.SCALAR_CELL, PerlOwnerSlot.Kind.HASH_ELEMENT);

        assertEquals(0, referent.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_CELL));
        assertEquals(1, referent.ownerSlotCount(PerlOwnerSlot.Kind.HASH_ELEMENT));

        reclassified.release();
        assertEquals(0, referent.ownerSlotCount(PerlOwnerSlot.Kind.HASH_ELEMENT));
    }
}
