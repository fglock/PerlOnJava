package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PerlUnicodeNamedSequenceDataTest {

    @Test
    void usesPinnedPerlUnicode17NamedSequences() {
        assertEquals("17.0.0", PerlUnicodeNamedSequenceData.UNICODE_VERSION);
        assertEquals("NamedSequences-17.0.0.txt",
                PerlUnicodeNamedSequenceData.SOURCE_FILE);
        assertEquals(461, PerlUnicodeNamedSequenceData.entryCount());
        assertEquals("9\uFE0F\u20E3",
                PerlUnicodeNamedSequenceData.sequence("KEYCAP DIGIT NINE"));
        assertEquals("\u0100\u0300", PerlUnicodeNamedSequenceData.sequence(
                "LATIN CAPITAL LETTER A WITH MACRON AND GRAVE"));
        assertNull(PerlUnicodeNamedSequenceData.sequence("LATIN CAPITAL LETTER A"));
        assertNull(PerlUnicodeNamedSequenceData.sequence("UNKNOWN SEQUENCE"));
        assertNull(PerlUnicodeNamedSequenceData.sequence(null));
    }

}
