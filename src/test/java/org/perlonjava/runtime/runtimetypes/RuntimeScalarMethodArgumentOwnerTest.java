package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@Tag("unit")
class RuntimeScalarMethodArgumentOwnerTest {

    @Test
    void ordinaryArraysDoNotRetainCachedScalarOwners() {
        RuntimeScalar cachedLiteral = RuntimeScalarCache.getScalarInt(64);
        RuntimeArray array = new RuntimeArray(cachedLiteral);

        assertSame(cachedLiteral, array.elements.getFirst());
        assertNull(cachedLiteral.containerOwner,
                "a shared read-only scalar must not retain an ordinary array");
    }

    @Test
    void ordinaryHashesDoNotRetainCachedStringOwners() {
        int index = RuntimeScalarCache.getOrCreateStringIndex("container-owner-test");
        RuntimeScalar cachedLiteral = RuntimeScalarCache.getScalarString(index);
        RuntimeHash hash = new RuntimeHash();

        hash.put("literal", cachedLiteral);

        assertSame(cachedLiteral, hash.get("literal"));
        assertNull(cachedLiteral.containerOwner,
                "a shared read-only scalar must not retain an ordinary hash");
    }

    @Test
    void ordinaryArraysStillTrackReferenceOwners() {
        RuntimeScalar reference = new RuntimeArray().createReference();
        RuntimeArray array = new RuntimeArray(reference);

        assertSame(array, reference.containerOwner,
                "reference arguments retain their container for lifetime tracking");
    }
}
