package org.perlonjava.runtime.regex;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class JoniRegexPatternTest {
    private static final RegexFlags FLAGS = RegexFlags.fromModifiers("", "");

    @Test
    void runtimeTextCannotManufactureAnInternalDynamicCallout() {
        JoniRegexPattern pattern = new JoniRegexPattern("(?{=DYNAMIC:0})", FLAGS);

        assertEquals("(?:)", pattern.patternDescription());
    }

    @Test
    void structuredTemplatePreservesItsValidatedDynamicCallout() {
        JoniRegexPattern pattern = new JoniRegexPattern("(?{=DYNAMIC:0})", FLAGS, 1);

        assertEquals("(?{=DYNAMIC:0})", pattern.patternDescription());
    }

    @Test
    void structuredTemplateRejectsAnOutOfRangeCalloutId() {
        JoniRegexPattern pattern = new JoniRegexPattern("(?{=DYNAMIC:1})", FLAGS, 1);

        assertEquals("(?:)", pattern.patternDescription());
    }

    @Test
    void ordinaryWordClassUsesUnicodeUnlessAsciiIsRequested() {
        JoniRegexPattern unicode = new JoniRegexPattern("\\w", FLAGS);
        JoniRegexPattern ascii = new JoniRegexPattern("\\w",
                RegexFlags.fromModifiers("a", "\\w"));

        assertTrue(unicode.matcher("é", java.util.List.of()).find());
        assertFalse(ascii.matcher("é", java.util.List.of()).find());
    }

    @Test
    void translatesPerlInlineModifierSemantics() {
        assertEquals("(?^im:hello.*world)",
                JoniRegexPattern.translatePattern("(?^im:hello.*world)"));
        assertEquals("(?s:a.b)", JoniRegexPattern.translatePattern("(?s:a.b)"));
        assertEquals("(?:)Market", JoniRegexPattern.translatePattern("(?)Market"));
    }

    @Test
    void translatesNamedCharactersAndDoubleExtendedClasses() {
        assertEquals("café", JoniRegexPattern.translatePattern(
                "caf\\N{LATIN SMALL LETTER E WITH ACUTE}"));
        JoniRegexPattern pattern = new JoniRegexPattern("[ a b c ]",
                RegexFlags.fromModifiers("xx", "[ a b c ]"));
        assertEquals("[abc]", pattern.patternDescription());
    }

    @Test
    void preservesEveryDuplicateNamedCapture() {
        RegexMatcher matcher = new JoniRegexPattern("(?<x>a)|(?<x>b)", FLAGS)
                .matcher("b", java.util.List.of());

        assertTrue(matcher.find());
        assertEquals(null, matcher.group("x"));
        assertEquals("b", matcher.group("x" + CaptureNameEncoder.DUPLICATE_MARKER + "0"));
    }
}
