package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class DeferredUnblessedAggregateOwnerSlotRepairTest {
    @Test
    void deferredReleaseUsesCurrentArrayElementSlotWithoutRootSnapshot() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash target = new RuntimeHash();
            RuntimeArray ownerArray = new RuntimeArray();
            ownerArray.push(target.createAnonymousReference());
            RuntimeScalar weakObserver = target.createAnonymousReference();
            WeakRefRegistry.weaken(weakObserver);

            try {
                assertEquals(1, target.ownerSlotCount(PerlOwnerSlot.Kind.ARRAY_ELEMENT));
                target.refCount = 1; // Model a transient legacy-count undercount.
                MortalList.pushMark();
                MortalList.deferDecrement(target);

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    MortalList.popAndFlush();

                    assertEquals(1, target.refCount);
                    assertTrue(WeakRefRegistry.isweak(weakObserver));
                    assertEquals(0, measurement.stats().rootQueries);
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                }

                ownerArray.elements.getFirst().set(new RuntimeScalar());
                assertFalse(WeakRefRegistry.isweak(weakObserver));
            } finally {
                if (WeakRefRegistry.isweak(weakObserver)) WeakRefRegistry.unweaken(weakObserver);
                if (!ownerArray.elements.isEmpty()) {
                    ownerArray.elements.getFirst().set(new RuntimeScalar());
                }
            }
        }
    }
}
