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

public class TestPerlRepeatCaptureLifetime {
    private static Matcher match(String pattern, String input) {
        byte[] patternBytes = pattern.getBytes(StandardCharsets.UTF_8);
        byte[] inputBytes = input.getBytes(StandardCharsets.UTF_8);
        Regex regex = new Regex(patternBytes, 0, patternBytes.length,
                Option.CAPTURE_GROUP, UTF8Encoding.INSTANCE, Syntax.RUBY);
        Matcher matcher = regex.matcher(inputBytes);
        assertEquals(pattern, 0, matcher.search(0, inputBytes.length, Option.NONE));
        return matcher;
    }

    private static void assertLastAlternativeWins(Matcher matcher) {
        assertEquals(-1, matcher.getRegion().getBeg(1));
        assertEquals(-1, matcher.getRegion().getEnd(1));
        assertEquals(1, matcher.getRegion().getBeg(2));
        assertEquals(2, matcher.getRegion().getEnd(2));
    }

    @Test
    public void clearsCapturesFromEarlierGreedyIterations() {
        assertLastAlternativeWins(match("^(?:(a)|(b))*$", "ab"));
    }

    @Test
    public void restoresTheLastSuccessfulIterationAfterTheNextBodyFails() {
        assertLastAlternativeWins(match("^(?:(a)|(b))+$", "ab"));
    }

    @Test
    public void clearsCapturesInExpandedFiniteRepeats() {
        assertLastAlternativeWins(match("^(?:(a)|(b)){2}$", "ab"));
    }

    @Test
    public void exposesPreviousIterationToConditionInsideOpenCapture() {
        Matcher matcher = match("^((?(1)a|b))+$", "baaa");
        assertEquals(3, matcher.getRegion().getBeg(1));
        assertEquals(4, matcher.getRegion().getEnd(1));
    }

    @Test
    public void exposesPreviousIterationToOptionalSelfBackref() {
        Matcher matcher = match("^(a\\1?){4}$", "aaaaaaaaaa");
        assertEquals(6, matcher.getRegion().getBeg(1));
        assertEquals(10, matcher.getRegion().getEnd(1));
    }

    @Test
    public void exposesPreviousIterationToConditionalSelfBackref() {
        Matcher matcher = match("^(a(?(1)\\1)){4}$", "aaaaaaaaaa");
        assertEquals(6, matcher.getRegion().getBeg(1));
        assertEquals(10, matcher.getRegion().getEnd(1));
    }

    @Test
    public void ordinaryAlternativeInvalidatesPreviousSelfCapture() {
        byte[] pattern = "^(xa|=?\\1a){2}$".getBytes(StandardCharsets.UTF_8);
        byte[] input = "xa=xaaa".getBytes(StandardCharsets.UTF_8);
        Regex regex = new Regex(pattern, 0, pattern.length,
                Option.CAPTURE_GROUP, UTF8Encoding.INSTANCE, Syntax.RUBY);
        assertEquals(-1, regex.matcher(input).search(0, input.length, Option.NONE));
    }
}
