package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RuntimeScalarCountedOwnerRepairTest {
    @Test
    void survivingArraySlotRepairsNonPackageWeakReferentWithoutRootWalk() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash target = new RuntimeHash();
            target.blessId = NameNormalizer.getBlessId("RuntimeScalarCountedOwnerRepairTest::Target");
            RuntimeArray arrayOwner = new RuntimeArray();
            arrayOwner.push(target.createAnonymousReference());

            RuntimeScalar scalarOwner = new RuntimeScalar();
            scalarOwner.set(target.createAnonymousReference());
            RuntimeScalar observer = target.createAnonymousReference();
            WeakRefRegistry.weaken(observer);

            try {
                assertFalse(target.storedInPackageGlobal);
                assertFalse(target.localBindingExists);
                assertEquals(1, target.ownerSlotCount(PerlOwnerSlot.Kind.ARRAY_ELEMENT));
                assertEquals(1, target.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_CELL));
                assertTrue(WeakRefRegistry.isweak(observer));

                // Model a transient selective-count undercount while retaining
                // both explicit owner identities.
                target.refCount = 1;
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    scalarOwner.set(new RuntimeScalar());

                    assertEquals(1, target.refCount);
                    assertEquals(1, target.countedOwnerSlotCount());
                    assertEquals(1, target.ownerSlotCount(PerlOwnerSlot.Kind.ARRAY_ELEMENT));
                    assertTrue(WeakRefRegistry.isweak(observer));
                    assertEquals(0, measurement.stats().rootQueries,
                            "the surviving array slot repairs the transient zero");
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                }

                arrayOwner.elements.get(0).set(new RuntimeScalar());
                assertFalse(WeakRefRegistry.isweak(observer),
                        "releasing the final counted slot clears the weak observer");
            } finally {
                if (WeakRefRegistry.isweak(observer)) WeakRefRegistry.unweaken(observer);
            }
        }
    }
}
