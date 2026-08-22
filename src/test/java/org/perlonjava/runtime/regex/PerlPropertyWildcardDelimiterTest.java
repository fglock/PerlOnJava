package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joni.CharacterPropertyResolver;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PerlPropertyWildcardDelimiterTest {
    @Test
    void acceptsPairedParenthesisDelimiterAndReportsNoMatchingValue() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> UnicodeResolver.resolveJoniProperty(
                        "nv:(\\B(*COMMIT)C+)", false));
        assertTrue(error.getMessage().startsWith(
                "No Unicode property value wildcard matches"));
    }

    @Test
    void reportsSingleCharacterBracketDelimiterAsUnterminated() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> UnicodeResolver.resolveJoniProperty("upper:]", false));
        assertEquals("Unicode property wildcard not terminated", error.getMessage());
    }

    @Test
    void resolvesPerlInternalSurrogatePropertyInBothScalarDomains() {
        CharacterPropertyResolver.Result result =
                UnicodeResolver.resolveJoniProperty(
                        "utf8::_perl_surrogate", false);
        assertNotNull(result);
        assertArrayEquals(new int[] {1, 0xD800, 0xDFFF}, result.ranges);
        assertArrayEquals(new long[] {1, 0xD800L, 0xDFFFL}, result.wideRanges);
    }
}
