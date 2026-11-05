package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class CountedScalarOwnerAcquisitionTest {
    @Test
    void containerAcquisitionPairsTheCountWithItsCurrentScalarSlot() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            RuntimeScalar owner = referent.createAnonymousReference();

            RuntimeScalar.incrementRefCountForContainerStore(owner);

            PerlOwnerSlot slot = owner.countedOwnerSlot();
            assertTrue(owner.refCountOwned);
            assertEquals(1, referent.refCount);
            assertEquals(1, referent.countedOwnerSlotCount());
            assertEquals(1, referent.activeOwnerCount());
            assertSame(referent, slot.referent());
            assertSame(owner, slot.countedScalarOwner());
            assertEquals(PerlOwnerSlot.Kind.SCALAR_CELL, slot.kind());
        }
    }

    @Test
    void unweakenReacquiresTheScalarSlotAndItsCountTogether() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            RuntimeScalar firstOwner = new RuntimeScalar();
            RuntimeScalar weakOwner = new RuntimeScalar();
            firstOwner.set(referent.createAnonymousReference());
            weakOwner.set(referent.createAnonymousReference());

            assertEquals(2, referent.refCount);
            assertEquals(2, referent.countedOwnerSlotCount());

            WeakRefRegistry.weaken(weakOwner);
            assertEquals(1, referent.refCount);
            assertEquals(1, referent.countedOwnerSlotCount());
            assertFalse(weakOwner.refCountOwned);

            WeakRefRegistry.unweaken(weakOwner);
            assertEquals(2, referent.refCount);
            assertEquals(2, referent.countedOwnerSlotCount());
            assertEquals(2, referent.activeOwnerCount());
            assertTrue(weakOwner.refCountOwned);
            assertSame(referent, weakOwner.countedOwnerSlot().referent());
        }
    }
}
