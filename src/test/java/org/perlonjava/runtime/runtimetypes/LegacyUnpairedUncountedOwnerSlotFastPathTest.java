package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class LegacyUnpairedUncountedOwnerSlotFastPathTest {
    @Test
    void currentUncountedOwnerDefersWeakCleanupWithoutRootRecovery() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            boolean previousMortalListState = MortalList.isActive();
            MortalList.setActive(true);
            RuntimeArray target = new RuntimeArray();
            target.markRefCountUntracked();
            RuntimeArray lexicalOwner = new RuntimeArray();
            RuntimeScalar strongOwner = referenceTo(target);
            lexicalOwner.elements.add(strongOwner);
            RuntimeScalar weakObserver = referenceTo(target);
            int lexicalMark = MyVarCleanupStack.pushMark();
            try {
                MyVarCleanupStack.register(lexicalOwner);
                PerlOwnerSlot strongOwnerSlot = strongOwner.untrackedReferenceOwnerSlot();
                assertTrue(strongOwnerSlot != null && strongOwnerSlot.isActive());
                target.setTrackedRefCount(1);
                weakObserver.refCountOwned = true;
                WeakRefRegistry.weaken(weakObserver);

                assertTrue(strongOwnerSlot.isActive());
                assertTrue(target.hasCurrentUncountedPerlOwnerSlotIdentity());
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    assertTrue(WeakRefRegistry.isweak(weakObserver));
                    assertEquals(RuntimeBase.RefCountTrackingState.WEAKLY_TRACKED, target.refCountTrackingState(),
                            "weak tracking preserves the current uncounted Perl owner");
                    assertEquals(0, measurement.stats().rootQueries,
                            "a current owner identity makes root recovery unnecessary");
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                }

                strongOwner.set(new RuntimeScalar());

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
                MyVarCleanupStack.unregister(lexicalOwner);
                MyVarCleanupStack.popMark(lexicalMark);
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
