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

public class TestPerlControlVerbs {
    private static Matcher matcher(String pattern, String input) {
        byte[] patternBytes = pattern.getBytes(StandardCharsets.US_ASCII);
        byte[] inputBytes = input.getBytes(StandardCharsets.US_ASCII);
        Regex regex = new Regex(patternBytes, 0, patternBytes.length, Option.NONE,
                ASCIIEncoding.INSTANCE, Syntax.RUBY);
        return regex.matcher(inputBytes);
    }

    @Test
    public void pruneRejectsLaterAlternativesAtTheSameStart() {
        assertEquals(-1, matcher("a(*PRUNE)b|ac", "ac").search(0, 2, Option.NONE));
        assertEquals(1, matcher("a(*PRUNE)b|c", "ac").search(0, 2, Option.NONE));
    }

    @Test
    public void skipResumesTheSearchAtTheCurrentPosition() {
        Matcher matcher = matcher("a(*SKIP)(*FAIL)|b", "aaab");
        assertEquals(3, matcher.search(0, 4, Option.NONE));
        assertEquals(4, matcher.getEnd());
    }

    @Test
    public void thenEntersTheNearestAlternative() {
        assertEquals(0, matcher("a(?:(?=b)(*THEN)c|b)", "ab").search(0, 2, Option.NONE));
        assertEquals(-1, matcher("a(?:(?=b)(*PRUNE)c|b)", "ab").search(0, 2, Option.NONE));
    }

    @Test
    public void commitPreventsLaterAlternativesAndSearchStarts() {
        assertEquals(-1, matcher("a(*COMMIT)b|ac", "ac").search(0, 2, Option.NONE));
        assertEquals(-1, matcher("a(*COMMIT)c|b", "ab").search(0, 2, Option.NONE));
    }

    @Test
    public void markReportsTheSuccessfulPathAndRestoresOnBacktrack() {
        Matcher matcher = matcher("a(*MARK:first)b|a(*MARK:second)c", "ac");
        assertEquals(0, matcher.search(0, 2, Option.NONE));
        assertEquals("second", matcher.getControlMark());
        assertEquals(null, matcher.getControlError());
    }

    @Test
    public void namedSkipResumesAtTheMatchingMark() {
        Matcher matcher = matcher("a(*MARK:after-a)b(*SKIP:after-a)(*FAIL)|b", "aab");
        assertEquals(2, matcher.search(0, 3, Option.NONE));
        assertEquals(3, matcher.getEnd());
    }

    @Test
    public void namedPruneReportsItsFailureName() {
        Matcher matcher = matcher("a(*PRUNE:blocked)b", "ac");
        assertEquals(-1, matcher.search(0, 2, Option.NONE));
        assertEquals(null, matcher.getControlMark());
        assertEquals("blocked", matcher.getControlError());
    }
}
