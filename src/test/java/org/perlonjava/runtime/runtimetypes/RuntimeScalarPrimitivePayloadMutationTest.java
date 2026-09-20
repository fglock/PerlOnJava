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
}
