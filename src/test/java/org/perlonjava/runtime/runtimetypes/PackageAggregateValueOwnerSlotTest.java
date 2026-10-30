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

    @Test
    void packageAggregateValuesOwnArrayAndHashReferentsWithoutSweeps() {
        Assumptions.assumeFalse(ReachabilityWalker.correctiveSweepsEnabled(),
                "package aggregate value ownership must not depend on corrective sweeps");

        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            assertPackageAggregateValueKeepsReferentAlive(
                    new RuntimeArray(), new RuntimeArray(), true,
                    "PackageAggregateValueOwnerSlotTest::array_referent");
            assertPackageAggregateValueKeepsReferentAlive(
                    new RuntimeHash(), new RuntimeHash(), false,
                    "PackageAggregateValueOwnerSlotTest::hash_referent");
        }
    }

    @Test
    void crossContainerAliasKeepsOneValueOwnerUntilItsFinalPackageSlotLeaves() {
        Assumptions.assumeFalse(ReachabilityWalker.correctiveSweepsEnabled(),
                "cross-container alias ownership must not depend on corrective sweeps");

        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray packageArray = new RuntimeArray();
            RuntimeHash packageHash = new RuntimeHash();
            RuntimeArray referent = new RuntimeArray();
            RuntimeScalar sharedValue = referenceTo(referent);
            RuntimeScalar weakObserver = referenceTo(referent);
            String arrayKey = "PackageAggregateValueOwnerSlotTest::alias_array";
            String hashKey = "PackageAggregateValueOwnerSlotTest::alias_hash";

            try {
                GlobalVariable.globalArrays.put(arrayKey, packageArray);
                GlobalVariable.globalHashes.put(hashKey, packageHash);
                packageArray.elements.add(sharedValue);
                packageHash.elements.put("shared", sharedValue);

                PerlOwnerSlot owner = sharedValue.untrackedReferenceOwnerSlot();
                assertNotNull(owner);
                assertEquals(1,
                        referent.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));
                WeakRefRegistry.weaken(weakObserver);

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    packageArray.elements.remove(sharedValue);

                    assertTrue(WeakRefRegistry.isweak(weakObserver),
                            "the hash alias remains a strong owner after the array slot leaves");
                    assertTrue(owner.isActive());

                    packageHash.elements.remove("shared");

                    assertFalse(owner.isActive(), "the shared value owner releases once");
                    assertFalse(WeakRefRegistry.isweak(weakObserver));
                    assertEquals(RuntimeScalarType.UNDEF, weakObserver.type);
                    assertEquals(0, measurement.stats().rootQueries);
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                }
            } finally {
                GlobalVariable.globalArrays.remove(arrayKey);
                GlobalVariable.globalHashes.remove(hashKey);
                if (WeakRefRegistry.isweak(weakObserver)) WeakRefRegistry.unweaken(weakObserver);
            }
        }
    }

    private static void assertPackageAggregateValueKeepsReferentAlive(
            RuntimeBase referent, RuntimeBase packageAggregate, boolean packageArray,
            String key) {
        RuntimeScalar aggregateValue = referenceTo(referent);
        if (packageArray) {
            ((RuntimeArray) packageAggregate).elements.add(aggregateValue);
            GlobalVariable.globalArrays.put(key, (RuntimeArray) packageAggregate);
        } else {
            ((RuntimeHash) packageAggregate).elements.put("child", aggregateValue);
            GlobalVariable.globalHashes.put(key, (RuntimeHash) packageAggregate);
        }
        RuntimeScalar weakObserver = referenceTo(referent);
        PerlOwnerSlot valueOwner = aggregateValue.untrackedReferenceOwnerSlot();

        try {
            assertNotNull(valueOwner,
                    "a package aggregate slot records its direct aggregate referent edge");
            assertEquals(1,
                    referent.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));

            WeakRefRegistry.weaken(weakObserver);
            try (MortalList.ReachabilityQueryMeasurement measurement =
                         MortalList.measureReachabilityQueries()) {
                assertTrue(WeakRefRegistry.isweak(weakObserver));

                aggregateValue.set(new RuntimeScalar());

                assertFalse(valueOwner.isActive());
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
            if (packageArray) GlobalVariable.globalArrays.remove(key);
            else GlobalVariable.globalHashes.remove(key);
            if (WeakRefRegistry.isweak(weakObserver)) WeakRefRegistry.unweaken(weakObserver);
        }
    }

    private static RuntimeScalar referenceTo(RuntimeBase referent) {
        RuntimeScalar reference = new RuntimeScalar();
        reference.type = RuntimeScalarType.REFERENCE;
        reference.value = referent;
        return reference;
    }
}
