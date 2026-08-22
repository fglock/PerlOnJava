package org.perlonjava.runtime.regex;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.joni.Regex.ParsedProgramFeature.INLINE_ASCII_STRICT;
import static org.perlonjava.runtime.regex.JoniProgramFacts.has;

@Tag("unit")
class NativeAsciiStrictRoutingTest {
    @Test
    void recordsInlineAsciiStrictPatternsInJoni() {
        assertTrue(has("(?aa:sharp-s)", INLINE_ASCII_STRICT));
        assertTrue(has("(?iaa:sharp-s)", INLINE_ASCII_STRICT));
        assertTrue(has("(?^aa:sharp-s)", INLINE_ASCII_STRICT));
        assertTrue(has("outer(?i:(?aa:inner))", INLINE_ASCII_STRICT));
        assertTrue(has("(?aa)sharp-s", INLINE_ASCII_STRICT));
    }

    @Test
    void ignoresAsciiStrictLookalikes() {
        assertFalse(has("(?a:ordinary)", INLINE_ASCII_STRICT));
        assertFalse(has("\\(\\?aa:escaped\\)", INLINE_ASCII_STRICT));
        assertFalse(has("[(?aa:class)]", INLINE_ASCII_STRICT));
        assertFalse(has("\\Q(?aa:quoted)\\E", INLINE_ASCII_STRICT));
        assertFalse(has("(?# (?aa:commented))ordinary", INLINE_ASCII_STRICT));

        String extendedComment = "# (?aa:commented)\nordinary";
        RegexFlags extendedFlags = RegexFlags.fromModifiers("x", extendedComment);
        assertFalse(has(extendedComment, extendedFlags, INLINE_ASCII_STRICT));
    }
}
