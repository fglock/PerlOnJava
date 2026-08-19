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

public class TestPerlControlEscape {
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

    @Test
    public void perlControlBackslashLeavesTheFollowingCharacterForTheLexer() {
        assertEquals(-1, search("\\Ax\\c\\_y\\z", "x\u001fy", Syntax.PerlNG));
        assertMatch("x\\c\\_y", "x\u001c_y", Syntax.PerlNG);

        for (String suffix : new String[] {"z", "\0", "!", "\u00fe", "\u0100"}) {
            assertMatch("a\\c\\" + suffix, "a\u001c" + suffix, Syntax.PerlNG);
        }
    }

    @Test
    public void nonPerlSyntaxRetainsRecursiveEscapedControlArguments() {
        assertMatch("x\\c\\_y", "x\u001fy", Syntax.RUBY);
    }
}
