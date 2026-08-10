package org.perlonjava.runtime.regex;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class MultiCharFoldMapperTest {
    @Test
    void includesLowercaseCharactersWithFullFolds() {
        assertEquals("j\u030C", MultiCharFoldMapper.getMultiCharFold(0x01F0));
        assertEquals("\u03B9\u0308\u0301", MultiCharFoldMapper.getMultiCharFold(0x0390));
    }

    @Test
    void includesEveryCodePointSharingAFullFold() {
        assertTrue(MultiCharFoldMapper.getReverseFolds("ss").containsAll(List.of(0x00DF, 0x1E9E)));
        assertTrue(MultiCharFoldMapper.expandToAlternation(0x00DF).contains("\u1E9E"));
    }

    @Test
    void supplementsSimpleFoldsMissingFromJavaPattern() {
        assertTrue(MultiCharFoldMapper.getSimpleFoldVariants(0xA7CE).contains(0xA7CF));
        assertTrue(MultiCharFoldMapper.getSimpleFoldVariants(0x16EA0).contains(0x16EBB));
    }
}
