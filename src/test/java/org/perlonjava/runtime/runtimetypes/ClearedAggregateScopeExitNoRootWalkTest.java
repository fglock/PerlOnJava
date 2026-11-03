package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@Tag("unit")
class ClearedAggregateScopeExitNoRootWalkTest {
    @Test
    void finalClearedAggregateOwnerReleaseUsesIdentityStateWithoutRootWalk() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            boolean previousActive = MortalList.isActive();
            RuntimeHash referent = new RuntimeHash();
            referent.blessId = NameNormalizer.getBlessId("Issue1649::ClearedScopeExit");
            referent.clearedOwnedAggregateElement = true;
            RuntimeScalar weakObserver = referent.createReference();
            WeakRefRegistry.weaken(weakObserver);
            referent.refCount = 0;

            try {
                MortalList.setActive(true);
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    MortalList.finalizeClearedAggregateOwnerAfterScopeExit(referent);

                    assertFalse(WeakRefRegistry.isweak(weakObserver),
                            "the final owner identity release clears weak observers");
                    assertEquals(Integer.MIN_VALUE, referent.refCount);
                    assertEquals(0, measurement.stats().rootQueries);
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                    assertEquals(0, measurement.stats().externalRootSnapshotsBuilt);
                }
            } finally {
                if (WeakRefRegistry.isweak(weakObserver)) {
                    WeakRefRegistry.clearWeakRefsTo(referent);
                }
                MortalList.invalidateAllRootSnapshots();
                MortalList.setActive(previousActive);
            }
        }
    }
}
