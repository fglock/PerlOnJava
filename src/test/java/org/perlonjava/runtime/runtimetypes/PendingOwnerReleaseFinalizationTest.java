package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class PendingOwnerReleaseFinalizationTest {
    @Test
    void clearedAggregateFinalizationWaitsForQueuedCountedRelease() {
        PerlRuntime runtime = new PerlRuntime();
        RuntimeHash referent = new RuntimeHash();
        RuntimeScalar owner = new RuntimeScalar();
        RuntimeScalar weakObserver = referenceTo(referent);

        try (PerlRuntime.Binding ignored = runtime.bind()) {
            boolean previousMortalListState = MortalList.isActive();
            MortalList.setActive(true);
            try {
                owner.set(referent.createAnonymousReference());
                WeakRefRegistry.weaken(weakObserver);
                assertEquals(1, referent.refCount);
                assertEquals(1, referent.countedOwnerSlotCount());

                referent.clearedOwnedAggregateElement = true;
                MortalList.deferDecrementIfTracked(owner);

                assertFalse(owner.refCountOwned);
                assertEquals(0, referent.countedOwnerSlotCount());
                assertEquals(1, referent.refCount,
                        "the queued release has not decremented the compatibility count yet");
                assertTrue(referent.hasPendingOwnerRelease());

                MortalList.finalizeClearedAggregateOwnerAfterScopeExit(referent);

                assertTrue(WeakRefRegistry.isweak(weakObserver),
                        "scope cleanup must leave the queued release to decide finalization");
                assertEquals(1, referent.refCount);

                MortalList.flush();

                assertFalse(WeakRefRegistry.isweak(weakObserver),
                        "draining the final counted release clears the weak observer");
                assertEquals(Integer.MIN_VALUE, referent.refCount);
                assertFalse(referent.hasPendingOwnerRelease());
            } finally {
                if (WeakRefRegistry.isweak(weakObserver)) {
                    WeakRefRegistry.unweaken(weakObserver);
                }
                if (referent.hasPendingOwnerRelease()) MortalList.flush();
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
