package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class NestedUntrackedOwnerFinalCellCleanupTest {
    @Test
    void finalCellCleanupReleasesItsSlotWhileAnotherCountedOwnerKeepsReferentAlive() {
        PerlRuntime runtime = new PerlRuntime();
        RuntimeHash referent = new RuntimeHash();
        RuntimeScalar countedOwner = new RuntimeScalar();
        RuntimeScalar weakObserver = null;
        RuntimeArray packageRoot = new RuntimeArray();
        String globalName =
                "NestedUntrackedOwnerFinalCellCleanupTest::packageRoot";

        try (PerlRuntime.Binding ignored = runtime.bind()) {
            boolean previousMortalListState = MortalList.isActive();
            MortalList.setActive(true);
            try {
                countedOwner.set(referent.createAnonymousReference());
                weakObserver = referent.createAnonymousReference();
                WeakRefRegistry.weaken(weakObserver);
                assertEquals(1, referent.refCount,
                        "the counted scalar is an independent live owner");

                GlobalVariable.globalArrays.put(globalName, packageRoot);
                RuntimeScalar nestedCell = referenceTo(referent);
                packageRoot.elements.add(nestedCell);
                nestedCell.markContainerOwner(packageRoot);
                nestedCell.markPrepopulatedPackageAggregateOwner(packageRoot);
                nestedCell.refreshUntrackedReferenceOwner();

                PerlOwnerSlot nestedOwner = nestedCell.untrackedReferenceOwnerSlot();
                assertTrue(nestedOwner != null && nestedOwner.isActive());
                assertEquals(1, referent.ownerSlotCount(
                        PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    GlobalVariable.globalArrays.remove(globalName);
                    MortalList.scopeExitCleanupArray(packageRoot);

                    assertFalse(nestedOwner.isActive(),
                            "final cell cleanup retires this exact nested edge");
                    assertEquals(0, referent.ownerSlotCount(
                            PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));
                    assertEquals(1, referent.refCount,
                            "releasing the nested edge must preserve the separate counted owner");
                    assertEquals(1, referent.countedOwnerSlotCount());
                    assertTrue(WeakRefRegistry.isweak(weakObserver),
                            "the remaining counted owner keeps the weak observer live");
                    assertEquals(0, measurement.stats().rootQueries);
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                }

                countedOwner.undefine();
                assertFalse(WeakRefRegistry.isweak(weakObserver),
                        "the final counted owner clears the observer when it leaves");
                assertEquals(Integer.MIN_VALUE, referent.refCount);
            } finally {
                if (weakObserver != null && WeakRefRegistry.isweak(weakObserver)) {
                    WeakRefRegistry.unweaken(weakObserver);
                }
                GlobalVariable.globalArrays.remove(globalName);
                MortalList.scopeExitCleanupArray(packageRoot);
                countedOwner.undefine();
                MortalList.setActive(previousMortalListState);
                MortalList.invalidateAllRootSnapshots();
            }
        }
    }

    private static RuntimeScalar referenceTo(RuntimeBase referent) {
        RuntimeScalar scalar = new RuntimeScalar();
        scalar.type = RuntimeScalarType.HASHREFERENCE;
        scalar.value = referent;
        return scalar;
    }
}
