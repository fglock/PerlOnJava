package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RuntimeScalarUncountedOwnerRepairTest {
    @Test
    void uncountedContainerSlotKeepsWeakReferentAliveWithoutRootWalk() {
        Assumptions.assumeFalse(ReachabilityWalker.correctiveSweepsEnabled(),
                "this lifecycle regression requires corrective sweeps to be disabled");

        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash target = new RuntimeHash();
            target.blessId = NameNormalizer.getBlessId(
                    "RuntimeScalarUncountedOwnerRepairTest::Target");
            target.refCount = -1;

            RuntimeArray ownerContainer = new RuntimeArray();
            RuntimeScalar uncountedOwner = referenceTo(target);
            ownerContainer.elements.add(uncountedOwner);
            PerlOwnerSlot ownerSlot = uncountedOwner.untrackedReferenceOwnerSlot();
            assertNotNull(ownerSlot);
            assertEquals(1, target.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));

            RuntimeScalar countedOwner = new RuntimeScalar();
            countedOwner.set(target.createAnonymousReference());
            RuntimeScalar weakObserver = target.createAnonymousReference();
            WeakRefRegistry.weaken(weakObserver);
            assertTrue(WeakRefRegistry.isweak(weakObserver));

            try {
                // Model a transient selective-count undercount. Releasing this
                // last counted slot leaves the array element as the strong owner.
                target.refCount = 1;
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    countedOwner.set(new RuntimeScalar());

                    assertEquals(WeakRefRegistry.WEAKLY_TRACKED, target.refCount);
                    assertTrue(WeakRefRegistry.isweak(weakObserver));
                    assertFalse(target.destroyFired);
                    assertEquals(0, measurement.stats().rootQueries,
                            "the uncounted slot replaces the package-root fallback");
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                }

                uncountedOwner.set(new RuntimeScalar());

                assertFalse(ownerSlot.isActive());
                assertFalse(WeakRefRegistry.isweak(weakObserver),
                        "the last uncounted owner release clears weak observers");
                assertEquals(RuntimeScalarType.UNDEF, weakObserver.type);
                assertEquals(Integer.MIN_VALUE, target.refCount);
            } finally {
                if (WeakRefRegistry.isweak(weakObserver)) {
                    WeakRefRegistry.unweaken(weakObserver);
                }
                if (ownerSlot.isActive()) uncountedOwner.set(new RuntimeScalar());
                countedOwner.set(new RuntimeScalar());
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
