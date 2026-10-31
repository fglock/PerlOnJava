package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RuntimeArraySingleValueOwnerTransferTest {
    @Test
    void setTransfersTheCountedCellToTheCurrentElementWithoutRootWalk() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash target = new RuntimeHash();
            target.activateOwnerTracking();
            RuntimeScalar value = new RuntimeScalar();
            value.set(target.createAnonymousReference());
            PerlOwnerSlot owner = value.countedOwnerSlot();
            assertNotNull(owner);
            assertEquals(PerlOwnerSlot.Kind.SCALAR_CELL, owner.kind());

            RuntimeArray array = new RuntimeArray();
            array.set(value);

            assertSame(owner, value.countedOwnerSlot(),
                    "moving the scalar cell preserves its counted owner identity");
            assertEquals(PerlOwnerSlot.Kind.ARRAY_ELEMENT, owner.kind());
            assertTrue(value.isCurrentAggregateElementOwner(target, owner));
            assertTrue(array.ownsElement(value));

            RuntimeScalar observer = target.createAnonymousReference();
            try {
                // The two live cells own the target, but simulate the transient
                // selective-count undercount that deferred cleanup must repair.
                target.refCount = 1;
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    WeakRefRegistry.weaken(observer);

                    assertTrue(WeakRefRegistry.isweak(observer));
                    assertEquals(1, target.refCount,
                            "the current array element identity restores its count");
                    assertEquals(0, measurement.stats().rootQueries);
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                    assertEquals(0, measurement.stats().scalarReachabilityRootWalks);
                }

                value.set(new RuntimeScalar());
                assertFalse(WeakRefRegistry.isweak(observer),
                        "releasing the transferred element clears its weak observer");
            } finally {
                if (WeakRefRegistry.isweak(observer)) WeakRefRegistry.unweaken(observer);
                observer.set(new RuntimeScalar());
                value.set(new RuntimeScalar());
            }
        }
    }
}
