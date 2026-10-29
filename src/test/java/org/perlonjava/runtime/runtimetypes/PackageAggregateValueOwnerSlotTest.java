package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class PackageAggregateValueOwnerSlotTest {
    @Test
    void prepopulatedPackageArrayOwnsUncountedValueWithoutSweeps() {
        Assumptions.assumeFalse(ReachabilityWalker.correctiveSweepsEnabled(),
                "package aggregate value ownership must not depend on corrective sweeps");

        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar referent = new RuntimeScalar("package aggregate referent");
            RuntimeScalar countedOwner = new RuntimeScalar();
            countedOwner.set(referent.createReference());

            RuntimeScalar aggregateValue = referenceTo(referent);
            RuntimeArray packageArray = new RuntimeArray();
            packageArray.elements.add(aggregateValue);
            String key = "PackageAggregateValueOwnerSlotTest::values";
            RuntimeScalar weakObserver = referenceTo(referent);
            PerlOwnerSlot valueOwner = null;

            try {
                GlobalVariable.globalArrays.put(key, packageArray);
                valueOwner = aggregateValue.untrackedReferenceOwnerSlot();
                assertNotNull(valueOwner,
                        "rooting a prepopulated aggregate records its uncounted value edge");
                assertEquals(1,
                        referent.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));

                WeakRefRegistry.weaken(weakObserver);
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    countedOwner.set(new RuntimeScalar());
                    assertTrue(WeakRefRegistry.isweak(weakObserver),
                            "the package aggregate value keeps the referent alive");

                    aggregateValue.set(new RuntimeScalar());

                    assertFalse(valueOwner.isActive(), "the value owner releases exactly once");
                    assertEquals(0,
                            referent.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));
                    assertFalse(WeakRefRegistry.isweak(weakObserver));
                    assertEquals(RuntimeScalarType.UNDEF, weakObserver.type);
                    assertEquals(0, measurement.stats().rootQueries);
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                }
            } finally {
                if (valueOwner != null && valueOwner.isActive()) {
                    aggregateValue.set(new RuntimeScalar());
                }
                GlobalVariable.globalArrays.remove(key);
                if (WeakRefRegistry.isweak(weakObserver)) {
                    WeakRefRegistry.unweaken(weakObserver);
                }
                countedOwner.set(new RuntimeScalar());
            }
        }
    }

    @Test
    void prepopulatedPackageHashOwnsUncountedValueWithoutSweeps() {
        Assumptions.assumeFalse(ReachabilityWalker.correctiveSweepsEnabled(),
                "package aggregate value ownership must not depend on corrective sweeps");

        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar referent = new RuntimeScalar("package hash referent");
            RuntimeScalar countedOwner = new RuntimeScalar();
            countedOwner.set(referent.createReference());

            RuntimeScalar aggregateValue = referenceTo(referent);
            RuntimeHash packageHash = new RuntimeHash();
            packageHash.elements.put("child", aggregateValue);
            String key = "PackageAggregateValueOwnerSlotTest::hash_values";
            RuntimeScalar weakObserver = referenceTo(referent);
            PerlOwnerSlot valueOwner = null;

            try {
                GlobalVariable.globalHashes.put(key, packageHash);
                valueOwner = aggregateValue.untrackedReferenceOwnerSlot();
                assertNotNull(valueOwner,
                        "rooting a prepopulated hash records its uncounted value edge");
                assertEquals(1,
                        referent.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));

                WeakRefRegistry.weaken(weakObserver);
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    countedOwner.set(new RuntimeScalar());
                    assertTrue(WeakRefRegistry.isweak(weakObserver),
                            "the package hash value keeps the referent alive");

                    aggregateValue.set(new RuntimeScalar());

                    assertFalse(valueOwner.isActive(), "the value owner releases exactly once");
                    assertEquals(0,
                            referent.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));
                    assertFalse(WeakRefRegistry.isweak(weakObserver));
                    assertEquals(RuntimeScalarType.UNDEF, weakObserver.type);
                    assertEquals(0, measurement.stats().rootQueries);
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                }
            } finally {
                if (valueOwner != null && valueOwner.isActive()) {
                    aggregateValue.set(new RuntimeScalar());
                }
                GlobalVariable.globalHashes.remove(key);
                if (WeakRefRegistry.isweak(weakObserver)) {
                    WeakRefRegistry.unweaken(weakObserver);
                }
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
