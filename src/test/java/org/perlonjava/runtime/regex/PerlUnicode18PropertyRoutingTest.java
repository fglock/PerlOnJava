package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Regression coverage for generated UCD data routing ahead of ICU4J 78.3. */
@Tag("unit")
class PerlUnicode18PropertyRoutingTest {
    private static final RegexFlags FLAGS = RegexFlags.fromModifiers("", "Unicode18");

    @Test
    void routesGeneratedBinaryAliasesBeforeOlderIcu() {
        assertMatches("\\p{XID_Start}", 0x3FC3F);
        assertMatches("\\p{isxidstart}", 0x3FC3F);
        assertMatches("\\p{xposixalpha}", 0x3FC3F);
        assertMatches("\\p{xposixalnum}", 0x3FC3F);
        assertMatches("\\p{Bidi_Mirrored}", 0x1DB1B);
    }

    @Test
    void routesGeneratedLineBreakValuesAndPerlWildcardsBeforeOlderIcu() {
        assertMatches("\\p{Line_Break=CJ}", 0x1B168);
        assertMatches("\\p{Line_Break=:\\AConditional_Japanese_Starter\\z:}", 0x1B168);
        assertMatches("\\p{Lb=:CJ:}", 0x1B168);
        assertMatches("\\p{Line_Break=E_Base}", 0x1FAFA);
        assertMatches("\\p{Line_Break: ebase}", 0x1FAFA);
        assertNotMatches("\\p{^Line_Break: ebase}", 0x1FAFA);
    }

    private static void assertMatches(String expression, int codePoint) {
        assertTrue(matches(expression, codePoint), expression);
    }

    private static void assertNotMatches(String expression, int codePoint) {
        assertFalse(matches(expression, codePoint), expression);
    }

    private static boolean matches(String expression, int codePoint) {
        return new JoniRegexPattern(expression, FLAGS)
                .matcher(new String(Character.toChars(codePoint)), List.of()).find();
    }
}
