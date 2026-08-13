package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("unit")
class SharedPerlStorageGraphTest {
    @Test
    void rejectedNestedNodeDoesNotPartiallyPublishSharedGraph() {
        RuntimeHash root = new RuntimeHash();
        RuntimeArray acceptedPrefix = new RuntimeArray();
        acceptedPrefix.push(new RuntimeScalar(1));
        RuntimeHash unsupported = new RuntimeHash();
        unsupported.blessId = 1;

        root.put("accepted", acceptedPrefix.createReference());
        root.put("unsupported", unsupported.createReference());

        assertThrows(IllegalArgumentException.class,
                () -> SharedPerlStorage.shareValue(root));
        assertFalse(root.threadShared);
        assertFalse(acceptedPrefix.threadShared);
        assertFalse(acceptedPrefix.get(0).threadShared);
        assertFalse(unsupported.threadShared);
    }
}
