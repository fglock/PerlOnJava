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

public class TestNullableEndAnchorSearch {
    private static Regex compile(String pattern) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        return new Regex(bytes, 0, bytes.length, Option.NONE,
                UTF8Encoding.INSTANCE, Syntax.Perl);
    }

    private static int search(String pattern, String input) {
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        return compile(pattern).matcher(bytes).search(0, bytes.length, Option.NONE);
    }

    private static int reverseSearch(String pattern, String input) {
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        return compile(pattern).matcher(bytes).search(bytes.length, 0, Option.NONE);
    }

    @Test
    public void nullableSemiEndAnchorRetainsTheEndCandidate() {
        assertEquals(2, search("a?$", "\u0131"));
        assertEquals(4, search("a?$", "\uD83D\uDE00"));
        assertEquals(1, search("a?$", "x"));
    }

    @Test
    public void nullableAbsoluteEndAnchorRetainsTheEndCandidate() {
        assertEquals(2, search("a?\\z", "\u0131"));
        assertEquals(4, search("a?\\z", "\uD83D\uDE00"));
    }

    @Test
    public void nonNullableEndAnchorDoesNotGainAnEmptyMatch() {
        assertEquals(Matcher.FAILED, search("a$", "\u0131"));
        assertEquals(Matcher.FAILED, search("a\\z", "\u0131"));
    }

    @Test
    public void reverseSearchRetainsNullableEndCandidates() {
        assertEquals(2, reverseSearch("a?$", "\u0131"));
        assertEquals(2, reverseSearch("a?\\z", "\u0131"));
    }
}
