package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class CountedScalarOwnerReplacementTest {
    @Test
    void replacementPairsTheNewCountWithTheStableScalarSlot() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash previous = new RuntimeHash();
            RuntimeHash replacement = new RuntimeHash();
            RuntimeScalar owner = new RuntimeScalar();
            owner.set(previous.createAnonymousReference());

            PerlOwnerSlot slot = owner.countedOwnerSlot();
            assertEquals(1, previous.refCount);
            assertEquals(1, previous.countedOwnerSlotCount());

            owner.set(replacement.createAnonymousReference());

            assertSame(slot, owner.countedOwnerSlot());
            assertSame(replacement, slot.referent());
            assertSame(owner, slot.countedScalarOwner());
            assertTrue(owner.refCountOwned);
            assertEquals(1, replacement.refCount);
            assertEquals(1, replacement.countedOwnerSlotCount());
            assertEquals(0, previous.countedOwnerSlotCount());
            assertEquals(Integer.MIN_VALUE, previous.refCount);
        }
    }

    @Test
    void replacingAReferenceWithTheSameReferentKeepsOneCountedEdge() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            RuntimeScalar owner = new RuntimeScalar();
            RuntimeScalar reference = referent.createAnonymousReference();
            owner.set(reference);
            PerlOwnerSlot slot = owner.countedOwnerSlot();

            owner.set(reference);

            assertSame(slot, owner.countedOwnerSlot());
            assertSame(referent, slot.referent());
            assertEquals(1, referent.refCount);
            assertEquals(1, referent.countedOwnerSlotCount());
            assertTrue(owner.refCountOwned);
        }
    }
}
