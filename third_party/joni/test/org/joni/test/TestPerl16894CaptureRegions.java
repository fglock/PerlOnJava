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
import org.joni.Region;
import org.joni.Syntax;
import org.junit.Test;

public class TestPerl16894CaptureRegions {
    private static Region match(String pattern, String input) {
        byte[] source = pattern.getBytes(StandardCharsets.UTF_8);
        byte[] target = input.getBytes(StandardCharsets.UTF_8);
        Regex regex = new Regex(source, 0, source.length, Option.NONE,
                UTF8Encoding.INSTANCE, Syntax.PerlNG);
        Matcher matcher = regex.matcher(target);
        assertEquals(0, matcher.search(0, target.length, Option.NONE));
        return matcher.getEagerRegion();
    }

    private static void assertRegions(String pattern, String input,
            int[] starts, int[] ends) {
        Region region = match(pattern, input);
        assertEquals(starts.length, region.getNumRegs());
        for (int capture = 0; capture < starts.length; capture++) {
            assertEquals("start of capture " + capture,
                    starts[capture], region.getBeg(capture));
            assertEquals("end of capture " + capture,
                    ends[capture], region.getEnd(capture));
        }
    }

    @Test
    public void retainsCapturesFromSuccessfulLookaheadRepeatIterations() {
        assertRegions("(?:[^b]*(?=(b)|(a))ab)*", "abab",
                new int[] {0, -1, 2}, new int[] {4, -1, 3});
        assertRegions("\\A(?:(?:[^b]*(?=(b)|(a))ab)*)\\z", "abab",
                new int[] {0, -1, 2}, new int[] {4, -1, 3});
        assertRegions("(?:[^b]*(?=(b)|(a))ab)+", "ababab",
                new int[] {0, -1, 4}, new int[] {6, -1, 5});
    }

    @Test
    public void handlesNearbyAlternationAndUntakenBranchGenerically() {
        assertRegions("(?:[^b]*(?=(a)|(b))ab)*", "abab",
                new int[] {0, 2, -1}, new int[] {4, 3, -1});
        assertRegions("(?:b*(?=(b)|(a))b)+", "bb",
                new int[] {0, 1, -1}, new int[] {2, 2, -1});
    }
}
