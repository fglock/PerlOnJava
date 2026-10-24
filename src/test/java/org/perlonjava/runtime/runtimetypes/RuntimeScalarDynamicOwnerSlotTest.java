package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RuntimeScalarDynamicOwnerSlotTest {
    @Test
    void dynamicRestoreReleasesDisplacedScalarSlotAfterLegacyFlagRetires() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray original = new RuntimeArray();
            RuntimeArray localized = new RuntimeArray();
            original.activateOwnerTracking();
            localized.activateOwnerTracking();
            RuntimeScalar scalar = new RuntimeScalar();
            scalar.set(original.createAnonymousReference());

            scalar.dynamicSaveState();
            scalar.set(localized.createAnonymousReference());
            scalar.refCountOwned = false;
            assertTrue(scalar.scalarValueOwnerSlot().isActive());

            scalar.dynamicRestoreState();

            assertEquals(Integer.MIN_VALUE, localized.refCount,
                    "restoring the saved value destroys its final localized referent");
            assertEquals(0, localized.activeOwnerCount());
            assertEquals(0, localized.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_VALUE));
            assertEquals(1, original.refCount,
                    "the saved scalar owner transfers back without changing its count");
            assertEquals(1, original.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_VALUE));
            assertTrue(scalar.ownsCountedReferent(original));

            scalar.undefine();
        }
    }

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
