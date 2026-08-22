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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.joni.constants.SyntaxProperties.ALLOW_MULTIPLEX_DEFINITION_NAME_CALL;
import static org.joni.constants.SyntaxProperties.OP2_ESC_H_HORIZONTAL_WHITESPACE;
import static org.joni.constants.SyntaxProperties.OP2_OPTION_PERL;
import static org.joni.constants.SyntaxProperties.OP2_OPTION_RUBY;
import static org.joni.constants.SyntaxProperties.OP2_PLUS_POSSESSIVE_INTERVAL;

import java.nio.charset.StandardCharsets;

import org.jcodings.Encoding;
import org.jcodings.specific.UTF8Encoding;
import org.joni.NamedCharacterResolver;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.joni.WarnCallback;
import org.joni.exception.JOniException;
import org.junit.Test;

public class TestPerlExtendedCharacterClass {
    private static final NamedCharacterResolver RESOLVER =
            new NamedCharacterResolver() {
                @Override
                public int resolve(byte[] bytes, int p, int end, Encoding encoding) {
                    int[] sequence = resolveSequence(bytes, p, end, encoding);
                    return sequence.length == 1 ? sequence[0] : -1;
                }

                @Override
                public int[] resolveSequence(byte[] bytes, int p, int end,
                                             Encoding encoding) {
                    String name = new String(bytes, p, end - p, StandardCharsets.UTF_8);
                    return switch (name) {
                    case "CAPITAL" -> new int[] {'A'};
                    case "EMPTY" -> new int[0];
                    case "MULTI" -> new int[] {'A', 'B'};
                    default -> throw new IllegalArgumentException(name);
                    };
                }
            };

    private static final Syntax SYNTAX = new Syntax(
            "PerlExtendedCharacterClass", Syntax.RUBY.op,
            (Syntax.RUBY.op2 & ~OP2_OPTION_RUBY) | OP2_OPTION_PERL
                    | OP2_PLUS_POSSESSIVE_INTERVAL | OP2_ESC_H_HORIZONTAL_WHITESPACE,
            Syntax.RUBY.op3,
            Syntax.RUBY.behavior | ALLOW_MULTIPLEX_DEFINITION_NAME_CALL,
            Syntax.RUBY.options & ~(Option.ASCII_RANGE
                    | Option.POSIX_BRACKET_ALL_RANGE | Option.WORD_BOUND_ALL_RANGE),
            Syntax.RUBY.metaCharTable, RESOLVER);

    private static Regex compile(String pattern) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        return new Regex(bytes, 0, bytes.length, Option.NONE,
                UTF8Encoding.INSTANCE, SYNTAX, WarnCallback.NONE);
    }

    private static int search(String pattern, String input) {
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        return compile(pattern).matcher(bytes).search(0, bytes.length, Option.NONE);
    }

    private static void assertMatches(String pattern, String yes, String no) {
        assertEquals(pattern + " should match " + yes, 0, search(pattern, yes));
        assertEquals(pattern + " should not match " + no, -1, search(pattern, no));
    }

    private static void assertSyntaxError(String pattern, String fragment) {
        try {
            compile(pattern);
            fail("expected syntax error for " + pattern);
        } catch (JOniException error) {
            if (!error.getMessage().contains(fragment)) {
                fail("expected '" + fragment + "' in '" + error.getMessage() + "'");
            }
        }
    }

    @Test
    public void evaluatesSetOperatorsAndPrecedence() {
        assertMatches("(?[ [a] + [b] ])", "a", "c");
        assertMatches("(?[ [a-c] & [b-d] ])", "b", "a");
        assertMatches("(?[ [a-c] - [b] ])", "a", "b");
        assertMatches("(?[ [a-b] ^ [b-c] ])", "a", "b");
        assertMatches("(?[ ! [a] ])", "z", "a");
        assertMatches("(?[ ([a] + [b]) & [b-c] ])", "b", "a");
        assertMatches("(?[ [a] + (?[ [b] & [b-c] ]) ])", "b", "c");
        assertMatches("(?[ [a] + (?^:(?[ [b] & [b-c] ])) ])", "b", "c");
        assertMatches("(?[[#]])", "#", "a");
        assertMatches("(?[ \\# ])", "#", "a");
        assertMatches("(?[ [a-c] # comment\n - [a-b] ])", "c", "a");
    }

    @Test
    public void acceptsClassAtomsEscapesAndComments() {
        assertMatches("(?[ \\N{CAPITAL} ])", "A", "B");
        assertMatches("(?[ \\p{Digit} & [0-9] ])", "5", "A");
        assertMatches("(?[ \\t ])", "\t", "x");
        assertMatches("(?[ [a-c] # comment\n - [a-b] ])", "c", "a");
        assertMatches("(?i)(?[ (?^:(?[ [a] ])) ])", "a", "A");
        assertMatches("(?[ (?i:(?[ [a] ])) ])", "A", "b");
        assertMatches("(?[ (?^a:(?[ [\\w] ])) ])", "A", "é");
        assertMatches("(?[ (?^u:(?[ [\\w] ])) ])", "é", "-");
        assertMatches("(?[ (?^d:(?[ [\\w] ])) ])", "A", "-");
        assertMatches("(?[ (?^aai:(?[ [k] ])) ])", "K", "K");
        assertMatches("(?[ \\005 ])", "\005", "5");
    }

    @Test
    public void rejectsInvalidBoundariesAndNamedSequences() {
        assertSyntaxError("(?[ ])", "Incomplete expression");
        assertSyntaxError("(?[ a ])", "Unexpected character");
        assertSyntaxError("(?[ [a]", "Incomplete expression");
        assertSyntaxError("(?[ \\N{EMPTY} ])", "Zero length \\N{}");
        assertSyntaxError("(?[ \\N{MULTI} ])", "restricted to one character");
        assertSyntaxError("(?[[\\N{MULTI}]])", "restricted to one character");
        assertSyntaxError("(?[ (?i:[a]) ])", "Expecting interpolated extended charclass");
        assertSyntaxError("(?[ (?^l:(?[ [a] ])) ])", "Locale charset modifier");
        assertSyntaxError("(?[ \\05 ])", "Need exactly 3 octal digits");
        assertSyntaxError("(?[ \\0004 ])", "Need exactly 3 octal digits");
        assertMatches("(?[ [[:alpha:]] ])", "A", "1");
        assertSyntaxError("(?[[[:x]]])",
                "Unexpected ']' with no following ')' in (?[...");
    }
}
