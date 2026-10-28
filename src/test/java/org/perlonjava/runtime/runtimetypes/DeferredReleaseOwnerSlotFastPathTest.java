package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class DeferredReleaseOwnerSlotFastPathTest {
    @Test
    void remainingScalarOwnerRepairsDeferredZeroWithoutRootWalk() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash target = new RuntimeHash();
            target.blessId = NameNormalizer.getBlessId("Issue1649::DeferredOwnerSlot");
            RuntimeScalar released = new RuntimeScalar();
            released.set(target.createAnonymousReference());
            RuntimeScalar remaining = new RuntimeScalar();
            remaining.set(target.createAnonymousReference());
            RuntimeScalar weak = target.createAnonymousReference();
            WeakRefRegistry.weaken(weak);

            try {
                assertEquals(2, target.countedOwnerSlotCount());
                target.refCount = 1; // Model a transient legacy-count underflow.

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    MortalList.deferDecrementIfTracked(released);
                    MortalList.flushAboveMark();

                    assertEquals(1, target.refCount);
                    assertEquals(1, target.countedOwnerSlotCount());
                    assertTrue(WeakRefRegistry.isweak(weak));
                    assertEquals(0, measurement.stats().rootQueries);
                    assertEquals(0, measurement.stats().snapshotsBuilt);

                    remaining.set(new RuntimeScalar());
                    assertEquals(RuntimeScalarType.UNDEF, weak.type);
                    assertEquals(0, target.countedOwnerSlotCount());
                }
            } finally {
                if (WeakRefRegistry.isweak(weak)) WeakRefRegistry.unweaken(weak);
            }
        }
    }
}
