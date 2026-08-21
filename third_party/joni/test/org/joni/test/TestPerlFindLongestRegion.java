/*
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
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
import org.joni.Syntax;
import org.junit.Test;

public class TestPerlFindLongestRegion {
    private static Matcher matcher(String pattern, String input) {
        byte[] patternBytes = pattern.getBytes(StandardCharsets.US_ASCII);
        byte[] inputBytes = input.getBytes(StandardCharsets.US_ASCII);
        Regex regex = new Regex(patternBytes, 0, patternBytes.length,
                Option.CAPTURE_GROUP | Option.FIND_LONGEST,
                ASCIIEncoding.INSTANCE, Syntax.PerlNG);
        return regex.matcher(inputBytes);
    }

    @Test
    public void shorterAlternativeDoesNotClearTheLongestCaptureRegion() {
        byte[] input = "aax".getBytes(StandardCharsets.US_ASCII);
        Matcher matcher = matcher("(aa|a)", "aax");

        assertEquals(0, matcher.search(0, input.length, Option.NONE));
        assertEquals(0, matcher.getRegion().getBeg(0));
        assertEquals(2, matcher.getRegion().getEnd(0));
        assertEquals(0, matcher.getRegion().getBeg(1));
        assertEquals(2, matcher.getRegion().getEnd(1));
    }

    @Test
    public void equalAlternativeRetainsTheFirstWinningCaptureRegion() {
        byte[] input = "a".getBytes(StandardCharsets.US_ASCII);
        Matcher matcher = matcher("(a|(a))", "a");

        assertEquals(0, matcher.search(0, input.length, Option.NONE));
        assertEquals(0, matcher.getRegion().getBeg(0));
        assertEquals(1, matcher.getRegion().getEnd(0));
        assertEquals(0, matcher.getRegion().getBeg(1));
        assertEquals(1, matcher.getRegion().getEnd(1));
        assertEquals(-1, matcher.getRegion().getBeg(2));
        assertEquals(-1, matcher.getRegion().getEnd(2));
    }
}
