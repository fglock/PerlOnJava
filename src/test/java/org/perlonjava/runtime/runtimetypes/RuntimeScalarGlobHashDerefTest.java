package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

class RuntimeScalarGlobHashDerefTest {

    @Tag("unit")
    @Test
    void hashDereferenceAcceptsHashPayloadStoredOnGlobScalar() {
        RuntimeHash hash = new RuntimeHash();
        hash.put("1", new RuntimeScalar("value"));

        RuntimeScalar globScalar = new RuntimeScalar();
        globScalar.type = RuntimeScalarType.GLOB;
        globScalar.value = hash;

        assertSame(hash, globScalar.hashDeref());
    }
}
