package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class PackageAggregateOwnerSlotTest {
    @Test
    void packageArrayAndHashKeysOwnDistinctRootIdentities() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray array = new RuntimeArray();
            RuntimeHash hash = new RuntimeHash();
            int arrayRefCount = array.refCount;
            int hashRefCount = hash.refCount;
            String firstArrayKey = "PackageAggregateOwnerSlotTest::array_one";
            String secondArrayKey = "PackageAggregateOwnerSlotTest::array_two";
            String sharedKey = "PackageAggregateOwnerSlotTest::shared_name";

            try {
                GlobalVariable.globalArrays.put(firstArrayKey, array);
                PerlOwnerSlot firstArrayOwner = array.ownerSlotSnapshot().stream()
                        .filter(slot -> slot.kind() == PerlOwnerSlot.Kind.PACKAGE_AGGREGATE_SLOT)
                        .findFirst().orElse(null);
                assertNotNull(firstArrayOwner);
                assertSame(array, firstArrayOwner.referent());
                assertEquals(1, array.ownerSlotCount(PerlOwnerSlot.Kind.PACKAGE_AGGREGATE_SLOT));

                GlobalVariable.globalArrays.put(secondArrayKey, array);
                assertEquals(2, array.ownerSlotCount(PerlOwnerSlot.Kind.PACKAGE_AGGREGATE_SLOT),
                        "each package key owns an independent array root slot");
                PerlOwnerSlot secondArrayOwner = array.ownerSlotSnapshot().stream()
                        .filter(slot -> slot.kind() == PerlOwnerSlot.Kind.PACKAGE_AGGREGATE_SLOT
                                && slot != firstArrayOwner)
                        .findFirst().orElse(null);
                assertNotNull(secondArrayOwner);

                GlobalVariable.globalHashes.put(sharedKey, hash);
                assertEquals(1, hash.ownerSlotCount(PerlOwnerSlot.Kind.PACKAGE_AGGREGATE_SLOT),
                        "array and hash roots with one name have separate identities");

                GlobalVariable.globalArrays.keySet().remove(firstArrayKey);
                assertFalse(firstArrayOwner.isActive());
                assertTrue(secondArrayOwner.isActive());
                assertEquals(1, array.ownerSlotCount(PerlOwnerSlot.Kind.PACKAGE_AGGREGATE_SLOT));

                GlobalVariable.globalArrays.remove(secondArrayKey);
                GlobalVariable.globalHashes.remove(sharedKey);
                assertFalse(secondArrayOwner.isActive());
                assertEquals(0, array.ownerSlotCount(PerlOwnerSlot.Kind.PACKAGE_AGGREGATE_SLOT));
                assertEquals(0, hash.ownerSlotCount(PerlOwnerSlot.Kind.PACKAGE_AGGREGATE_SLOT));
                assertEquals(arrayRefCount, array.refCount,
                        "package root identities must not promote the aggregate count");
                assertEquals(hashRefCount, hash.refCount,
                        "package root identities must not promote the aggregate count");

                GlobalVariable.globalArrays.put(firstArrayKey, array);
                GlobalVariable.globalHashes.put(sharedKey, hash);
                PerlOwnerSlot clearArrayOwner = array.ownerSlotSnapshot().stream()
                        .filter(slot -> slot.isActive()
                                && slot.kind() == PerlOwnerSlot.Kind.PACKAGE_AGGREGATE_SLOT)
                        .findFirst().orElseThrow();
                PerlOwnerSlot clearHashOwner = hash.ownerSlotSnapshot().stream()
                        .filter(slot -> slot.isActive()
                                && slot.kind() == PerlOwnerSlot.Kind.PACKAGE_AGGREGATE_SLOT)
                        .findFirst().orElseThrow();
                GlobalVariable.globalArrays.clear();
                GlobalVariable.globalHashes.clear();
                assertFalse(clearArrayOwner.isActive());
                assertFalse(clearHashOwner.isActive());
            } finally {
                GlobalVariable.globalArrays.remove(firstArrayKey);
                GlobalVariable.globalArrays.remove(secondArrayKey);
                GlobalVariable.globalHashes.remove(sharedKey);
            }
        }
    }

    @Test
    void packageArrayAndHashRootsRebuildFromSnapshotTables() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray array = new RuntimeArray();
            RuntimeHash hash = new RuntimeHash();
            runtime.globalState.arrayValues().put("PackageAggregateOwnerSlotTest::clone_array", array);
            runtime.globalState.hashValues().put("PackageAggregateOwnerSlotTest::clone_hash", hash);

            runtime.globalState.rebuildPackageAggregateOwnerSlots();

            assertEquals(1, array.ownerSlotCount(PerlOwnerSlot.Kind.PACKAGE_AGGREGATE_SLOT));
            assertEquals(1, hash.ownerSlotCount(PerlOwnerSlot.Kind.PACKAGE_AGGREGATE_SLOT));
            assertEquals(-1, array.refCount);
            assertEquals(-1, hash.refCount);
        }
    }

    @Test
    void packageRootEntryValueReplacementTransfersItsIdentity() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray first = new RuntimeArray();
            RuntimeArray replacement = new RuntimeArray();
            String key = "PackageAggregateOwnerSlotTest::replace_array";

            try {
                GlobalVariable.globalArrays.put(key, first);
                PerlOwnerSlot owner = first.ownerSlotSnapshot().stream()
                        .filter(slot -> slot.kind() == PerlOwnerSlot.Kind.PACKAGE_AGGREGATE_SLOT)
                        .findFirst().orElseThrow();
                GlobalVariable.globalArrays.entrySet().stream()
                        .filter(entry -> entry.getKey().equals(key))
                        .findFirst().orElseThrow().setValue(replacement);

                assertSame(replacement, owner.referent());
                assertEquals(0, first.ownerSlotCount(PerlOwnerSlot.Kind.PACKAGE_AGGREGATE_SLOT));
                assertEquals(1,
                        replacement.ownerSlotCount(PerlOwnerSlot.Kind.PACKAGE_AGGREGATE_SLOT));

                GlobalVariable.globalArrays.remove(key);
                assertFalse(owner.isActive());
                assertEquals(0,
                        replacement.ownerSlotCount(PerlOwnerSlot.Kind.PACKAGE_AGGREGATE_SLOT));
            } finally {
                GlobalVariable.globalArrays.remove(key);
            }
        }
    }

    @Test
    void finalPackageAggregateRootClearsWeakObserverWithoutSweeps() {
        Assumptions.assumeFalse(ReachabilityWalker.correctiveSweepsEnabled(),
                "package-root release must clear weak refs without corrective sweeps");

        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray array = new RuntimeArray();
            RuntimeScalar weakObserver = referenceTo(array);
            WeakRefRegistry.weaken(weakObserver);
            assertEquals(WeakRefRegistry.WEAKLY_TRACKED, array.refCount);
            String key = "PackageAggregateOwnerSlotTest::weak_array";

            try {
                GlobalVariable.globalArrays.put(key, array);
                assertEquals(1, array.ownerSlotCount(PerlOwnerSlot.Kind.PACKAGE_AGGREGATE_SLOT));

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    GlobalVariable.globalArrays.remove(key);

                    assertEquals(0, array.ownerSlotCount(
                            PerlOwnerSlot.Kind.PACKAGE_AGGREGATE_SLOT));
                    assertFalse(WeakRefRegistry.isweak(weakObserver));
                    assertEquals(RuntimeScalarType.UNDEF, weakObserver.type);
                    assertEquals(0, measurement.stats().rootQueries);
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                    assertEquals(0, measurement.stats().scalarReachabilityQueries);
                }
            } finally {
                GlobalVariable.globalArrays.remove(key);
                if (WeakRefRegistry.isweak(weakObserver)) WeakRefRegistry.unweaken(weakObserver);
            }
        }
    }

    @Test
    void finalPackageScalarRootClearsWeakObserverToCellWithoutSweeps() {
        Assumptions.assumeFalse(ReachabilityWalker.correctiveSweepsEnabled(),
                "package-root release must clear weak refs without corrective sweeps");

        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar packageCell = new RuntimeScalar("package scalar cell");
            RuntimeScalar weakObserver = referenceTo(packageCell);
            WeakRefRegistry.weaken(weakObserver);
            assertEquals(WeakRefRegistry.WEAKLY_TRACKED, packageCell.refCount);
            String key = "PackageAggregateOwnerSlotTest::weak_scalar";

            try {
                GlobalVariable.globalVariables.put(key, packageCell);
                assertEquals(1, packageCell.ownerSlotCount(PerlOwnerSlot.Kind.PACKAGE_SCALAR_SLOT));

                GlobalVariable.globalVariables.remove(key);

                assertEquals(0, packageCell.ownerSlotCount(PerlOwnerSlot.Kind.PACKAGE_SCALAR_SLOT));
                assertFalse(WeakRefRegistry.isweak(weakObserver));
                assertEquals(RuntimeScalarType.UNDEF, weakObserver.type);
            } finally {
                GlobalVariable.globalVariables.remove(key);
                if (WeakRefRegistry.isweak(weakObserver)) WeakRefRegistry.unweaken(weakObserver);
            }
        }
    }

    private static RuntimeScalar referenceTo(RuntimeBase value) {
        RuntimeScalar reference = new RuntimeScalar();
        reference.type = RuntimeScalarType.REFERENCE;
        reference.value = value;
        return reference;
    }

}
