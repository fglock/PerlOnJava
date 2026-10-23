package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class CountedScalarOwnerSnapshotTest {
    @Test
    void includesDirectOwnerCreatedBeforeLegacyTracking() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray referent = new RuntimeArray();
            RuntimeScalar owner = new RuntimeScalar();
            owner.set(referent.createAnonymousReference());
            MyVarCleanupStack.register(owner);

            assertEquals(0, referent.activeOwnerCount(),
                    "the direct owner predates the legacy registry");
            assertEquals(1, referent.countedScalarOwnerCount(),
                    "the explicit scalar slot still identifies its owner");

            MyVarCleanupStack.unregister(owner);
            MortalList.deferDecrementIfTracked(owner);
            MortalList.flush();
        }
    }

    @Test
    void deduplicatesOwnerPresentInLegacyRegistryAndExplicitSlot() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray referent = new RuntimeArray();
            referent.activateOwnerTracking();
            RuntimeScalar owner = new RuntimeScalar();
            owner.set(referent.createAnonymousReference());
            MyVarCleanupStack.register(owner);

            assertEquals(1, referent.activeOwnerCount());
            assertEquals(1, referent.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_VALUE));
            assertEquals(1, referent.countedScalarOwnerCount(),
                    "the union counts one scalar cell, not both ledger entries");

            MyVarCleanupStack.unregister(owner);
            MortalList.deferDecrementIfTracked(owner);
            MortalList.flush();
        }
    }

    @Test
    void retainsExplicitOwnerWhenLegacyFlagRetires() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray referent = new RuntimeArray();
            referent.activateOwnerTracking();
            RuntimeScalar owner = new RuntimeScalar();
            owner.set(referent.createAnonymousReference());
            MyVarCleanupStack.register(owner);

            assertEquals(1, referent.countedScalarOwnerCount());
            owner.refCountOwned = false;

            assertEquals(0, referent.activeOwnerCount(),
                    "the legacy registry retires the owner with its Boolean flag");
            assertEquals(1, referent.countedScalarOwnerCount(),
                    "the active scalar-value slot remains an explicit owner");

            owner.setRefCountOwned(true);
            MyVarCleanupStack.unregister(owner);
            MortalList.deferDecrementIfTracked(owner);
            MortalList.flush();
            assertEquals(0, referent.countedScalarOwnerCount(),
                    "releasing the explicit slot removes the counted owner");
        }
    }
}
