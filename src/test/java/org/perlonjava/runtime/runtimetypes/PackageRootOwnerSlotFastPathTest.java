package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class PackageRootOwnerSlotFastPathTest {
    @Test
    void survivingPackageHashSlotRepairsTransientZeroWithoutRootWalk() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            String leftName = "PackageRootOwnerSlotFastPath::left";
            String rightName = "PackageRootOwnerSlotFastPath::right";
            RuntimeHash left = new RuntimeHash();
            RuntimeHash right = new RuntimeHash();
            GlobalVariable.aliasGlobalHash(leftName, left);
            GlobalVariable.aliasGlobalHash(rightName, right);

            RuntimeHash target = new RuntimeHash();
            target.blessId = NameNormalizer.getBlessId("PackageRootOwnerSlotFastPath::Target");
            left.put("target", target.createAnonymousReference());
            right.put("target", target.createAnonymousReference());
            RuntimeScalar observer = new RuntimeScalar();
            observer.set(target.createAnonymousReference());
            WeakRefRegistry.weaken(observer);

            try {
                assertTrue(target.storedInPackageGlobal);
                assertEquals(2, target.countedOwnerSlotCount());
                assertTrue(WeakRefRegistry.isweak(observer));

                // Model the selective count's transient undercount. The two
                // package hash slots remain the authoritative owner ledger.
                target.refCount = 1;
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    left.elements.get("target").set(new RuntimeScalar());

                    assertEquals(1, target.refCount);
                    assertEquals(1, target.countedOwnerSlotCount());
                    assertTrue(WeakRefRegistry.isweak(observer));
                    assertFalse(target.destroyFired);
                    assertEquals(0, measurement.stats().rootQueries,
                            "the remaining counted slot repairs the transient zero");
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                }

                right.elements.get("target").set(new RuntimeScalar());
                assertFalse(WeakRefRegistry.isweak(observer),
                        "the final counted owner still releases the weak observer");
            } finally {
                if (WeakRefRegistry.isweak(observer)) WeakRefRegistry.unweaken(observer);
                GlobalVariable.globalHashes.remove(leftName);
                GlobalVariable.globalHashes.remove(rightName);
            }
        }
    }
}
