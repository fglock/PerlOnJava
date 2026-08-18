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
import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.UTF8Encoding;
import org.joni.Matcher;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.joni.exception.SyntaxException;
import org.junit.Test;

public class TestPerlVariableLookBehind {
    private static Matcher matcher(String pattern, String input) {
        return matcher(pattern, input, Syntax.Perl);
    }

    private static Matcher matcher(String pattern, String input, Syntax syntax) {
        byte[] patternBytes = pattern.getBytes(StandardCharsets.UTF_8);
        byte[] inputBytes = input.getBytes(StandardCharsets.UTF_8);
        Regex regex = new Regex(patternBytes, 0, patternBytes.length, Option.NONE,
                UTF8Encoding.INSTANCE, syntax);
        return regex.matcher(inputBytes);
    }

    private static void assertRejected(String pattern, Syntax syntax) {
        assertThrows(SyntaxException.class, () -> matcher(pattern, "", syntax));
    }

    @Test
    public void boundedPositiveLookBehindTriesEveryLength() {
        assertEquals(3, matcher("(?<=a{1,3})b", "aaab").search(0, 4, Option.NONE));
    }

    @Test
    public void boundedNegativeLookBehindRejectsEveryLength() {
        assertEquals(-1, matcher("(?<!a{1,3})b", "zaab").search(0, 4, Option.NONE));
    }

    @Test
    public void compoundBoundedLookBehindRequiresTheOriginalEndpoint() {
        Matcher matcher = matcher("(?<=(a{1,3}b))c", "aaabc");
        assertEquals(4, matcher.search(0, 5, Option.NONE));
        assertEquals(0, matcher.getRegion().getBeg(1));
        assertEquals(4, matcher.getRegion().getEnd(1));
    }

    @Test
    public void compoundNegativeLookBehindChecksEveryFiniteLength() {
        assertEquals(-1, matcher("(?<!a{1,3}b)c", "aaabc").search(0, 5, Option.NONE));
        assertEquals(1, matcher("(?<!a{1,3}b)c", "zc").search(0, 2, Option.NONE));
    }

    @Test
    public void nestedFiniteAlternationSupportsCompoundLengths() {
        assertEquals(5, matcher("(?<=x(?:a|bc){1,2})z", "xbcbcz")
                .search(0, 6, Option.NONE));
    }

    @Test
    public void nestedLookAheadAssertionsFollowPerlLookBehindSemantics() {
        assertEquals(1, matcher("(?<=a(?=b))b", "ab").search(0, 2, Option.NONE));
        assertEquals(1, matcher("(?<=a(?!b))c", "ac").search(0, 2, Option.NONE));
        assertEquals(-1, matcher("(?<=a(?!b))b", "ab").search(0, 2, Option.NONE));
        assertEquals(-1, matcher("(?<!a(?=b))b", "ab").search(0, 2, Option.NONE));
        assertEquals(1, matcher("(?<!a(?=b))b", "cb").search(0, 2, Option.NONE));
        assertEquals(-1, matcher("(?<!a(?!b))c", "ac").search(0, 2, Option.NONE));
        assertEquals(1, matcher("(?<!a(?!b))c", "bc").search(0, 2, Option.NONE));
    }

    @Test
    public void nestedLookAheadPublishesCapturesAfterBoundedBacktracking() {
        Matcher matcher = matcher("(?<=((?:a{1,3}))(?=(b)))b", "xaaab");
        assertEquals(4, matcher.search(0, 5, Option.NONE));
        assertEquals(1, matcher.getRegion().getBeg(1));
        assertEquals(4, matcher.getRegion().getEnd(1));
        assertEquals(4, matcher.getRegion().getBeg(2));
        assertEquals(5, matcher.getRegion().getEnd(2));
    }

    @Test
    public void nestedLookAheadRespectsPerlLookBehindLengthLimit() {
        String input254 = "a".repeat(254) + "b";
        String input255 = "a".repeat(255) + "b";
        assertEquals(254, matcher("(?<=a{254,255}(?=b))b", input254)
                .search(0, input254.length(), Option.NONE));
        assertEquals(255, matcher("(?<=a{254,255}(?=b))b", input255)
                .search(0, input255.length(), Option.NONE));
        assertRejected("(?<=a{255,256}(?=b))b", Syntax.Perl);
        assertRejected("(?<=a+(?=b))b", Syntax.Perl);
        assertRejected("(?<=a(?=b)b", Syntax.Perl);
    }

    @Test
    public void nestedLookAheadRemainsRejectedByRubySyntax() {
        assertRejected("(?<=a(?=b))b", Syntax.RUBY);
        assertRejected("(?<=a(?!b))b", Syntax.RUBY);
        assertRejected("(?<!a(?=b))b", Syntax.RUBY);
        assertRejected("(?<!a(?!b))b", Syntax.RUBY);
    }
}
