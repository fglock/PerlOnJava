package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class GlobalRuntimeScalarDynamicOwnerSlotTest {
    @Test
    void globalRestoreReleasesDisplacedScalarSlotAfterLegacyFlagRetires() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            String name = "DynamicOwnerSlot::global";
            RuntimeArray originalReferent = new RuntimeArray();
            RuntimeArray localizedReferent = new RuntimeArray();
            originalReferent.activateOwnerTracking();
            localizedReferent.activateOwnerTracking();
            RuntimeScalar original = GlobalVariable.getGlobalVariable(name);
            original.set(originalReferent.createAnonymousReference());

            GlobalRuntimeScalar localizer = new GlobalRuntimeScalar(name);
            localizer.dynamicSaveState();
            RuntimeScalar localized = GlobalVariable.getGlobalVariable(name);
            localized.set(localizedReferent.createAnonymousReference());
            localized.refCountOwned = false;
            assertTrue(localized.scalarValueOwnerSlot().isActive());

            localizer.dynamicRestoreState();

            assertSame(original, GlobalVariable.getGlobalVariable(name));
            assertEquals(Integer.MIN_VALUE, localizedReferent.refCount,
                    "restoring the global destroys its final localized referent");
            assertEquals(0, localizedReferent.activeOwnerCount());
            assertEquals(0, localizedReferent.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_VALUE));
            assertEquals(1, originalReferent.refCount);
            assertEquals(1, originalReferent.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_VALUE));
            assertTrue(original.ownsCountedReferent(originalReferent));

            original.undefine();
        }
    }
}
