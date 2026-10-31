package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void finalCellCleanupReleasesNestedUntrackedOwnerDespiteLegacyCount() {
        PerlRuntime runtime = new PerlRuntime();
        RuntimeHash referent = new RuntimeHash();
        RuntimeScalar weakObserver = referenceTo(referent);
        RuntimeArray container = new RuntimeArray();
        RuntimeScalar nestedCell = referenceTo(referent);
        container.elements.add(nestedCell);
        nestedCell.markContainerOwner(container);

        try (PerlRuntime.Binding ignored = runtime.bind()) {
            boolean previousMortalListState = MortalList.isActive();
            int mark = MyVarCleanupStack.pushMark();
            MyVarCleanupStack.register(container);
            MortalList.setActive(true);
            try {
                referent.refCount = -1;
                nestedCell.refreshUntrackedReferenceOwner();
                PerlOwnerSlot nestedOwner = nestedCell.untrackedReferenceOwnerSlot();
                assertTrue(nestedOwner != null && nestedOwner.isActive());

                WeakRefRegistry.weaken(weakObserver);
                assertTrue(WeakRefRegistry.isweak(weakObserver));

                // Model a positive compatibility count left behind after the
                // nested scalar's final untracked owner identity is retired.
                referent.refCount = 5;
                referent.clearedOwnedAggregateElement = true;
                nestedCell.set(new RuntimeScalar());
                assertFalse(nestedOwner.isActive());
                assertEquals(0, referent.ownerSlotCount(
                        PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));

                MortalList.finalizeClearedAggregateOwnerAfterScopeExit(referent);

                assertFalse(WeakRefRegistry.isweak(weakObserver),
                        "legacy count alone must not keep a cleared aggregate alive");
                assertEquals(Integer.MIN_VALUE, referent.refCount);
            } finally {
                if (nestedCell.untrackedReferenceOwnerSlot() != null
                        && nestedCell.untrackedReferenceOwnerSlot().isActive()) {
                    nestedCell.set(new RuntimeScalar());
                }
                if (WeakRefRegistry.isweak(weakObserver)) {
                    WeakRefRegistry.unweaken(weakObserver);
                }
                WeakRefRegistry.clearWeakRefsTo(referent);
                MortalList.scopeExitCleanupArray(container);
                MyVarCleanupStack.unregister(container);
                MyVarCleanupStack.popMark(mark);
                MortalList.invalidateAllRootSnapshots();
                MortalList.setActive(previousMortalListState);
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
