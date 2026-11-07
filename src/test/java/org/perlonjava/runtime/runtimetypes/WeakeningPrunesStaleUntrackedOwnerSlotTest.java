package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Tag("unit")
class WeakeningPrunesStaleUntrackedOwnerSlotTest {
    @Test
    void staleUntrackedCellDoesNotPreserveWeakReferentAfterFinalCountedOwnerLeaves() {
        PerlRuntime runtime = new PerlRuntime();
        RuntimeArray lexicalOwner = new RuntimeArray();
        RuntimeArray target = new RuntimeArray();
        RuntimeScalar staleCell = referenceTo(target);
        RuntimeScalar weakObserver = referenceTo(target);

        try (PerlRuntime.Binding ignored = runtime.bind()) {
            boolean previousMortalListState = MortalList.isActive();
            MortalList.setActive(true);
            lexicalOwner.localBindingExists = true;
            lexicalOwner.elements.add(staleCell);
            PerlOwnerSlot staleOwner = staleCell.untrackedReferenceOwnerSlot();
            assertNotNull(staleOwner);
            target.refCount = 1;
            weakObserver.refCountOwned = true;

            try {
                lexicalOwner.localBindingExists = false;
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    WeakRefRegistry.weaken(weakObserver);

                    assertFalse(staleOwner.isActive(),
                            "weakening must retire an untracked slot with no live durable cell");
                    assertFalse(WeakRefRegistry.isweak(weakObserver),
                            "the stale slot must not strand the final weak observer");
                    assertEquals(RuntimeScalarType.UNDEF, weakObserver.type);
                    assertEquals(Integer.MIN_VALUE, target.refCount);
                    assertEquals(0, measurement.stats().rootQueries);
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                }
            } finally {
                if (WeakRefRegistry.isweak(weakObserver)) {
                    WeakRefRegistry.unweaken(weakObserver);
                }
                lexicalOwner.localBindingExists = false;
                MortalList.scopeExitCleanupArray(lexicalOwner);
                MortalList.setActive(previousMortalListState);
                MortalList.invalidateAllRootSnapshots();
            }
        }
    }

    private static RuntimeScalar referenceTo(RuntimeBase referent) {
        RuntimeScalar scalar = new RuntimeScalar();
        scalar.type = RuntimeScalarType.REFERENCE;
        scalar.value = referent;
        return scalar;
    }
}
