package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class RuntimeScalarPrimitivePayloadProxyTest {
    @Test
    void arrayProxySynchronizationCopiesTheActivePayload() {
        RuntimeScalar element = new RuntimeScalar(0);
        element.setPrimitiveFlowInteger(12);
        RuntimeArrayProxyEntry proxy = new RuntimeArrayProxyEntry(new RuntimeArray(), 0);
        proxy.lvalue = element;

        proxy.preAutoIncrement();

        assertEquals(13L, element.getLong());
        assertEquals(13L, proxy.getLong());
    }
}
