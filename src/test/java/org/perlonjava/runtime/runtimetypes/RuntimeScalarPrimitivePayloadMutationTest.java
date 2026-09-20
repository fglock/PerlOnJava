package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RuntimeScalarPrimitivePayloadMutationTest {
    @Test
    void truthAndMutationUseTheDeferredIntegerPayload() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeScalar scalar = new RuntimeScalar(0);

            scalar.setPrimitiveFlowInteger(7);
            assertTrue(scalar.getBoolean());
            assertTrue(scalar.getBooleanNoOverload());
            assertEquals(8L, scalar.preAutoIncrement().getLong());

            scalar.setPrimitiveFlowInteger(7);
            assertEquals(7L, scalar.postAutoIncrement().getLong());
            assertEquals(8L, scalar.getLong());

            scalar.setPrimitiveFlowInteger(7);
            assertEquals(6L, scalar.preAutoDecrement().getLong());

            scalar.setPrimitiveFlowInteger(7);
            assertEquals(7L, scalar.postAutoDecrement().getLong());
            assertEquals(6L, scalar.getLong());
        }
    }

    @Test
    void stringIncrementUsesTheCanonicalIntegerWrite() {
        RuntimeScalar empty = new RuntimeScalar("");
        ScalarUtils.stringIncrement(empty);
        assertEquals(1L, empty.getLong());
        assertTrue(empty.hasFixedWidthIntegerPayload());

        RuntimeScalar decimal = new RuntimeScalar("41");
        ScalarUtils.stringIncrement(decimal);
        assertEquals(42L, decimal.getLong());
        assertTrue(decimal.hasFixedWidthIntegerPayload());
    }

    @Test
    void objectObservationMaterializesTheDeferredPayload() {
        RuntimeScalar scalar = new RuntimeScalar(0);
        scalar.setPrimitiveFlowInteger(1_000_003L);

        assertEquals(1_000_003L, ((Number) scalar.materializeObjectPayload()).longValue());
        assertTrue(!scalar.hasPrimitiveFlowInteger());
        assertEquals(1_000_003L, scalar.getLong());
    }

    @Test
    void scalarAssignmentCopiesTheActivePayloadWithoutFlushingTheSource() {
        RuntimeScalar source = new RuntimeScalar(0);
        source.setPrimitiveFlowInteger(73);
        RuntimeScalar target = new RuntimeScalar(0);

        target.set(source);

        assertEquals(73L, target.getLong());
        assertTrue(source.hasPrimitiveFlowInteger());
        assertEquals(73L, source.getLong());
    }
}
