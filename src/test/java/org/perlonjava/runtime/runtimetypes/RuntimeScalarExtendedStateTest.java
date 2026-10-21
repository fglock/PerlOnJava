package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@Tag("unit")
class RuntimeScalarExtendedStateTest {

    @Test
    void ordinaryScalarsDoNotAllocateRareWatcherState() {
        RuntimeScalar scalar = new RuntimeScalar(12_345);

        assertNull(scalar.extendedState,
                "ordinary scalar values should not allocate watcher/substr metadata");

        scalar.addModifiedWatcher(1L, RuntimeScalarCache.scalarTrue);

        assertNotNull(scalar.extendedState,
                "adding a watcher should lazily allocate the rare scalar metadata");
    }
}
