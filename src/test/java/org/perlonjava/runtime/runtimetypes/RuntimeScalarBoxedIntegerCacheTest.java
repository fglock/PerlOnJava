package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

@Tag("unit")
class RuntimeScalarBoxedIntegerCacheTest {

    @Test
    void reusesOnlyTheImmutableBoxedPayload() {
        int value = 12_345;
        Integer firstBox = RuntimeScalarCache.getBoxedInteger(value);
        Integer secondBox = RuntimeScalarCache.getBoxedInteger(value);

        RuntimeScalar first = new RuntimeScalar((long) value);
        RuntimeScalar second = new RuntimeScalar((long) value);

        assertSame(firstBox, secondBox);
        assertSame(firstBox, first.value);
        assertSame(first.value, second.value);
        assertNotSame(first, second, "Perl scalar cells must remain independent and writable");

        first.set(value + 1);
        assertEquals(value + 1, first.getLong());
        assertEquals(value, second.getLong());
    }

    @Test
    void collidingIntegersNeverReturnTheWrongPayload() {
        int first = 23_456;
        int collision = first + (1 << 16);

        assertEquals(first, RuntimeScalarCache.getBoxedInteger(first).intValue());
        assertEquals(collision, RuntimeScalarCache.getBoxedInteger(collision).intValue());
        assertEquals(first, RuntimeScalarCache.getBoxedInteger(first).intValue());
    }
}
