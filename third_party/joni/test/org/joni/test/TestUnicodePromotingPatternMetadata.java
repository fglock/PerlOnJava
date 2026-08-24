/*
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do
 * so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.joni.test;

import static org.joni.constants.SyntaxProperties.ALLOW_MULTIPLEX_DEFINITION_NAME_CALL;
import static org.joni.constants.SyntaxProperties.OP2_ESC_CAPITAL_Q_QUOTE;
import static org.joni.constants.SyntaxProperties.OP2_ESC_H_HORIZONTAL_WHITESPACE;
import static org.joni.constants.SyntaxProperties.OP2_OPTION_PERL;
import static org.joni.constants.SyntaxProperties.OP2_OPTION_RUBY;
import static org.joni.constants.SyntaxProperties.OP2_PLUS_POSSESSIVE_INTERVAL;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.jcodings.Encoding;
import org.jcodings.specific.UTF8Encoding;
import org.joni.NamedCharacterResolver;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Regex.ParsedProgramFeature;
import org.joni.Syntax;
import org.joni.WarnCallback;
import org.joni.exception.SyntaxException;
import org.junit.Test;

public class TestUnicodePromotingPatternMetadata {
    private static final NamedCharacterResolver NAMED =
            new NamedCharacterResolver() {
                @Override
                public int resolve(byte[] bytes, int p, int end,
                        Encoding encoding) {
                    return resolveSequence(bytes, p, end, encoding)[0];
                }

                @Override
                public int[] resolveSequence(byte[] bytes, int p, int end,
                        Encoding encoding) {
                    String name = new String(bytes, p, end - p,
                            StandardCharsets.UTF_8);
                    return new int[] {name.equals("BYTE") ? 0xff : 0x100};
                }
            };

    private static final Syntax SYNTAX = new Syntax(
            "UnicodePromotingPatternMetadata", Syntax.RUBY.op,
            (Syntax.RUBY.op2 & ~OP2_OPTION_RUBY) | OP2_OPTION_PERL
                    | OP2_PLUS_POSSESSIVE_INTERVAL
                    | OP2_ESC_H_HORIZONTAL_WHITESPACE
                    | OP2_ESC_CAPITAL_Q_QUOTE,
            Syntax.RUBY.op3,
            Syntax.RUBY.behavior | ALLOW_MULTIPLEX_DEFINITION_NAME_CALL,
            Syntax.RUBY.options & ~(Option.ASCII_RANGE
                    | Option.POSIX_BRACKET_ALL_RANGE
                    | Option.WORD_BOUND_ALL_RANGE),
            Syntax.RUBY.metaCharTable, NAMED);

    @Test
    public void marksLiteralAndNumericPromotionBoundaries() {
        assertNoPromotion("\u00ff");
        assertPromotion("\u0100");
        assertNoPromotion("[\u00ff]");
        assertPromotion("[\u0100]");
        assertNoPromotion("\\Q\u00ff\\E");
        assertPromotion("\\Q\u0100\\E");

        assertNoPromotion("\\x{ff}");
        assertPromotion("\\x{100}");
        assertNoPromotion("[\\x{ff}]");
        assertPromotion("[\\x{100}]");
        assertNoPromotion("\\o{377}");
        assertPromotion("\\o{400}");
        assertNoPromotion("[\\o{377}]");
        assertPromotion("[\\o{400}]");
    }

    @Test
    public void marksNamedSyntaxRegardlessOfResolvedValue() {
        assertPromotion("\\N{BYTE}");
        assertPromotion("[\\N{BYTE}]");
        assertPromotion("\\N{WIDE}");
        assertPromotion("[\\N{WIDE}]");
    }

    @Test
    public void marksOnlyOutsideClassPropertySyntax() {
        assertPromotion("\\p{Lowercase}");
        assertPromotion("\\P{Lowercase}");
        assertNoPromotion("[\\p{Lowercase}]");
        assertNoPromotion("[\\P{Lowercase}]");
        assertNoPromotion("(?[ \\p{Lowercase} ])");
    }

    @Test
    public void excludesEscapedQuotedAndCommentedLookalikes() {
        assertNoPromotion("\\\\p\\{Lowercase\\}");
        assertNoPromotion("(?# \\p{Lowercase})A");
        assertNoPromotion("\\Q\\p{Lowercase}\\E");
        assertNoPromotion("# \\p{Lowercase}\nA", Option.EXTEND);
    }

    @Test
    public void metadataSetRemainsImmutable() {
        Regex regex = compile("\\x{100}", Option.NONE);
        assertThrows(UnsupportedOperationException.class,
                () -> regex.getParsedProgramMetadata().features().clear());
        assertTrue(regex.getParsedProgramMetadata().has(
                ParsedProgramFeature.UNICODE_PROMOTING_PATTERN_SYNTAX));
    }

    @Test
    public void preservesPromotionFactOnAParserFailureAfterTheToken() {
        String source = "\\x{100}(";
        SyntaxException error = assertThrows(SyntaxException.class,
                () -> compile(source, Option.NONE));
        assertTrue(error.getDiagnosticMessage().contains(
                "unmatched parenthesis"));
        assertEquals(source.length(), error.getPatternPosition());
        assertTrue(error.getParsedProgramMetadata().has(
                ParsedProgramFeature.UNICODE_PROMOTING_PATTERN_SYNTAX));
    }

    private static void assertPromotion(String source) {
        assertPromotion(source, Option.NONE);
    }

    private static void assertPromotion(String source, int option) {
        assertTrue(source, compile(source, option).getParsedProgramMetadata()
                .has(ParsedProgramFeature.UNICODE_PROMOTING_PATTERN_SYNTAX));
    }

    private static void assertNoPromotion(String source) {
        assertNoPromotion(source, Option.NONE);
    }

    private static void assertNoPromotion(String source, int option) {
        assertFalse(source, compile(source, option).getParsedProgramMetadata()
                .has(ParsedProgramFeature.UNICODE_PROMOTING_PATTERN_SYNTAX));
    }

    private static Regex compile(String source, int option) {
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        return new Regex(bytes, 0, bytes.length, option,
                UTF8Encoding.INSTANCE, SYNTAX, WarnCallback.NONE);
    }
}
