package org.perlonjava.runtime.operators;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class PerlUtfStringFastPathTest {
    @Test
    void ordinaryJavaTextUsesTheNativeCodePointBoundaries() {
        String text = "A\uD83D\uDE00B\uD800C";

        assertEquals(5, PerlUtfString.codePointCountPerl(text));
        assertEquals(3, PerlUtfString.offsetByPerlCodePoints(text, 0, 2));
        assertEquals(text.length(), PerlUtfString.offsetByPerlCodePoints(text, 0, 99));
        assertEquals(5, PerlUtfString.lastLogicalCharacterStart(text));
    }

    @Test
    void syntheticPerlUvMarkersRetainOneLogicalCharacterSemantics() {
        String marker = PerlUtfString.encodeBeyondUnicode(0x11_0000L);
        String text = "A" + marker + "B";

        assertEquals(3, PerlUtfString.codePointCountPerl(text));
        assertEquals(1 + marker.length(), PerlUtfString.offsetByPerlCodePoints(text, 0, 2));
        assertEquals(1 + marker.length(), PerlUtfString.lastLogicalCharacterStart(text));
    }
}
