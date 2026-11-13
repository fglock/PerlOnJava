package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class CurrentCountedOwnerRepairsDeferredUndercountTest {
    @Test
    void currentScalarCellSlotRepairsDeferredUndercountWithoutReachabilityQueries() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash target = new RuntimeHash();
            RuntimeScalar released = new RuntimeScalar();
            RuntimeScalar surviving = new RuntimeScalar();
            RuntimeScalar weakObserver = target.createAnonymousReference();
            WeakRefRegistry.weaken(weakObserver);
            MyVarCleanupStack.register(released);
            MyVarCleanupStack.register(surviving);

            try {
                released.set(target.createAnonymousReference());
                surviving.set(target.createAnonymousReference());

                assertEquals(2, target.countedOwnerSlotCount());
                assertEquals(2, target.activeOwnerSnapshot().size());
                assertTrue(target.activeOwnerSnapshot().stream().allMatch(owner ->
                        owner.countedOwnerSlot() != null
                                && owner.countedOwnerSlot().isActive()
                                && owner.countedOwnerSlot().referent() == target),
                        "each active scalar owner has a corresponding counted identity");
                target.refCount = 1; // Model a transient compatibility-count undercount.

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    MortalList.deferDecrementIfTracked(released);
                    MortalList.flushAboveMark();

                    assertEquals(1, target.refCount);
                    assertEquals(1, target.countedOwnerSlotCount());
                    assertTrue(WeakRefRegistry.isweak(weakObserver));
                    assertEquals(0, measurement.stats().rootQueries);
                    assertEquals(0, measurement.stats().scalarReachabilityQueries);
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                }

                surviving.set(new RuntimeScalar());
                assertEquals(RuntimeScalarType.UNDEF, weakObserver.type,
                        "the final current owner clears the weak observer");
            } finally {
                if (WeakRefRegistry.isweak(weakObserver)) {
                    WeakRefRegistry.unweaken(weakObserver);
                }
                RuntimeScalar.scopeExitCleanup(released);
                RuntimeScalar.scopeExitCleanup(surviving);
                MyVarCleanupStack.unregister(released);
                MyVarCleanupStack.unregister(surviving);
                MortalList.flush();
            }
        }
    }
}
