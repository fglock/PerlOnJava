package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ReachabilityWalkerScalarValueOwnerSlotTest {
    @Test
    void directOwnerSlotPreservesTargetCreatedBeforeWeakTracking() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray target = new RuntimeArray();
            RuntimeScalar strongOwner = new RuntimeScalar();
            strongOwner.set(target.createAnonymousReference());
            MyVarCleanupStack.register(strongOwner);

            RuntimeScalar weakObserver = target.createReference();
            WeakRefRegistry.weaken(weakObserver);

            assertFalse(ScalarRefRegistry.snapshot().contains(strongOwner),
                    "the strong owner predates lazy weak-scalar registration");
            assertTrue(strongOwner.scalarValueOwnerSlot().isActive());
            assertEquals(1, target.refCount);
            // Simulate retirement of the legacy bridge while the identity-based
            // scalar owner slot remains the authoritative ownership record.
            strongOwner.refCountOwned = false;

            try (MortalList.ReachabilityQueryMeasurement measurement =
                         MortalList.measureReachabilityQueries()) {
                ReachabilityWalker.sweepReleasedWeakReferents(
                        Collections.singleton(target));

                assertEquals(1, target.refCount,
                        "the direct scalar owner keeps the target alive");
                assertEquals(0, measurement.stats().snapshotsBuilt,
                        "the explicit owner slot proves the target without a root walk");
                assertEquals(0, measurement.stats().scalarReachabilityQueries);
            } finally {
                WeakRefRegistry.unweaken(weakObserver);
                MyVarCleanupStack.unregister(strongOwner);
            }
        }
    }

    @Test
    void weakeningUsesAnActiveScalarOwnerSlotAfterLegacyFlagRetires() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray target = new RuntimeArray();
            RuntimeScalar strongOwner = new RuntimeScalar();
            strongOwner.set(target.createAnonymousReference());
            MyVarCleanupStack.register(strongOwner);

            RuntimeScalar weakObserver = new RuntimeScalar();
            weakObserver.set(target.createAnonymousReference());
            assertEquals(2, target.refCount);
            weakObserver.refCountOwned = false;
            assertTrue(weakObserver.scalarValueOwnerSlot().isActive());

            try {
                WeakRefRegistry.weaken(weakObserver);

                assertEquals(1, target.refCount,
                        "weakening consumes the count proven by the explicit slot");
                assertFalse(weakObserver.scalarValueOwnerSlot().isActive());
                assertTrue(WeakRefRegistry.isweak(weakObserver));
            } finally {
                WeakRefRegistry.unweaken(weakObserver);
                MyVarCleanupStack.unregister(strongOwner);
            }
        }
    }
}
