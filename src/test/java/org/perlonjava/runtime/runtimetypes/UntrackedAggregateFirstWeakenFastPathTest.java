package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class UntrackedAggregateFirstWeakenFastPathTest {
    @Test
    void temporaryAggregateKeepsSentinelUntilDurableSlotWithoutRootWalk() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray target = new RuntimeArray();
            target.markRefCountUntracked();
            RuntimeScalar weakObserver = referenceTo(target);
            String key = "UntrackedAggregateFirstWeakenFastPathTest::target";

            try {
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    WeakRefRegistry.weaken(weakObserver);

                    assertTrue(WeakRefRegistry.isweak(weakObserver));
                    assertEquals(RuntimeBase.RefCountTrackingState.WEAKLY_TRACKED, target.refCountTrackingState());
                    assertEquals(0, measurement.stats().rootQueries,
                            "an untracked temporary needs no root-graph fallback");
                    assertEquals(0, measurement.stats().edgesInspected);
                    assertEquals(0, measurement.stats().nodesVisited);
                }

                GlobalVariable.globalArrays.put(key, target);
                assertEquals(1,
                        target.ownerSlotCount(PerlOwnerSlot.Kind.PACKAGE_AGGREGATE_SLOT));
                GlobalVariable.globalArrays.remove(key);

                assertFalse(WeakRefRegistry.isweak(weakObserver),
                        "the durable package slot's final release clears the weak observer");
                assertEquals(RuntimeScalarType.UNDEF, weakObserver.type);
                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED, target.perlLifecycleState());
                assertEquals(0, target.refCount);
            } finally {
                if (WeakRefRegistry.isweak(weakObserver)) {
                    WeakRefRegistry.unweaken(weakObserver);
                }
                GlobalVariable.globalArrays.remove(key);
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
