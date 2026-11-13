package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class LegacyUnpairedUncountedOwnerSlotFastPathTest {
    @Test
    void currentUncountedOwnerDefersLegacyReleaseWithoutRootRecovery() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            boolean previousMortalListState = MortalList.isActive();
            MortalList.setActive(true);
            RuntimeArray target = new RuntimeArray();
            RuntimeScalar strongOwner = referenceTo(target);
            RuntimeScalar weakObserver = referenceTo(target);
            int lexicalMark = MyVarCleanupStack.pushMark();
            try {
                MyVarCleanupStack.register(strongOwner);
                PerlOwnerSlot strongOwnerSlot = strongOwner.untrackedReferenceOwnerSlot();
                WeakRefRegistry.weaken(weakObserver);

                assertTrue(strongOwnerSlot.isActive());
                assertTrue(target.hasCurrentUncountedPerlOwnerSlotIdentity());
                target.refCount = 1; // Model the compatibility bridge's undercount.

                MortalList.pushMark();
                MortalList.deferDecrement(target);
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    MortalList.popAndFlush();

                    assertEquals(WeakRefRegistry.WEAKLY_TRACKED, target.refCount,
                            "the explicit owner slot carries finalization while it remains active");
                    assertTrue(WeakRefRegistry.isweak(weakObserver));
                    assertTrue(target.deferredFinalReleaseForUncountedOwners);
                    assertEquals(0, measurement.stats().rootQueries,
                            "a current owner identity makes root recovery unnecessary");
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                }

                strongOwner.undefine();

                assertFalse(strongOwnerSlot.isActive());
                assertFalse(WeakRefRegistry.isweak(weakObserver),
                        "the final exact owner release clears the weak observer");
                assertEquals(RuntimeScalarType.UNDEF, weakObserver.type);
                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                        target.perlLifecycleState());
            } finally {
                if (strongOwner.value == target) strongOwner.undefine();
                if (WeakRefRegistry.isweak(weakObserver)) {
                    WeakRefRegistry.unweaken(weakObserver);
                }
                RuntimeScalar.scopeExitCleanup(strongOwner);
                MyVarCleanupStack.unregister(strongOwner);
                MyVarCleanupStack.popMark(lexicalMark);
                MortalList.invalidateAllRootSnapshots();
                MortalList.setActive(previousMortalListState);
            }
        }
    }

    private static RuntimeScalar referenceTo(RuntimeBase referent) {
        RuntimeScalar scalar = new RuntimeScalar();
        scalar.type = RuntimeScalarType.ARRAYREFERENCE;
        scalar.value = referent;
        return scalar;
    }
}
