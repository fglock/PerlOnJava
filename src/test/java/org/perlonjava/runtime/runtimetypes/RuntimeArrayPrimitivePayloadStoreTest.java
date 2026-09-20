package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RuntimeArrayPrimitivePayloadStoreTest {
    @Test
    void plainNativeWordStoreRetainsTheFixedWidthPayloadWithoutObjectStorage() {
        RuntimeArray array = new RuntimeArray();

        RuntimeScalar element = array.setUnsignedWordElement(0, 123_456L);

        assertTrue(element.hasPrimitiveFlowInteger());
        assertEquals(123_456L, array.nativeIntegerElement(0));
    }
}
