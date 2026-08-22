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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.joni.constants.SyntaxProperties.OP2_OPTION_PERL;
import static org.joni.constants.SyntaxProperties.OP2_OPTION_RUBY;
import static org.joni.constants.SyntaxProperties.OP3_PERL_LITERAL_OPEN_IN_CC;
import static org.joni.constants.SyntaxProperties.OP_POSIX_BRACKET;

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.UTF8Encoding;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Regex.ParsedProgramFeature;
import org.joni.Syntax;
import org.joni.exception.SyntaxException;
import org.junit.Test;

public class TestRegexParsedProgramMetadata {
    private static final Syntax SYNTAX = new Syntax(
            "ParsedProgramMetadata", Syntax.RUBY.op | OP_POSIX_BRACKET,
            (Syntax.RUBY.op2 & ~OP2_OPTION_RUBY) | OP2_OPTION_PERL,
            Syntax.RUBY.op3 | OP3_PERL_LITERAL_OPEN_IN_CC,
            Syntax.RUBY.behavior,
            Syntax.RUBY.options & ~(Option.ASCII_RANGE
                    | Option.POSIX_BRACKET_ALL_RANGE
                    | Option.WORD_BOUND_ALL_RANGE),
            Syntax.RUBY.metaCharTable);

    @Test
    public void publishesAcceptedParserAndProgramFacts() {
        assertFeature("a\\Kb", ParsedProgramFeature.KEEP);
        assertFeature("(?<=a)b", ParsedProgramFeature.POSITIVE_LOOKBEHIND);
        assertFeature("(?<!a)b", ParsedProgramFeature.NEGATIVE_LOOKBEHIND);
        assertFeature("(?|(a)|(b))", ParsedProgramFeature.BRANCH_RESET);
        assertFeature("(a)(?(1)b|c)", ParsedProgramFeature.CONDITIONAL);
        assertFeature("(*pla:a)", ParsedProgramFeature.ALPHA_ASSERTION);
        assertFeature("(*script_run:a)", ParsedProgramFeature.SCRIPT_RUN);
        assertFeature("(*atomic_script_run:a)",
                ParsedProgramFeature.ATOMIC_SCRIPT_RUN);
        assertFeature("(?<x>x)(?&x)", ParsedProgramFeature.SUBEXPRESSION_CALL);
        assertFeature("(?{=CALL:0})", ParsedProgramFeature.CALLOUT);
        assertFeature("(?{=DYNAMIC:0})", ParsedProgramFeature.DYNAMIC_CALLOUT);
        assertFeature("\\Gx", ParsedProgramFeature.G_ASSERTION);
        assertFeature("(?aa:x)", ParsedProgramFeature.INLINE_ASCII_STRICT);
        assertFeature("(?[ [a] + [b] ])",
                ParsedProgramFeature.PERL_EXTENDED_CLASS);
        assertFeature("(?[ [:ascii:] & [:graph:] ])",
                ParsedProgramFeature.NATIVE_EXTENDED_CLASS_LEAF);
    }

    @Test
    public void excludesLiteralAndCommentLookalikes() {
        assertNoFeature("\\\\K", ParsedProgramFeature.KEEP);
        assertNoFeature("\\\\G", ParsedProgramFeature.G_ASSERTION);
        assertNoFeature("(?# \\G)A", ParsedProgramFeature.G_ASSERTION);
        assertNoFeature("# \\G\nA", ParsedProgramFeature.G_ASSERTION,
                Option.EXTEND);
        assertNoFeature("(?[ [a] + [b] ])",
                ParsedProgramFeature.NATIVE_EXTENDED_CLASS_LEAF);
    }

    @Test
    public void attachesPartialFactsToMalformedGrammar() {
        assertPartial("(*pla)", ParsedProgramFeature.ALPHA_ASSERTION);
        assertPartial("(?(1)x", ParsedProgramFeature.CONDITIONAL);
        assertPartial("a[]b", ParsedProgramFeature.EMPTY_CHARACTER_CLASS);
        assertPartial("(?&missing", ParsedProgramFeature.SUBEXPRESSION_CALL);

        SyntaxException unrelated = assertThrows(SyntaxException.class,
                () -> compile("(", Option.NONE));
        assertTrue(unrelated.getParsedProgramMetadata().features().isEmpty());
    }

    @Test
    public void returnedFeatureSetIsImmutable() {
        Regex regex = compile("a\\Kb", Option.NONE);
        assertThrows(UnsupportedOperationException.class,
                () -> regex.getParsedProgramMetadata().features().clear());
        assertTrue(regex.getParsedProgramMetadata().has(
                ParsedProgramFeature.KEEP));
    }

    private static void assertFeature(String source,
            ParsedProgramFeature feature) {
        assertTrue(source, compile(source, Option.NONE)
                .getParsedProgramMetadata().has(feature));
    }

    private static void assertNoFeature(String source,
            ParsedProgramFeature feature) {
        assertNoFeature(source, feature, Option.NONE);
    }

    private static void assertNoFeature(String source,
            ParsedProgramFeature feature, int option) {
        assertFalse(source, compile(source, option)
                .getParsedProgramMetadata().has(feature));
    }

    private static void assertPartial(String source,
            ParsedProgramFeature feature) {
        SyntaxException error = assertThrows(SyntaxException.class,
                () -> compile(source, Option.NONE));
        assertTrue(source, error.getParsedProgramMetadata().has(feature));
    }

    private static Regex compile(String source, int option) {
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        return new Regex(bytes, 0, bytes.length, option,
                UTF8Encoding.INSTANCE, SYNTAX);
    }
}
