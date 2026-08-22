package org.perlonjava.runtime.regex;

import org.perlonjava.runtime.operators.PerlUtfString;
import org.perlonjava.runtime.runtimetypes.PerlRuntime;
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
    void nativeDynamicCalloutSourceAndMetadataRemainVisible() {
        String source = "(?{=DYNAMIC:0})";
        JoniRegexPattern pattern = new JoniRegexPattern(source, FLAGS);

        assertEquals(source, pattern.patternDescription());
        assertTrue(pattern.engineRegex().getParsedProgramMetadata().has(
                org.joni.Regex.ParsedProgramFeature.DYNAMIC_CALLOUT));
    }

    @Test
    void structuredTemplatePreservesItsValidatedDynamicCallout() {
        JoniRegexPattern pattern = new JoniRegexPattern("(?{=DYNAMIC:0})", FLAGS, 1);

        assertEquals("(?{=DYNAMIC:0})", pattern.patternDescription());
        assertTrue(pattern.engineRegex().getParsedProgramMetadata().has(
                org.joni.Regex.ParsedProgramFeature.DYNAMIC_CALLOUT));
    }

    @Test
    void nativeDescriptionDoesNotRewriteCalloutTextByCallbackCount() {
        JoniRegexPattern pattern = new JoniRegexPattern("(?{=DYNAMIC:1})", FLAGS, 1);

        assertEquals("(?{=DYNAMIC:1})", pattern.patternDescription());
        assertTrue(pattern.engineRegex().getParsedProgramMetadata().has(
                org.joni.Regex.ParsedProgramFeature.DYNAMIC_CALLOUT));
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
    void nativeInlineModifiersPreserveSourceAndSemantics() {
        JoniRegexPattern reset = new JoniRegexPattern(
                "(?^im:hello.*world)", FLAGS);
        JoniRegexPattern dotAll = new JoniRegexPattern("(?s:a.b)", FLAGS);
        JoniRegexPattern empty = new JoniRegexPattern("(?)Market", FLAGS);

        assertEquals("(?^im:hello.*world)", reset.patternDescription());
        assertTrue(reset.matcher("HELLO--WORLD", java.util.List.of()).find());
        assertEquals("(?s:a.b)", dotAll.patternDescription());
        assertTrue(dotAll.matcher("a\nb", java.util.List.of()).find());
        assertEquals("(?)Market", empty.patternDescription());
        assertTrue(empty.matcher("Market", java.util.List.of()).find());
    }

    @Test
    void nativeAbbreviatedMarkPreservesSourceAndControlState() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            JoniRegexPattern pattern = new JoniRegexPattern("(*:B)A", FLAGS);
            RegexMatcher matcher = pattern.matcher("A", java.util.List.of());

            assertEquals("(*:B)A", pattern.patternDescription());
            assertTrue(matcher.find());
            assertEquals("B", matcher.controlMark());
        }
    }

    @Test
    void nativeNamedCharactersAndDoubleExtendedClassesPreserveSource() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            String namedSource = "caf\\N{LATIN SMALL LETTER E WITH ACUTE}";
            JoniRegexPattern named = new JoniRegexPattern(namedSource, FLAGS);
            assertEquals(namedSource, named.patternDescription());
            assertTrue(named.matcher("café", java.util.List.of()).find());
            JoniRegexPattern pattern = new JoniRegexPattern("[ a b c ]",
                    RegexFlags.fromModifiers("xx", "[ a b c ]"));
            assertEquals("[ a b c ]", pattern.patternDescription());
            assertTrue(pattern.matcher("b", java.util.List.of()).find());
            assertFalse(pattern.matcher(" ", java.util.List.of()).find());
        }
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
    void passesPinnedGeneralCategoriesToTheJoniParserWithoutTextExpansion() {
        JoniRegexPattern category = new JoniRegexPattern("\\p{gc=Uppercase_Letter}", FLAGS);

        assertEquals("\\p{gc=Uppercase_Letter}", category.patternDescription());
        assertTrue(category.matcher("A", java.util.List.of()).find());
        assertFalse(category.matcher("a", java.util.List.of()).find());
        JoniRegexPattern folded = new JoniRegexPattern("\\p{gc=Uppercase_Letter}",
                RegexFlags.fromModifiers("i", "\\p{gc=Uppercase_Letter}"));
        JoniRegexPattern foldedNegated = new JoniRegexPattern("\\P{gc=Uppercase_Letter}",
                RegexFlags.fromModifiers("i", "\\P{gc=Uppercase_Letter}"));
        assertTrue(folded.matcher("a", java.util.List.of()).find());
        assertFalse(foldedNegated.matcher("A", java.util.List.of()).find());
    }

    @Test
    void preservesStandaloneBlockAndScriptFoldPolicyInsideJoni() {
        RegexFlags ignoreCase = RegexFlags.fromModifiers("i", "property");
        JoniRegexPattern block = new JoniRegexPattern("\\p{Block=ASCII}", ignoreCase);
        JoniRegexPattern script = new JoniRegexPattern("\\p{Script=Common}", ignoreCase);

        assertEquals("\\p{Block=ASCII}", block.patternDescription());
        assertEquals("\\p{Script=Common}", script.patternDescription());
        assertFalse(block.matcher("\u212A", java.util.List.of()).find());
        assertFalse(script.matcher("K", java.util.List.of()).find());
    }

    @Test
    void passesRemainingExactEnumeratedPropertiesToJoni() {
        String[][] cases = {
                {"\\p{Canonical_Combining_Class=Above}", "\u0301"},
                {"\\p{Bidi_Class=Right_To_Left}", "\u05D0"},
                {"\\p{Decomposition_Type=Canonical}", "\u00C0"},
                {"\\p{East_Asian_Width=Fullwidth}", "\u3000"},
                {"\\p{Numeric_Value=1/2}", "\u00BD"},
                {"\\p{Joining_Group=Alef}", "\u0627"},
        };

        for (String[] testCase : cases) {
            JoniRegexPattern pattern = new JoniRegexPattern(testCase[0], FLAGS);
            assertEquals(testCase[0], pattern.patternDescription());
            assertTrue(pattern.matcher(testCase[1], java.util.List.of()).find());
        }
    }

    @Test
    void passesExactAgePropertiesToJoniWithoutTextExpansion() {
        String[][] cases = {
                {"\\p{Age=2.1}", "\u20AC"},
                {"\\p{In=3.0}", "\u20AC"},
                {"\\p{Present_In=3.0}", "\u20AC"},
                {"\\p{Is_Age=6.1}", "\uD83D\uDE00"},
                {"\\p{Age=Unassigned}", "\uD88D\uDC7A"},
        };

        for (String[] testCase : cases) {
            JoniRegexPattern pattern = new JoniRegexPattern(testCase[0], FLAGS);
            assertEquals(testCase[0], pattern.patternDescription());
            assertTrue(pattern.matcher(testCase[1], java.util.List.of()).find());
        }

        JoniRegexPattern wildcard = new JoniRegexPattern(
                "\\p{Age=:\\AV16_0\\z:}", FLAGS);
        assertEquals("\\p{Age=:\\AV16_0\\z:}", wildcard.patternDescription());
        assertTrue(wildcard.hasCharacterProperty());
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
    void matchesStandaloneBeyondUnicodeCharacterClassMembers() {
        JoniRegexPattern pattern = new JoniRegexPattern(
                "[\\x{4000001}\\x{4000003}\\x{4000005}]+", FLAGS);

        assertFalse(pattern.matcher(PerlUtfString.encodeBeyondUnicode(0x4000000L),
                java.util.List.of()).find());
        assertTrue(pattern.matcher(PerlUtfString.encodeBeyondUnicode(0x4000001L),
                java.util.List.of()).find());
        assertTrue(pattern.matcher(PerlUtfString.encodeBeyondUnicode(0x4000003L),
                java.util.List.of()).find());
        assertTrue(pattern.matcher(PerlUtfString.encodeBeyondUnicode(0x4000005L),
                java.util.List.of()).find());
        assertFalse(pattern.matcher(PerlUtfString.encodeBeyondUnicode(0x4000006L),
                java.util.List.of()).find());
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

    @Test
    void resolvesPerlPropertyAliasesInsideExtendedClasses() {
        JoniRegexPattern pattern = new JoniRegexPattern(
                "(?[ [A] + \\p{Titlecase} ])", FLAGS);

        assertTrue(pattern.matcher("A", java.util.List.of()).find());
        assertTrue(pattern.matcher("\u01C5", java.util.List.of()).find());
        assertFalse(pattern.matcher("z", java.util.List.of()).find());
    }

    @Test
    void resolvesControlEscapesInsideExtendedClasses() {
        JoniRegexPattern pattern = new JoniRegexPattern("(?[\\c\\])", FLAGS);

        assertTrue(pattern.matcher("\u001C", java.util.List.of()).find());
        assertFalse(pattern.matcher("\\", java.util.List.of()).find());
    }
}
