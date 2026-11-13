package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ClearedAggregateNestedPackageOwnerNoRootWalkTest {
    @Test
    void nestedPackageSlotPreservesWeakObserverWithoutRootSnapshot() {
        PerlRuntime runtime = new PerlRuntime();
        RuntimeArray packageRoot = new RuntimeArray();
        RuntimeHash referent = new RuntimeHash();
        RuntimeScalar strongOwner = referent.createAnonymousReference();
        RuntimeScalar weakObserver = referent.createAnonymousReference();
        String globalName = "ClearedAggregateNestedPackageOwnerNoRootWalkTest::root";

        try (PerlRuntime.Binding ignored = runtime.bind()) {
            boolean previousMortalListState = MortalList.isActive();
            GlobalVariable.globalArrays.put(globalName, packageRoot);
            packageRoot.elements.add(strongOwner);
            referent.refCount = -1;
            weakObserver.value = referent;
            weakObserver.type = RuntimeScalarType.HASHREFERENCE;
            MortalList.setActive(true);
            try {
                strongOwner.refreshUntrackedReferenceOwner();
                WeakRefRegistry.weaken(weakObserver);
                assertTrue(WeakRefRegistry.isweak(weakObserver));
                assertEquals(1, referent.ownerSlotCount(
                        PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));

                // Model the scope-exit count transition after the owner cell
                // has been marked cleared; the package element slot is still
                // the current strong edge.
                referent.clearedOwnedAggregateElement = true;
                referent.refCount = 0;
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    MortalList.finalizeClearedAggregateOwnerAfterScopeExit(referent);

                    assertTrue(WeakRefRegistry.isweak(weakObserver),
                            "the nested package scalar owner keeps its weak observer live");
                    assertEquals(0, measurement.stats().rootQueries);
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                    assertEquals(0, measurement.stats().externalRootSnapshotsBuilt);
                }

                assertEquals(0, referent.refCount,
                        "the exact package owner remains authoritative despite the stale zero count");
                strongOwner.undefine();
                assertFalse(WeakRefRegistry.isweak(weakObserver));
                assertEquals(Integer.MIN_VALUE, referent.refCount);
            } finally {
                if (WeakRefRegistry.isweak(weakObserver)) {
                    WeakRefRegistry.unweaken(weakObserver);
                }
                GlobalVariable.globalArrays.remove(globalName);
                MortalList.invalidateAllRootSnapshots();
                MortalList.setActive(previousMortalListState);
            }
        }
    }
}
