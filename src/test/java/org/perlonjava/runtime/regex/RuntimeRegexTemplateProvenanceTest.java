package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;

@Tag("unit")
class RuntimeRegexTemplateProvenanceTest {
    @Test
    public void retainsByteProvenanceAcrossByteCompatibleParts() {
        RuntimeScalar result = RuntimeRegexTemplate.build(new RuntimeList(
                new RuntimeScalar(new byte[] {'^'}),
                new RuntimeScalar(new byte[] {(byte) 0xdf}),
                new RuntimeScalar(new byte[] {'$'})));

        assertEquals(RuntimeScalarType.BYTE_STRING, result.type);
        assertEquals("^\u00df$", result.toString());
    }

    @Test
    public void upgradesWhenAnyPartIsACharacterString() {
        RuntimeScalar result = RuntimeRegexTemplate.build(new RuntimeList(
                new RuntimeScalar(new byte[] {'^'}),
                new RuntimeScalar("\u00df"),
                new RuntimeScalar(new byte[] {'$'})));

        assertEquals(RuntimeScalarType.STRING, result.type);
        assertEquals("^\u00df$", result.toString());
    }
}
