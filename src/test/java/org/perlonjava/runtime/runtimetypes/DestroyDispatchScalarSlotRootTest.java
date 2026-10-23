package org.perlonjava.runtime.runtimetypes;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class DestroyDispatchScalarSlotRootTest {
    @Test
    void packageRootedScalarSlotCountsWhenLegacyOwnerEntryIsMissing() {
        PerlRuntime runtime = new PerlRuntime();
        String globalName = "DestroyDispatchScalarSlotRootTest::owner";
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray referent = new RuntimeArray();
            referent.activateOwnerTracking();
            RuntimeScalar owner = new RuntimeScalar();
            owner.set(referent.createReference());
            RuntimeHash globalHash = new RuntimeHash();
            globalHash.isGlobalPackageHash = true;
            GlobalVariable.globalHashes.put(globalName, globalHash);
            globalHash.elements.put("owner", owner);
            referent.releaseActiveOwner(owner);

            Set<RuntimeScalar> noScalarOwnersAtStart =
                    Collections.newSetFromMap(new IdentityHashMap<>());
            Set<PerlOwnerSlot> noOwnerSlotsAtStart =
                    Collections.newSetFromMap(new IdentityHashMap<>());
            try {
                assertEquals(0, referent.activeOwnerCount(),
                        "the fixture removes the compatibility-ledger entry");
                assertEquals(1, referent.countedScalarOwnerCount(),
                        "the direct scalar slot remains authoritative");
                assertTrue(DestroyDispatch.hasEscapedPerlOwner(
                                referent, true, noScalarOwnersAtStart, noOwnerSlotsAtStart),
                        "the rooted scalar slot is visible to destructor owner-delta checks");
            } finally {
                globalHash.elements.clear();
                GlobalVariable.globalHashes.remove(globalName);
                owner.set(new RuntimeScalar());
            }
        }
    }
}
