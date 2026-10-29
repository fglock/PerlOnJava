package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class CountedOwnerSlotRepairsUnblessedReferentTest {
    @Test
    void scalarOverwriteUsesSurvivingCountedSlotWithoutRootWalk() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash target = new RuntimeHash();
            RuntimeArray arrayOwner = new RuntimeArray();
            arrayOwner.push(target.createAnonymousReference());
            RuntimeScalar scalarOwner = new RuntimeScalar();
            scalarOwner.set(target.createAnonymousReference());
            RuntimeScalar observer = target.createAnonymousReference();
            WeakRefRegistry.weaken(observer);

            try {
                assertEquals(2, target.countedOwnerSlotCount());
                target.refCount = 1; // Model a transient legacy-count undercount.

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    scalarOwner.set(new RuntimeScalar());

                    assertEquals(1, target.refCount);
                    assertEquals(1, target.ownerSlotCount(PerlOwnerSlot.Kind.ARRAY_ELEMENT));
                    assertTrue(WeakRefRegistry.isweak(observer));
                    assertEquals(0, measurement.stats().rootQueries);
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                }

                arrayOwner.elements.getFirst().set(new RuntimeScalar());
                assertFalse(WeakRefRegistry.isweak(observer));
            } finally {
                if (WeakRefRegistry.isweak(observer)) WeakRefRegistry.unweaken(observer);
                scalarOwner.set(new RuntimeScalar());
                if (!arrayOwner.elements.isEmpty()) {
                    arrayOwner.elements.getFirst().set(new RuntimeScalar());
                }
            }
        }
    }

}
