package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RuntimeScalarDynamicOwnerSlotTest {
    @Test
    void dynamicSaveAndRestorePreservesOwnerSlotAfterLegacyFlagRetires() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray referent = new RuntimeArray();
            RuntimeScalar scalar = new RuntimeScalar();
            scalar.set(referent.createAnonymousReference());
            scalar.refCountOwned = false;
            assertTrue(scalar.scalarValueOwnerSlot().isActive());
            int originalRefCount = referent.refCount;

            scalar.dynamicSaveState();
            scalar.dynamicRestoreState();

            assertEquals(originalRefCount, referent.refCount);
            assertTrue(scalar.ownsCountedReferent(referent));
            assertTrue(scalar.scalarValueOwnerSlot().isActive());
        }
    }
}
