package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@Tag("unit")
class RuntimeScalarContainerOwnerConsistencyTest {
    @Test
    void stalePreferredOwnerIsReplacedByAStillStoredContainer() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar value = new RuntimeHash().createAnonymousReference();
            RuntimeArray survivingOwner = new RuntimeArray();
            RuntimeArray removedOwner = new RuntimeArray();

            survivingOwner.elements.add(value);
            removedOwner.elements.add(value);
            assertSame(removedOwner, value.getContainerOwner());

            removedOwner.elements.remove(value);

            assertSame(survivingOwner, value.getContainerOwner());
        }
    }

    @Test
    void packageRootOwnerSurvivesWhenCurrentOwnerChanges() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar value = new RuntimeHash().createAnonymousReference();
            RuntimeArray packageRoot = new RuntimeArray();
            RuntimeArray otherOwner = new RuntimeArray();

            packageRoot.elements.add(value);
            GlobalVariable.markPackageGlobalRoot(packageRoot);
            otherOwner.elements.add(value);
            assertSame(otherOwner, value.getContainerOwner());

            assertNotNull(value.untrackedReferenceOwnerSlot());
        }
    }

    @Test
    void slotRemovalRetiresOnlyTheEdgesThatNoLongerExist() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar value = new RuntimeHash().createAnonymousReference();
            RuntimeArray array = new RuntimeArray();
            array.elements.add(value);
            assertSame(array, value.getContainerOwner());

            array.elements.remove(value);
            assertNull(value.getContainerOwner());

            RuntimeHash hash = new RuntimeHash();
            hash.elements.put("first", value);
            hash.elements.put("second", value);
            hash.elements.remove("first");
            assertSame(hash, value.getContainerOwner());

            hash.elements.remove("second");
            assertNull(value.getContainerOwner());
        }
    }

    @Test
    void dynamicStateRestoreRebindsOwnersToTheLiveAggregate() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar arrayValue = new RuntimeHash().createAnonymousReference();
            RuntimeArray array = new RuntimeArray();
            array.elements.add(arrayValue);
            array.dynamicSaveState();
            array.dynamicRestoreState();
            assertSame(array, arrayValue.getContainerOwner());

            RuntimeScalar hashValue = new RuntimeHash().createAnonymousReference();
            RuntimeHash hash = new RuntimeHash();
            hash.elements.put("value", hashValue);
            hash.dynamicSaveState();
            hash.dynamicRestoreState();
            assertSame(hash, hashValue.getContainerOwner());
        }
    }

    @Test
    void arrayIteratorRemovalRetiresTheContainerOwner() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar value = new RuntimeHash().createAnonymousReference();
            RuntimeArray array = new RuntimeArray();
            array.elements.add(value);

            var iterator = array.elements.iterator();
            assertSame(value, iterator.next());
            iterator.remove();

            assertNull(value.getContainerOwner());
        }
    }

    @Test
    void arrayBulkRemovalRetiresTheContainerOwner() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar value = new RuntimeHash().createAnonymousReference();
            RuntimeArray array = new RuntimeArray();
            array.elements.add(value);

            array.elements.removeAll(java.util.List.of(value));

            assertNull(value.getContainerOwner());
        }
    }

    @Test
    void arrayBulkReplacementUpdatesContainerOwners() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar oldValue = new RuntimeHash().createAnonymousReference();
            RuntimeScalar newValue = new RuntimeHash().createAnonymousReference();
            RuntimeArray array = new RuntimeArray();
            array.elements.add(oldValue);

            array.elements.replaceAll(ignoredValue -> newValue);

            assertNull(oldValue.getContainerOwner());
            assertSame(array, newValue.getContainerOwner());
        }
    }

    @Test
    void hashEntryViewRemovalRetiresTheContainerOwner() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar value = new RuntimeHash().createAnonymousReference();
            RuntimeHash hash = new RuntimeHash();
            hash.elements.put("value", value);

            var iterator = hash.elements.entrySet().iterator();
            assertSame(value, iterator.next().getValue());
            iterator.remove();

            assertNull(value.getContainerOwner());
        }
    }

    @Test
    void hashEntryReplacementUpdatesBothContainerOwners() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar oldValue = new RuntimeHash().createAnonymousReference();
            RuntimeScalar newValue = new RuntimeHash().createAnonymousReference();
            RuntimeHash hash = new RuntimeHash();
            hash.elements.put("value", oldValue);

            hash.elements.entrySet().iterator().next().setValue(newValue);

            assertNull(oldValue.getContainerOwner());
            assertSame(hash, newValue.getContainerOwner());
        }
    }

    @Test
    void hashValuesViewRemovalRetiresTheContainerOwner() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar value = new RuntimeHash().createAnonymousReference();
            RuntimeHash hash = new RuntimeHash();
            hash.elements.put("value", value);

            hash.elements.values().remove(value);

            assertNull(value.getContainerOwner());
        }
    }

    @Test
    void hashKeyViewRemovalRetiresTheContainerOwner() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar value = new RuntimeHash().createAnonymousReference();
            RuntimeHash hash = new RuntimeHash();
            hash.elements.put("value", value);

            var iterator = hash.elements.keySet().iterator();
            assertSame("value", iterator.next());
            iterator.remove();

            assertNull(value.getContainerOwner());
        }
    }

    @Test
    void hashComputeReplacementUpdatesContainerOwners() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar oldValue = new RuntimeHash().createAnonymousReference();
            RuntimeScalar newValue = new RuntimeHash().createAnonymousReference();
            RuntimeHash hash = new RuntimeHash();
            hash.elements.put("value", oldValue);

            hash.elements.computeIfPresent("value", (key, value) -> newValue);

            assertNull(oldValue.getContainerOwner());
            assertSame(hash, newValue.getContainerOwner());
        }
    }
}
