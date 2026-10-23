package org.perlonjava.runtime.runtimetypes;

import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RuntimeHashStableMethodFieldCacheTest {

    @Test
    void normalMapWritesAdvanceStableMethodFieldRevision() {
        RuntimeHash hash = new RuntimeHash();
        long revision = hash.stableMethodFieldCacheRevision();
        assertTrue(revision >= 0);

        hash.elements.put("x", new RuntimeScalar(1));
        long afterPut = hash.stableMethodFieldCacheRevision();
        assertTrue(afterPut > revision);

        hash.elements.putAll(Map.of("y", new RuntimeScalar(2)));
        long afterPutAll = hash.stableMethodFieldCacheRevision();
        assertTrue(afterPutAll > afterPut);

        hash.elements.remove("x");
        long afterRemove = hash.stableMethodFieldCacheRevision();
        assertTrue(afterRemove > afterPutAll);

        hash.elements.clear();
        assertEquals(-1, hash.stableMethodFieldCacheRevision());
    }

    @Test
    void exposingMutableMapViewsDisablesStableMethodFieldCaching() {
        RuntimeHash entrySetHash = new RuntimeHash();
        entrySetHash.elements.entrySet();
        assertEquals(-1, entrySetHash.stableMethodFieldCacheRevision());

        RuntimeHash keySetHash = new RuntimeHash();
        keySetHash.elements.keySet();
        assertEquals(-1, keySetHash.stableMethodFieldCacheRevision());

        RuntimeHash valuesHash = new RuntimeHash();
        valuesHash.elements.values();
        assertEquals(-1, valuesHash.stableMethodFieldCacheRevision());
    }

    @Test
    void mapMutatorsWithoutRuntimeHashHooksDisableStableMethodFieldCaching() {
        RuntimeHash hash = new RuntimeHash();
        hash.elements.compute("x", (key, previous) -> new RuntimeScalar(1));

        assertEquals(-1, hash.stableMethodFieldCacheRevision());
        assertEquals(1, hash.elements.get("x").getInt());
    }
}
