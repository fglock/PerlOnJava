package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ClearedAggregateOwnerIdentityFinalizationTest {
    @Test
    void scopeExitUsesPackageOwnerIdentityWithoutExternalRootWalk() {
        PerlRuntime runtime = new PerlRuntime();
        RuntimeHash packageRoot = new RuntimeHash();
        RuntimeScalar weakPackageRoot = packageRoot.createAnonymousReference();
        String globalName =
                "ClearedAggregateOwnerIdentityFinalizationTest::packageRoot";

        try (PerlRuntime.Binding ignored = runtime.bind()) {
            boolean previousMortalListState = MortalList.isActive();
            MortalList.setActive(true);
            try {
                GlobalVariable.globalHashes.put(globalName, packageRoot);
                assertEquals(1, packageRoot.ownerSlotCount(
                        PerlOwnerSlot.Kind.PACKAGE_AGGREGATE_SLOT));
                packageRoot.clearedOwnedAggregateElement = true;
                WeakRefRegistry.weaken(weakPackageRoot);
                packageRoot.refCount = 0;

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    MortalList.finalizeClearedAggregateOwnerAfterScopeExit(packageRoot);

                    assertEquals(0, measurement.stats().rootQueries,
                            "the package aggregate owner identity replaces the root walk");
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                    assertTrue(WeakRefRegistry.isweak(weakPackageRoot),
                            "the package slot keeps the aggregate alive");
                }

            } finally {
                GlobalVariable.globalHashes.remove(globalName);
                WeakRefRegistry.clearWeakRefsTo(packageRoot);
                MortalList.invalidateAllRootSnapshots();
                MortalList.setActive(previousMortalListState);
            }
        }
    }
}
