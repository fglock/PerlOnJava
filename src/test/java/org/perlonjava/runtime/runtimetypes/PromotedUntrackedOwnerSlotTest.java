package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class PromotedUntrackedOwnerSlotTest {
    @Test
    void scalarInUnownedTemporaryArrayDoesNotPinPromotedReferent() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar target = new RuntimeScalar("temporary array referent");
            RuntimeScalar element = referenceTo(target);
            RuntimeArray temporaryArray = new RuntimeArray();
            temporaryArray.elements.add(element);
            element.markContainerOwner(temporaryArray);
            element.refreshUntrackedReferenceOwner();
            PerlOwnerSlot ownerSlot = element.untrackedReferenceOwnerSlot();
            assertNotNull(ownerSlot);

            target.refCount = 0;

            assertFalse(target.hasUntrackedReferenceOwnerSlotIdentity());
            assertFalse(ownerSlot.isActive(),
                    "a temporary container without a Perl owner must not retain the referent");
            assertEquals(0, target.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));
        }
    }

    @Test
    void durableUncountedPackageValueSurvivesReferentPromotionAndFinalCountedRelease() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar target = new RuntimeScalar("promoted referent");
            RuntimeScalar packageOwner = referenceTo(target);
            String globalName = "PromotedUntrackedOwnerSlotTest::packageOwner";
            GlobalVariable.globalVariables.put(globalName, packageOwner);
            PerlOwnerSlot ownerSlot = packageOwner.untrackedReferenceOwnerSlot();
            assertNotNull(ownerSlot,
                    "a durable owner without a count needs an owner-slot identity");
            assertEquals(target, ownerSlot.referent());

            RuntimeScalar countedOwner = new RuntimeScalar();
            countedOwner.set(target.createReference());
            packageOwner.refreshUntrackedReferenceOwner();
            RuntimeScalar weak = target.createReference();
            WeakRefRegistry.weaken(weak);

            try {
                assertEquals(1, target.refCount,
                        "a later counted store promotes the referent independently");
                assertTrue(ownerSlot.isActive(),
                        "promotion must not retire the earlier uncounted Perl slot");

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    countedOwner.set(new RuntimeScalar());

                    assertEquals(WeakRefRegistry.WEAKLY_TRACKED, target.refCount);
                    assertTrue(WeakRefRegistry.isweak(weak),
                            "the surviving package scalar keeps the weak referent alive");
                    assertEquals(0, measurement.stats().rootQueries);
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                }

                GlobalVariable.globalVariables.remove(globalName);

                assertFalse(ownerSlot.isActive());
                assertFalse(WeakRefRegistry.isweak(weak));
                assertEquals(RuntimeScalarType.UNDEF, weak.type);
                assertEquals(Integer.MIN_VALUE, target.refCount);
            } finally {
                GlobalVariable.globalVariables.remove(globalName);
                if (WeakRefRegistry.isweak(weak)) WeakRefRegistry.unweaken(weak);
                countedOwner.set(new RuntimeScalar());
            }
        }
    }

    private static RuntimeScalar referenceTo(RuntimeBase referent) {
        RuntimeScalar reference = new RuntimeScalar();
        reference.type = RuntimeScalarType.REFERENCE;
        reference.value = referent;
        return reference;
    }
}
