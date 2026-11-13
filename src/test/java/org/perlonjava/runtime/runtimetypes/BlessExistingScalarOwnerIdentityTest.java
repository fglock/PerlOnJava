package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.operators.ReferenceOperators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class BlessExistingScalarOwnerIdentityTest {
    @Test
    void reblessingAnUntrackedReferentRecordsBothScalarAndMortalOwners() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            MortalList.setActive(true);
            RuntimeHash referent = new RuntimeHash();
            referent.blessId = NameNormalizer.getBlessId("Issue1649::Original");
            assertTrue(referent.blessId != 0, "the setup is an already-blessed referent");
            assertEquals(-1, referent.refCount, "the existing referent is untracked");
            RuntimeScalar scalarOwner = new RuntimeScalar();
            scalarOwner.type = RuntimeScalarType.REFERENCE;
            scalarOwner.value = referent;
            MyVarCleanupStack.register(scalarOwner);

            try {
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    ReferenceOperators.bless(scalarOwner,
                            new RuntimeScalar("Issue1649::Reblessed"));

                    assertEquals("Issue1649::Reblessed",
                            NameNormalizer.getBlessStr(referent.blessId));
                    assertEquals(2, referent.refCount,
                            "the existing scalar slot and bless temporary each own one count");
                    assertEquals(2, referent.countedOwnerSlotCount(),
                            "both count producers must install one owner identity");
                    PerlOwnerSlot scalarSlot = scalarOwner.countedOwnerSlot();
                    assertNotNull(scalarSlot);
                    assertTrue(scalarOwner.isCurrentCountedScalarOwner(referent, scalarSlot));

                    MortalList.flush();

                    assertEquals(1, referent.refCount,
                            "draining the bless temporary leaves the scalar slot's count");
                    assertEquals(1, referent.countedOwnerSlotCount());
                    assertTrue(scalarOwner.isCurrentCountedScalarOwner(referent, scalarSlot));
                    assertEquals(0, measurement.stats().rootQueries);
                }
            } finally {
                if (scalarOwner.refCountOwned) scalarOwner.set(new RuntimeScalar());
                MyVarCleanupStack.unregister(scalarOwner);
            }
        }
    }
}
