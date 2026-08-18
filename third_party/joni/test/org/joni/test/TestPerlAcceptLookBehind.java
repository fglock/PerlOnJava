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
import org.joni.Matcher;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Region;
import org.joni.Syntax;
import org.junit.Test;

public class TestPerlAcceptLookBehind {
    private static Matcher matcher(String pattern, String input) {
        byte[] patternBytes = pattern.getBytes(StandardCharsets.US_ASCII);
        byte[] inputBytes = input.getBytes(StandardCharsets.US_ASCII);
        Regex regex = new Regex(patternBytes, 0, patternBytes.length, Option.NONE,
                ASCIIEncoding.INSTANCE, Syntax.Perl);
        return regex.matcher(inputBytes);
    }

    private static void assertAcceptBranch(String input, String capture) {
        Matcher matcher = matcher("(?<=([cd](*ACCEPT)|x)gggg)blrph", input);
        assertEquals(1, matcher.search(0, input.length(), Option.NONE));
        assertEquals(6, matcher.getEnd());
        Region region = matcher.getEagerRegion();
        assertEquals(0, region.getBeg(1));
        assertEquals(1, region.getEnd(1));
        assertEquals(capture, input.substring(region.getBeg(1), region.getEnd(1)));
    }

    @Test
    public void acceptContributesAnEarlyPositiveLookBehindWidth() {
        assertAcceptBranch("cblrph", "c");
        assertAcceptBranch("dblrph", "d");
    }

    @Test
    public void ordinaryBranchRetainsTheFullLookBehindWidth() {
        Matcher matcher = matcher("(?<=([cd](*ACCEPT)|x)gggg)blrph", "xggggblrph");
        assertEquals(5, matcher.search(0, 10, Option.NONE));
        assertEquals(10, matcher.getEnd());
        assertEquals(0, matcher.getEagerRegion().getBeg(1));
        assertEquals(1, matcher.getEagerRegion().getEnd(1));
    }

    @Test
    public void nestedAssertionKeepsItsOwnAcceptBoundary() {
        assertEquals(1, matcher("(?<=a(?=b(*ACCEPT)))b", "ab")
                .search(0, 2, Option.NONE));
    }

    @Test
    public void ordinaryAndCompoundLookBehindWidthsRemainAvailable() {
        assertEquals(2, matcher("(?<=ab)c", "abc").search(0, 3, Option.NONE));
        assertEquals(4, matcher("(?<=a{1,3}b)c", "aaabc")
                .search(0, 5, Option.NONE));
    }
}
