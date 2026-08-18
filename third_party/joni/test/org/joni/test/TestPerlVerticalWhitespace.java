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

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.UTF8Encoding;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.junit.Test;

public class TestPerlVerticalWhitespace {
    private static final String VERTICAL = "\n\u000b\f\r\u0085\u2028\u2029";
    private static final String OTHER = "\0\t V\u00a0\u0100";

    private static int search(String pattern, String input, Syntax syntax) {
        byte[] patternBytes = pattern.getBytes(StandardCharsets.UTF_8);
        byte[] inputBytes = input.getBytes(StandardCharsets.UTF_8);
        Regex regex = new Regex(patternBytes, 0, patternBytes.length, Option.NONE,
                UTF8Encoding.INSTANCE, syntax);
        return regex.matcher(inputBytes).search(0, inputBytes.length, Option.NONE);
    }

    private static void assertMatch(String pattern, String input, Syntax syntax) {
        assertEquals(0, search("\\A(?:" + pattern + ")\\z", input, syntax));
    }

    private static void assertNoMatch(String pattern, String input, Syntax syntax) {
        assertEquals(-1, search("\\A(?:" + pattern + ")\\z", input, syntax));
    }

    @Test
    public void implementsPerlVerticalWhitespaceInsideAndOutsideClasses() {
        for (int offset = 0; offset < VERTICAL.length();) {
            int codePoint = VERTICAL.codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            assertMatch("\\v", character, Syntax.PerlNG);
            assertMatch("[\\v]", character, Syntax.PerlNG);
            assertNoMatch("\\V", character, Syntax.PerlNG);
            assertNoMatch("[\\V]", character, Syntax.PerlNG);
            offset += Character.charCount(codePoint);
        }

        for (int offset = 0; offset < OTHER.length();) {
            int codePoint = OTHER.codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            assertNoMatch("\\v", character, Syntax.PerlNG);
            assertNoMatch("[\\v]", character, Syntax.PerlNG);
            assertMatch("\\V", character, Syntax.PerlNG);
            assertMatch("[\\V]", character, Syntax.PerlNG);
            offset += Character.charCount(codePoint);
        }

        assertMatch("\\v+", VERTICAL, Syntax.PerlNG);
        assertMatch("[A\\v]", "A", Syntax.PerlNG);
        assertMatch("[A\\V]", "Z", Syntax.PerlNG);
    }

    @Test
    public void preservesNonPerlVerticalTabEscapes() {
        for (Syntax syntax : new Syntax[] {Syntax.RUBY, Syntax.Java, Syntax.ECMAScript}) {
            assertMatch("\\v", "\u000b", syntax);
            assertNoMatch("\\v", "\n", syntax);
            assertMatch("\\V", "V", syntax);
            assertNoMatch("\\V", "A", syntax);
            assertMatch("[\\v]", "\u000b", syntax);
            assertMatch("[\\V]", "V", syntax);
        }
    }
}
