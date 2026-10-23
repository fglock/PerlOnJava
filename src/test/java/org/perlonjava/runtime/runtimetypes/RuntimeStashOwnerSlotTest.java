package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RuntimeStashOwnerSlotTest {
    @Test
    void deletingGlobalScalarReleasesExplicitSlotAfterLegacyFlagRetires() {
        PerlRuntime runtime = new PerlRuntime();
        String name = "RuntimeStashOwnerSlotTest::owner";
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray referent = new RuntimeArray();
            RuntimeScalar owner = new RuntimeScalar();
            owner.set(referent.createAnonymousReference());
            owner.refCountOwned = false;
            assertTrue(owner.scalarValueOwnerSlot().isActive());
            GlobalVariable.scalarSlots().put(name, owner);

            try {
                assertEquals(1, referent.refCount);
                new RuntimeStash("RuntimeStashOwnerSlotTest::").delete("owner");

                assertEquals(Integer.MIN_VALUE, referent.refCount);
                assertFalse(owner.scalarValueOwnerSlot().isActive());
            } finally {
                GlobalVariable.scalarSlots().remove(name);
            }
        }
    }
}
