package org.perlonjava.runtime.regex;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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

    @Test
    void acceptsPerlBarePropertiesAndWideOctalEscapes() {
        assertTrue(new JoniRegexPattern("\\pC", FLAGS)
                .matcher("\u0080", java.util.List.of()).find());
        assertFalse(new JoniRegexPattern("\\PC", FLAGS)
                .matcher("\u0080", java.util.List.of()).find());
        assertTrue(new JoniRegexPattern("\\400", FLAGS)
                .matcher("\u0100", java.util.List.of()).find());
    }

    @Test
    void translatesPerlOnlyUnicodePropertyAliasesBeforeJoniCompilation() {
        assertTrue(new JoniRegexPattern("\\p{Titlecase}", FLAGS)
                .matcher("\u01c5", java.util.List.of()).find());
        assertTrue(new JoniRegexPattern("\\p{XPosixSpace}", FLAGS)
                .matcher("\u00a0", java.util.List.of()).find());
        assertTrue(new JoniRegexPattern("\\p{PosixUpper}", FLAGS)
                .matcher("A", java.util.List.of()).find());
        assertTrue(new JoniRegexPattern("\\p{_Perl_IDStart}", FLAGS)
                .matcher("_", java.util.List.of()).find());
    }

    @Test
    void flattensTranslatedPropertiesInsideOrdinaryCharacterClasses() {
        JoniRegexPattern pattern = new JoniRegexPattern(
                "[\\p{IsDigit}\\p{IsLower}\\p{IsUpper}]", FLAGS);

        assertTrue(pattern.matcher("A", java.util.List.of()).find());
        assertTrue(pattern.matcher("7", java.util.List.of()).find());
        assertFalse(pattern.matcher("-", java.util.List.of()).find());
    }

    @Test
    void acceptsPerlNumericGBackrefsAndHexCodePoints() {
        assertTrue(new JoniRegexPattern("(a)(b)(c)\\g1\\g2\\g3", FLAGS)
                .matcher("abcabc", java.util.List.of()).find());
        assertTrue(new JoniRegexPattern("([[:ascii:]]+)\\x81", FLAGS)
                .matcher("b\u0081", java.util.List.of()).find());
    }

    @Test
    void acceptsWhitespaceInsidePerlIntervals() {
        assertTrue(new JoniRegexPattern("^a{ , 2 }$", FLAGS)
                .matcher("aa", java.util.List.of()).find());
        assertTrue(new JoniRegexPattern("\\p{Latin}{ , 2 }", FLAGS)
                .matcher("a", java.util.List.of()).find());
    }

    @Test
    void reusesImmutableInputEncodingAndPreservesSupplementaryOffsets() {
        String input = new String("A\u00E9\uD83D\uDE42Z");

        JoniRegexPattern.InputEncoding first = JoniRegexPattern.inputEncoding(input);
        JoniRegexPattern.InputEncoding second = JoniRegexPattern.inputEncoding(input);

        assertSame(first, second);
        assertArrayEquals(new int[] {0, 1, 3, 3, 7, 8}, first.charToByte());
        assertArrayEquals(new int[] {0, 1, 1, 2, 2, 2, 2, 4, 5}, first.byteToChar());
    }

    @Test
    void supplementaryCaptureUsesTheHighSurrogateBoundary() {
        RegexMatcher matcher = new JoniRegexPattern("(.)", FLAGS)
                .matcher("\uD83D\uDE42", java.util.List.of());

        assertTrue(matcher.find());
        assertEquals("\uD83D\uDE42", matcher.group(1));
        assertEquals(0, matcher.start(1));
        assertEquals(2, matcher.end(1));
    }

    @Test
    void resolvesBlockPropertiesInsideExtendedClassesBeforeJoniCompilation() {
        JoniRegexPattern pattern = new JoniRegexPattern(
                "(?[ [k] + \\p{Blk=ASCII} ])",
                RegexFlags.fromModifiers("i", "(?[ [k] + \\p{Blk=ASCII} ])"));

        assertTrue(pattern.matcher("k", java.util.List.of()).find());
        assertTrue(pattern.matcher("A", java.util.List.of()).find());
        assertFalse(pattern.matcher("\u017F", java.util.List.of()).find());
    }
}
