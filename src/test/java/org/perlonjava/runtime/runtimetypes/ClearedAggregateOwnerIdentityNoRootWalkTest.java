package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ClearedAggregateOwnerIdentityNoRootWalkTest {
    @Test
    void finalUntrackedCellReleaseIgnoresStaleCountWithoutWalkingRoots() {
        PerlRuntime runtime = new PerlRuntime();
        RuntimeHash referent = new RuntimeHash();
        RuntimeScalar weakObserver = referenceTo(referent);
        RuntimeArray lexicalContainer = new RuntimeArray();
        RuntimeScalar nestedCell = referenceTo(referent);
        lexicalContainer.elements.add(nestedCell);
        nestedCell.markContainerOwner(lexicalContainer);

        try (PerlRuntime.Binding ignored = runtime.bind()) {
            boolean previousMortalListState = MortalList.isActive();
            int mark = MyVarCleanupStack.pushMark();
            MyVarCleanupStack.register(lexicalContainer);
            MortalList.setActive(true);
            try {
                referent.refCount = -1;
                nestedCell.refreshUntrackedReferenceOwner();
                PerlOwnerSlot nestedOwner = nestedCell.untrackedReferenceOwnerSlot();
                assertTrue(nestedOwner != null && nestedOwner.isActive());

                WeakRefRegistry.weaken(weakObserver);
                assertTrue(WeakRefRegistry.isweak(weakObserver));

                // Retire the exact owner while leaving a positive compatibility
                // count behind, as can happen during async aggregate cleanup.
                referent.refCount = 5;
                referent.clearedOwnedAggregateElement = true;
                nestedCell.set(new RuntimeScalar());
                assertFalse(nestedOwner.isActive());

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    MortalList.finalizeClearedAggregateOwnerAfterScopeExit(referent);

                    assertEquals(RuntimeScalarType.UNDEF, weakObserver.type,
                            "a stale count cannot preserve the cleared referent");
                    assertEquals(Integer.MIN_VALUE, referent.refCount);
                    assertEquals(0, measurement.stats().rootQueries);
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                    assertEquals(0, measurement.stats().externalRootSnapshotsBuilt);
                }
            } finally {
                if (nestedCell.untrackedReferenceOwnerSlot() != null
                        && nestedCell.untrackedReferenceOwnerSlot().isActive()) {
                    nestedCell.set(new RuntimeScalar());
                }
                if (WeakRefRegistry.isweak(weakObserver)) {
                    WeakRefRegistry.unweaken(weakObserver);
                }
                WeakRefRegistry.clearWeakRefsTo(referent);
                MortalList.scopeExitCleanupArray(lexicalContainer);
                MyVarCleanupStack.unregister(lexicalContainer);
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
