package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class SharedPerlStorageDestructiveShareTest {

    @Test
    void publicSharePreservesScalarsButClearsAggregateStorage() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar scalar = new RuntimeScalar(7);
            SharedPerlStorage.share(scalar);
            assertTrue(scalar.threadShared);
            assertEquals(7, scalar.getInt());

            RuntimeArray array = new RuntimeArray();
            array.push(new RuntimeScalar(1));
            array.push(new RuntimeScalar(2));
            SharedPerlStorage.share(array.createReference());
            assertTrue(array.threadShared);
            assertTrue(array.isEmpty());
            array.push(new RuntimeScalar(3));
            SharedPerlStorage.share(array.createReference());
            assertTrue(array.isEmpty());

            RuntimeHash hash = new RuntimeHash();
            hash.put("value", new RuntimeScalar(1));
            hash.blessId = NameNormalizer.getBlessId("SharedDestructiveObject");
            RuntimeScalar objectVariable = hash.createReference();
            SharedPerlStorage.share(objectVariable.createReference());
            assertTrue(hash.threadShared);
            assertTrue(hash.elements.isEmpty());
            assertEquals("SharedDestructiveObject", NameNormalizer.getBlessStr(hash.blessId));
        }
    }

    @Test
    void sharedClonePreservesRecursiveCopyWithoutSharingSource() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray nested = new RuntimeArray();
            nested.push(new RuntimeScalar(2));
            nested.push(new RuntimeScalar(3));
            RuntimeHash source = new RuntimeHash();
            source.put("value", new RuntimeScalar(1));
            source.put("nested", nested.createReference());

            RuntimeScalar cloneReference = SharedPerlStorage.sharedClone(source.createReference());
            RuntimeHash clone = (RuntimeHash) cloneReference.value;
            RuntimeArray clonedNested = (RuntimeArray) clone.get("nested").value;

            assertNotSame(source, clone);
            assertFalse(source.threadShared);
            assertFalse(nested.threadShared);
            assertTrue(clone.threadShared);
            assertTrue(clonedNested.threadShared);
            assertEquals(1, clone.get("value").getInt());
            assertEquals(2, clonedNested.size());
        }
    }
}
