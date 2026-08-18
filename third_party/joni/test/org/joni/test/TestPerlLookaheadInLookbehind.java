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
import org.joni.Syntax;
import org.junit.Test;

public class TestPerlLookaheadInLookbehind {
    private static Regex regex(String pattern) {
        byte[] bytes = pattern.getBytes(StandardCharsets.US_ASCII);
        return new Regex(bytes, 0, bytes.length, Option.NONE,
                ASCIIEncoding.INSTANCE, Syntax.RUBY);
    }

    private static Matcher matcher(String pattern, String input) {
        return regex(pattern).matcher(input.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    public void positiveLookaheadIsAllowedInsidePositiveLookbehind() {
        Matcher matcher = matcher("(?<=(?=a)ab)c", "abc");

        assertEquals(2, matcher.search(0, 3, Option.NONE));
        assertEquals(3, matcher.getEnd());
    }

    @Test
    public void negativeLookaheadIsAllowedInsidePositiveLookbehind() {
        Matcher matcher = matcher("(?<=a(?!x)b)c", "abc");

        assertEquals(2, matcher.search(0, 3, Option.NONE));
    }

    @Test
    public void positiveLookaheadIsAllowedInsideNegativeLookbehind() {
        Matcher matcher = matcher("(?<!x(?=b))c", "abc");

        assertEquals(2, matcher.search(0, 3, Option.NONE));
    }

    @Test
    public void negativeLookaheadIsAllowedInsideNegativeLookbehind() {
        Matcher matcher = matcher("(?<!a(?!x))c", "abc");

        assertEquals(2, matcher.search(0, 3, Option.NONE));
    }

    @Test
    public void captureStateSurvivesNestedLookaheadInLookbehind() {
        Matcher matcher = matcher("(?<=(?=a)(ab))c", "abc");

        assertEquals(2, matcher.search(0, 3, Option.NONE));
        assertEquals(0, matcher.captureBegin(1));
        assertEquals(2, matcher.captureEnd(1));
    }

    @Test
    public void notEmptyRetryPreservesTheExactGlobalSequence() {
        String pattern = "(?<=(?=a)..)((?=c)|.)";
        Matcher empty = matcher(pattern, "xabcx");
        assertEquals(3, empty.search(0, 5, Option.NONE));
        assertEquals(3, empty.getEnd());
        assertEquals(3, empty.captureBegin(1));
        assertEquals(3, empty.captureEnd(1));

        Matcher consuming = matcher(pattern, "xabcx");
        assertEquals(3, consuming.search(3, 5, Option.FIND_NOT_EMPTY));
        assertEquals(4, consuming.getEnd());
        assertEquals(3, consuming.captureBegin(1));
        assertEquals(4, consuming.captureEnd(1));
    }
}
