package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RuntimeProxyDynamicOwnerSlotTest {
    @Test
    void hashRestoreReleasesNewEntryScalarOwnerSlotAfterLegacyFlagRetires() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash hash = new RuntimeHash();
            RuntimeArray referent = new RuntimeArray();
            RuntimeHashProxyEntry proxy = new RuntimeHashProxyEntry(hash, "temporary");
            referent.activateOwnerTracking();

            proxy.dynamicSaveState();
            RuntimeScalar cell = hash.elements.get("temporary");
            proxy.set(referent.createAnonymousReference());
            cell.refCountOwned = false;
            assertTrue(cell.scalarValueOwnerSlot().isActive());

            proxy.dynamicRestoreState();

            assertFalse(hash.elements.containsKey("temporary"));
            assertEquals(Integer.MIN_VALUE, referent.refCount);
            assertEquals(0, referent.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_VALUE));
            assertEquals(0, referent.ownerSlotCount(PerlOwnerSlot.Kind.HASH_ENTRY));
            assertEquals(0, referent.activeOwnerCount());
        }
    }

    @Test
    void arrayRestoreReleasesNewElementScalarOwnerSlotAfterLegacyFlagRetires() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray array = new RuntimeArray();
            RuntimeArray referent = new RuntimeArray();
            RuntimeArrayProxyEntry proxy = new RuntimeArrayProxyEntry(array, 0);
            referent.activateOwnerTracking();

            proxy.dynamicSaveState();
            RuntimeScalar cell = array.elements.get(0);
            proxy.set(referent.createAnonymousReference());
            cell.refCountOwned = false;
            assertTrue(cell.scalarValueOwnerSlot().isActive());

            proxy.dynamicRestoreState();

            assertTrue(array.elements.isEmpty());
            assertEquals(Integer.MIN_VALUE, referent.refCount);
            assertEquals(0, referent.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_VALUE));
            assertEquals(0, referent.ownerSlotCount(PerlOwnerSlot.Kind.ARRAY_ELEMENT));
            assertEquals(0, referent.activeOwnerCount());
        }
    }
}
