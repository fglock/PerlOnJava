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

public class TestControlVerb {
    private static Matcher matcher(String pattern, String input) {
        byte[] patternBytes = pattern.getBytes(StandardCharsets.US_ASCII);
        byte[] inputBytes = input.getBytes(StandardCharsets.US_ASCII);
        Regex regex = new Regex(patternBytes, 0, patternBytes.length, Option.NONE,
                ASCIIEncoding.INSTANCE, Syntax.RUBY);
        return regex.matcher(inputBytes);
    }

    @Test
    public void acceptClosesActiveCapturesAndSkipsTheOuterSuffix() {
        Matcher matcher = matcher("(A(A|B(*ACCEPT)|C)+D)(E)z", "ABDE");

        assertEquals(0, matcher.search(0, 4, Option.NONE));
        Region region = matcher.getEagerRegion();
        assertEquals(0, region.getBeg(0));
        assertEquals(2, region.getEnd(0));
        assertEquals(0, region.getBeg(1));
        assertEquals(2, region.getEnd(1));
        assertEquals(1, region.getBeg(2));
        assertEquals(2, region.getEnd(2));
        assertEquals(-1, region.getBeg(3));
    }

    @Test
    public void acceptCompletesOnlyThePositiveAssertionBoundary() {
        Matcher matcher = matcher("(?=(a(*ACCEPT)z))ab", "ab");

        assertEquals(0, matcher.search(0, 2, Option.NONE));
        Region region = matcher.getEagerRegion();
        assertEquals(2, region.getEnd(0));
        assertEquals(0, region.getBeg(1));
        assertEquals(1, region.getEnd(1));
    }

    @Test
    public void acceptCanProduceAnEmptyMatch() {
        Matcher matcher = matcher("(*ACCEPT)never", "x");

        assertEquals(0, matcher.search(0, 1, Option.NONE));
        assertEquals(0, matcher.getEnd());
    }
}
