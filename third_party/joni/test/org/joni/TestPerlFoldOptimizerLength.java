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
package org.joni;

import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.UTF8Encoding;
import org.junit.Test;

public class TestPerlFoldOptimizerLength {
    private static int search(String pattern, String input, boolean reverse) {
        byte[] source = pattern.getBytes(StandardCharsets.UTF_8);
        Regex regex = new Regex(source, 0, source.length, Option.IGNORECASE,
                UTF8Encoding.INSTANCE, Syntax.Perl);
        byte[] target = input.getBytes(StandardCharsets.UTF_8);
        Matcher matcher = regex.matcher(target);
        return reverse
                ? matcher.search(target.length, 0, Option.NONE)
                : matcher.search(0, target.length, Option.NONE);
    }

    private static void assertBothDirections(String pattern, String input) {
        assertEquals(0, search(pattern, input, false));
        assertEquals(0, search(pattern, input, true));
    }

    @Test
    public void groupedHighAlternativeLiteralKeepsFollowingCandidateReachable() {
        assertBothDirections("(?u:(\u1f80)_$)", "\u1f88_");
        assertBothDirections("(?u:(\u1f80,?){1,3}_$)", "\u1f88_");
    }

    @Test
    public void groupedHighAlternativeClassKeepsFollowingCandidateReachable() {
        assertBothDirections("(?u:([\u1f80])_$)", "\u1f88_");
        assertBothDirections("(?u:([\u1f80],?){1,3}_$)", "\u1f88_");
    }

    @Test
    public void highAlternativeFoldRetainsItsLookbehindWidthRange() {
        assertEquals(3, search("(?u:(?<=\u1f80)X)", "\u1f88X", false));
        assertEquals(3, search("(?u:(?<=\u1f80)X)", "\u1f88X", true));
        assertEquals(3, search("(?u:(?<=\u1f88)X)", "\u1f80X", false));
        assertEquals(3, search("(?u:(?<=\u1f88)X)", "\u1f80X", true));
    }
}
