package org.perlonjava.runtime.runtimetypes;

import java.util.HashMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RuntimeHashStableMethodFieldCacheTest {

    @Test
    void reusesCellsUntilTheirMapBindingsChange() {
        RuntimeHash hash = new RuntimeHash();
        RuntimeScalar first = new RuntimeScalar(1);
        RuntimeScalar second = new RuntimeScalar(2);
        hash.elements.put("x", first);
        hash.elements.put("y", second);

        assertTrue(hash.prepareStableMethodFieldCache("x", "y"));
        assertSame(first, hash.stableMethodFieldCacheFirstCell());
        assertSame(second, hash.stableMethodFieldCacheSecondCell());

        first.set(10);
        assertTrue(hash.prepareStableMethodFieldCache("x", "y"));
        assertSame(first, hash.stableMethodFieldCacheFirstCell(),
                "in-place scalar mutation must keep the cached hash cell valid");

        RuntimeScalar replacement = new RuntimeScalar(20);
        hash.elements.put("x", replacement);
        assertTrue(hash.prepareStableMethodFieldCache("x", "y"));
        assertSame(replacement, hash.stableMethodFieldCacheFirstCell(),
                "put must invalidate a cached binding even when the key already exists");

        hash.elements.remove("y");
        assertTrue(hash.prepareStableMethodFieldCache("x", "y"));
        assertNull(hash.stableMethodFieldCacheSecondCell(),
                "remove must invalidate a cached binding");
    }

    @Test
    void putAllAndBackingMapReplacementCannotLeaveStaleCells() {
        RuntimeHash hash = new RuntimeHash();
        RuntimeScalar original = new RuntimeScalar(1);
        hash.elements.put("x", original);
        assertTrue(hash.prepareStableMethodFieldCache("x", null));

        RuntimeScalar replacement = new RuntimeScalar(2);
        HashMap<String, RuntimeScalar> values = new HashMap<>();
        values.put("x", replacement);
        hash.elements.putAll(values);
        assertTrue(hash.prepareStableMethodFieldCache("x", null));
        assertSame(replacement, hash.stableMethodFieldCacheFirstCell());

        hash.elements = new HashMap<>(values);
        assertFalse(hash.prepareStableMethodFieldCache("x", null),
                "untracked replacement maps must use the ordinary lookup path");
    }

    @Test
    void exposingMutableViewsDisablesTheCache() {
        RuntimeHash hash = new RuntimeHash();
        RuntimeScalar original = new RuntimeScalar(1);
        hash.elements.put("x", original);
        assertTrue(hash.prepareStableMethodFieldCache("x", null));

        RuntimeScalar replacement = new RuntimeScalar(3);
        hash.elements.entrySet().iterator().next().setValue(replacement);

        assertFalse(hash.prepareStableMethodFieldCache("x", null),
                "entry mutation bypasses map overrides, so exposed views disable caching");
    }

    @Test
    void lessCommonMapMutationApisDisableTheCache() {
        RuntimeHash hash = new RuntimeHash();
        hash.elements.put("x", new RuntimeScalar(1));
        assertTrue(hash.prepareStableMethodFieldCache("x", null));

        hash.elements.compute("x", (key, value) -> new RuntimeScalar(4));

        assertFalse(hash.prepareStableMethodFieldCache("x", null),
                "compute may bypass put overrides and must disable caching");
    }
}
