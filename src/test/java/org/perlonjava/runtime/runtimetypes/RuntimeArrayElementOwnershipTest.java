package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@Tag("unit")
class RuntimeArrayElementOwnershipTest {
    @Test
    void elementSlotsFollowStructuralAndScalarCellMutations() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray first = new RuntimeArray();
            RuntimeArray second = new RuntimeArray();
            RuntimeArray third = new RuntimeArray();
            RuntimeArray array = new RuntimeArray();
            RuntimeScalar cell = first.createReference();
            array.elements.add(cell);
            array.elementsOwned = true;
            array.reconcileArrayElementOwnerSlots();

            PerlOwnerSlot slot = array.arrayElementOwnerSlot(0);
            assertEquals(1, first.arraySlotOwnerCount());
            assertSame(first, slot.referent());

            cell.set(second.createReference());
            assertEquals(0, first.arraySlotOwnerCount());
            assertEquals(1, second.arraySlotOwnerCount());
            assertSame(slot, array.arrayElementOwnerSlot(0));
            assertSame(second, slot.referent());

            array.elements.add(0, third.createReference());
            assertEquals(1, third.arraySlotOwnerCount());
            assertSame(slot, array.arrayElementOwnerSlot(1),
                    "insertion moves the existing element slot without replacing its identity");

            array.elements.remove(0);
            assertEquals(0, third.arraySlotOwnerCount());
            assertSame(slot, array.arrayElementOwnerSlot(0));

            array.elements.set(0, third.createReference());
            assertEquals(0, second.arraySlotOwnerCount());
            assertEquals(1, third.arraySlotOwnerCount());
            assertSame(slot, array.arrayElementOwnerSlot(0));
            assertSame(third, slot.referent());

            array.elements.clear();
            assertEquals(0, third.arraySlotOwnerCount());
            assertNull(array.arrayElementOwnerSlot(0));
        }
    }

    @Test
    void aliasedElementsDoNotOwnReferentsUnlessExplicitlyTransferred() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray referent = new RuntimeArray();
            RuntimeArray array = new RuntimeArray();
            RuntimeScalar cell = referent.createReference();
            array.elements.add(cell);
            array.elementsAliased = true;
            array.elementsOwned = false;

            assertEquals(0, referent.arraySlotOwnerCount());

            array.markOwnedAliasElement(cell);
            assertEquals(1, referent.arraySlotOwnerCount());
            assertSame(referent, array.arrayElementOwnerSlot(0).referent());

            array.forgetOwnedAliasElement(cell);
            assertEquals(0, referent.arraySlotOwnerCount());
            assertNull(array.arrayElementOwnerSlot(0));
        }
    }

    @Test
    void rangeRemovalReleasesOnlyRemovedElementSlots() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray first = new RuntimeArray();
            RuntimeArray second = new RuntimeArray();
            RuntimeArray array = new RuntimeArray();
            array.elements.add(first.createReference());
            array.elements.add(second.createReference());
            array.elementsOwned = true;
            array.reconcileArrayElementOwnerSlots();

            PerlOwnerSlot retained = array.arrayElementOwnerSlot(1);
            array.elements.subList(0, 1).clear();

            assertEquals(0, first.arraySlotOwnerCount());
            assertEquals(1, second.arraySlotOwnerCount());
            assertSame(retained, array.arrayElementOwnerSlot(0));
        }
    }
}
