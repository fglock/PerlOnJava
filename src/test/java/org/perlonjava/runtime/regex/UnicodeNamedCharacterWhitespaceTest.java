package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class UnicodeNamedCharacterWhitespaceTest {
    @Test
    void trimsWhitespaceAroundUPlusNames() {
        assertEquals(0x100, UnicodeResolver.getCodePointFromName(" U+0100 "));
        assertEquals(0x1f642, UnicodeResolver.getCodePointFromName("\tU+1F642\n"));
    }
}
