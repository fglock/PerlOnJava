package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RuntimeScalarPrimitiveFlowTest {
    @Test
    void primitiveFlowPayloadIsVisibleAndFlushesToOrdinaryStorage() {
        RuntimeScalar scalar = new RuntimeScalar(1);
        scalar.setPrimitiveFlowInteger(1_000_003L);

        assertTrue(scalar.hasPrimitiveFlowInteger());
        assertEquals(1_000_003L, scalar.getLong());
        assertEquals("1000003", scalar.toString());

        scalar.flushPrimitiveFlowInteger();
        assertFalse(scalar.hasPrimitiveFlowInteger());
        assertEquals(1_000_003L, scalar.getLong());

        scalar.set(7L);
        assertFalse(scalar.hasPrimitiveFlowInteger());
        assertEquals(7L, scalar.getLong());
    }
}
