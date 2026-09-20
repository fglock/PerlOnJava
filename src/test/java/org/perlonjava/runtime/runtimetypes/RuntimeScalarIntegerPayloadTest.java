package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.operators.BitwiseOperators;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RuntimeScalarIntegerPayloadTest {
    @Test
    void fixedWidthPayloadIncludesDeferredPrimitiveFlow() {
        RuntimeScalar scalar = new RuntimeScalar(17);
        assertTrue(scalar.hasFixedWidthIntegerPayload());
        assertEquals(17L, scalar.fixedWidthIntegerPayload());

        scalar.setPrimitiveFlowInteger(1_000_003L);
        assertTrue(scalar.hasFixedWidthIntegerPayload());
        assertEquals(1_000_003L, scalar.fixedWidthIntegerPayload());
    }

    @Test
    void wideIntegerPayloadStaysOutsideFixedWidthAccess() {
        RuntimeScalar scalar = new RuntimeScalar(BigInteger.ONE.shiftLeft(80));
        assertFalse(scalar.hasFixedWidthIntegerPayload());
        assertTrue(scalar.hasWideIntegerPayload());
        assertThrows(IllegalStateException.class, scalar::fixedWidthIntegerPayload);
    }

    @Test
    void coreNumericReadersUseTheDeferredPayloadInsteadOfTheBoxedSentinel() {
        RuntimeScalar scalar = new RuntimeScalar(0);
        scalar.setPrimitiveFlowInteger(0x1234L);

        RuntimeArray array = new RuntimeArray();
        array.elements.add(scalar);
        assertEquals(0x1234L, array.nativeIntegerElement(0));
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            assertEquals(0x1230L,
                    BitwiseOperators.bitwiseAnd(scalar, new RuntimeScalar(0xfff0)).getLong());
        }
    }
}
