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
import org.joni.Matcher;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.junit.Test;

public class TestPerlSimpleFoldClosure {
    private static int search(String pattern, String input) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        Regex regex = new Regex(bytes, 0, bytes.length, Option.IGNORECASE,
                UTF8Encoding.INSTANCE, Syntax.PerlNG);
        byte[] target = input.getBytes(StandardCharsets.UTF_8);
        Matcher matcher = regex.matcher(target);
        return matcher.search(0, target.length, Option.NONE);
    }

    @Test
    public void foldsPinnedSimpleClassesWithoutBreakingScopedAsciiPolicy() {
        assertEquals(0, search("^(?d:k)$", "\u212a"));
        assertEquals(0, search("^(?u:\u00e5)$", "\u212b"));
        assertEquals(0, search("^(?a:kk)$", "\u212aK"));
        assertEquals(0, search("^(?d:[k])$", "\u212a"));
        assertEquals(-1, search("^(?aa:k)$", "\u212a"));
        assertEquals(0, search("^\u2c65$", "\u023a"));
        assertEquals(0, search("^\u2c66$", "\u023e"));
    }
}
