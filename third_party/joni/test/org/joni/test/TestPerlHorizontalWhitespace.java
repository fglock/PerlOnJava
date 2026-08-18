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

import static org.joni.constants.SyntaxProperties.OP2_ESC_H_HORIZONTAL_WHITESPACE;
import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import org.jcodings.Encoding;
import org.jcodings.constants.CharacterType;
import org.jcodings.specific.ISO8859_1Encoding;
import org.jcodings.specific.UTF8Encoding;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.junit.Test;

public class TestPerlHorizontalWhitespace {
    private static final int[] HORIZONTAL = {
        0x09, 0x20, 0xa0, 0x1680, 0x2000, 0x200a, 0x202f, 0x205f, 0x3000
    };
    private static final int[] OTHER = {
        0x0a, 0x0b, 0x41, 0x85, 0x180e, 0x2028, 0x2029
    };
    private static final Syntax PERL_HORIZONTAL = new Syntax(
            "PERL_HORIZONTAL", Syntax.PerlNG.op,
            Syntax.PerlNG.op2 | OP2_ESC_H_HORIZONTAL_WHITESPACE,
            Syntax.PerlNG.op3, Syntax.PerlNG.behavior, Syntax.PerlNG.options,
            Syntax.PerlNG.metaCharTable);

    private static byte[] encode(String value, Encoding encoding) {
        return value.getBytes(encoding == ISO8859_1Encoding.INSTANCE
                ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8);
    }

    private static int search(String pattern, String input, Encoding encoding,
                              Syntax syntax) {
        byte[] patternBytes = encode(pattern, encoding);
        byte[] inputBytes = encode(input, encoding);
        Regex regex = new Regex(patternBytes, 0, patternBytes.length, Option.NONE,
                encoding, syntax);
        return regex.matcher(inputBytes).search(0, inputBytes.length, Option.NONE);
    }

    private static void assertMatch(String pattern, String input, Encoding encoding,
                                    Syntax syntax) {
        assertEquals(0, search("\\A(?:" + pattern + ")\\z", input, encoding, syntax));
    }

    private static void assertNoMatch(String pattern, String input, Encoding encoding,
                                      Syntax syntax) {
        assertEquals(-1, search("\\A(?:" + pattern + ")\\z", input, encoding, syntax));
    }

    @Test
    public void jcodingsBlankExactlyMatchesPerlHorizontalWhitespace() {
        Set<Integer> expected = new HashSet<>();
        for (int codePoint : new int[] {
                0x09, 0x20, 0xa0, 0x1680, 0x202f, 0x205f, 0x3000}) {
            expected.add(codePoint);
        }
        for (int codePoint = 0x2000; codePoint <= 0x200a; codePoint++) {
            expected.add(codePoint);
        }

        Set<Integer> actual = new HashSet<>();
        for (int codePoint = 0; codePoint <= 0x10ffff; codePoint++) {
            if (UTF8Encoding.INSTANCE.isCodeCType(codePoint, CharacterType.BLANK)) {
                actual.add(codePoint);
            }
        }
        assertEquals(expected, actual);
    }

    @Test
    public void implementsPerlHorizontalWhitespaceInsideAndOutsideClasses() {
        for (int codePoint : HORIZONTAL) {
            String character = new String(Character.toChars(codePoint));
            assertMatch("\\h", character, UTF8Encoding.INSTANCE, PERL_HORIZONTAL);
            assertMatch("[\\h]", character, UTF8Encoding.INSTANCE, PERL_HORIZONTAL);
            assertNoMatch("\\H", character, UTF8Encoding.INSTANCE, PERL_HORIZONTAL);
            assertNoMatch("[\\H]", character, UTF8Encoding.INSTANCE, PERL_HORIZONTAL);
            if (codePoint <= 0xff) {
                assertMatch("\\h", character, ISO8859_1Encoding.INSTANCE, PERL_HORIZONTAL);
                assertMatch("[\\h]", character, ISO8859_1Encoding.INSTANCE, PERL_HORIZONTAL);
                assertNoMatch("\\H", character, ISO8859_1Encoding.INSTANCE, PERL_HORIZONTAL);
                assertNoMatch("[\\H]", character, ISO8859_1Encoding.INSTANCE, PERL_HORIZONTAL);
            }
        }

        for (int codePoint : OTHER) {
            String character = new String(Character.toChars(codePoint));
            assertNoMatch("\\h", character, UTF8Encoding.INSTANCE, PERL_HORIZONTAL);
            assertNoMatch("[\\h]", character, UTF8Encoding.INSTANCE, PERL_HORIZONTAL);
            assertMatch("\\H", character, UTF8Encoding.INSTANCE, PERL_HORIZONTAL);
            assertMatch("[\\H]", character, UTF8Encoding.INSTANCE, PERL_HORIZONTAL);
            if (codePoint <= 0xff) {
                assertNoMatch("\\h", character, ISO8859_1Encoding.INSTANCE, PERL_HORIZONTAL);
                assertNoMatch("[\\h]", character, ISO8859_1Encoding.INSTANCE, PERL_HORIZONTAL);
                assertMatch("\\H", character, ISO8859_1Encoding.INSTANCE, PERL_HORIZONTAL);
                assertMatch("[\\H]", character, ISO8859_1Encoding.INSTANCE, PERL_HORIZONTAL);
            }
        }
    }

    @Test
    public void asciiModifiersDoNotNarrowPerlHorizontalWhitespace() {
        String ideographicSpace = new String(Character.toChars(0x3000));
        for (String pattern : new String[] {
                "(?a:\\h)", "(?aa:\\h)", "(?a:[\\h])", "(?aa:[\\h])"}) {
            assertMatch(pattern, ideographicSpace, UTF8Encoding.INSTANCE, PERL_HORIZONTAL);
        }
        for (String pattern : new String[] {
                "(?a:\\H)", "(?aa:\\H)", "(?a:[\\H])", "(?aa:[\\H])"}) {
            assertMatch(pattern, "\n", UTF8Encoding.INSTANCE, PERL_HORIZONTAL);
        }
        for (String pattern : new String[] {
                "(?a:\\h)", "(?aa:\\h)", "(?a:[\\h])", "(?aa:[\\h])"}) {
            assertMatch(pattern, "\u00a0", ISO8859_1Encoding.INSTANCE, PERL_HORIZONTAL);
        }
    }

    @Test
    public void preservesRubyHexDigitEscapes() {
        assertMatch("\\h+", "09AFaf", UTF8Encoding.INSTANCE, Syntax.RUBY);
        assertMatch("[\\h]+", "09AFaf", UTF8Encoding.INSTANCE, Syntax.RUBY);
        assertNoMatch("\\h", " ", UTF8Encoding.INSTANCE, Syntax.RUBY);
        assertNoMatch("[\\h]", " ", UTF8Encoding.INSTANCE, Syntax.RUBY);
    }
}
