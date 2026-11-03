package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class CountedOwnerSlotLedgerTest {
    @Test
    void countedOwnersAreVisibleBeforeFirstWeaken() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            RuntimeScalar firstOwner = new RuntimeScalar();
            RuntimeScalar secondOwner = new RuntimeScalar();
            firstOwner.set(referent.createAnonymousReference());
            secondOwner.set(referent.createAnonymousReference());

            assertEquals(2, referent.countedOwnerSlotCount());
            assertEquals(2, referent.activeOwnerCount());
            assertTrue(referent.activeOwnerSnapshot().contains(firstOwner));
            assertTrue(referent.activeOwnerSnapshot().contains(secondOwner));
        }
    }
}
