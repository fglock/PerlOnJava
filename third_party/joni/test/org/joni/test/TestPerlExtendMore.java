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

import org.jcodings.specific.ASCIIEncoding;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.junit.Test;

public class TestPerlExtendMore {
    private static int search(String pattern, String input, int options) {
        byte[] patternBytes = pattern.getBytes(StandardCharsets.US_ASCII);
        byte[] inputBytes = input.getBytes(StandardCharsets.US_ASCII);
        Regex regex = new Regex(patternBytes, 0, patternBytes.length, options,
                ASCIIEncoding.INSTANCE, Syntax.PerlNG);
        return regex.matcher(inputBytes).search(0, inputBytes.length, Option.NONE);
    }

    @Test
    public void topLevelExtendMoreIgnoresOnlyHorizontalClassSpace() {
        int xx = Option.EXTEND | Option.PERL_EXTEND_MORE;
        assertEquals(-1, search("[a b]", " ", xx));
        assertEquals(-1, search("[a\tb]", "\t", xx));
        assertEquals(0, search("[a\nb]", "\n", xx));
        assertEquals(0, search("[a#b]", "#", xx));
        assertEquals(0, search("[a\\ b]", " ", xx));
    }

    @Test
    public void inlineXSelectsAnExactExtendLevel() {
        int x = Option.EXTEND;
        int xx = x | Option.PERL_EXTEND_MORE;
        assertEquals(0, search("(?x:[a b])", " ", xx));
        assertEquals(-1, search("(?xx:[a b])", " ", x));
        assertEquals(0, search("(?-x:[a b])", " ", xx));
    }
}
