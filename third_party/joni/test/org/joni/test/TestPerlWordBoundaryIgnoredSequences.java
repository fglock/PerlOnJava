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
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.junit.Test;

public class TestPerlWordBoundaryIgnoredSequences {
    private static int search(String pattern, String input) {
        byte[] patternBytes = pattern.getBytes(StandardCharsets.UTF_8);
        byte[] inputBytes = input.getBytes(StandardCharsets.UTF_8);
        Regex regex = new Regex(patternBytes, 0, patternBytes.length, Option.NONE,
                UTF8Encoding.INSTANCE, Syntax.PerlNG);
        return regex.matcher(inputBytes).search(0, inputBytes.length, Option.NONE);
    }

    private static void assertBoundary(String left, String right) {
        assertEquals(0, search("\\A" + left + "\\b{wb}" + right + "\\z", left + right));
    }

    private static void assertNoBoundary(String left, String right) {
        assertEquals(0, search("\\A" + left + "\\B{wb}" + right + "\\z", left + right));
    }

    @Test
    public void keepsPerlWordWhitespaceRunsTogether() {
        assertNoBoundary(" ", "\r");
        assertNoBoundary("\r", " ");
        assertNoBoundary(" ", "\n");
        assertNoBoundary("\n", " ");
        assertNoBoundary(" ", "\u000b");
        assertNoBoundary("\u000b", " ");
        assertEquals(0, search("\\A \\B{wb} \\B{wb}\\r\\z", "  \r"));
    }

    @Test
    public void bindsWSegSpaceToExtendOrFormatSuccessors() {
        assertEquals(0, search("\\A \\B{wb} \\b{wb} \\B{wb}\\x{0308}\\z", "   \u0308"));
        assertEquals(0, search("\\A \\B{wb} \\b{wb} \\B{wb}\\x{00ad}\\z", "   \u00ad"));
        assertEquals(0, search("\\A \\B{wb} \\B{wb} \\B{wb}\\x{200d}\\z", "   \u200d"));
        assertEquals(0, search("\\A \\B{wb} \\B{wb}\\t\\B{wb}\\x{200d}\\z", "  \t\u200d"));
        assertEquals(0, search("\\A \\B{wb} \\b{wb}\\t\\B{wb}\\x{0308}\\z", "  \t\u0308"));
        assertEquals(0, search("\\A \\B{wb} \\B{wb}\\x{00a0}\\B{wb}\\x{200d}\\z",
                "  \u00a0\u200d"));
        assertEquals(0, search("\\A \\B{wb} \\B{wb}\\x{2007}\\B{wb}\\x{200d}\\z",
                "  \u2007\u200d"));
        assertEquals(0, search("\\A \\B{wb} \\b{wb}\\x{202f}\\B{wb}\\x{200d}\\z",
                "  \u202f\u200d"));
        assertEquals(0, search("\\A \\b{wb} \\B{wb}\\x{0308}\\z", "  \u0308"));
        assertEquals(0, search("\\A\\r\\B{wb} \\B{wb}\\x{0308}\\z", "\r \u0308"));
        assertEquals(0, search("\\A \\B{wb} \\b{wb}\\x{1680}\\B{wb}\\x{0308}\\z",
                "  \u1680\u0308"));
        assertNoBoundary("\u1680", "\r");
    }

    @Test
    public void keepsNewlineAndIgnoredRulesOrdered() {
        assertBoundary("\r", "\u0308");
        assertBoundary("\n", "\u00ad");
        assertBoundary("\u000b", "\u200d");
        assertEquals(0, search("\\A\\r\\b{wb}\\x{0308}\\b{wb}\\n\\z", "\r\u0308\n"));
    }

    @Test
    public void preservesIgnoredSequenceContext() {
        assertEquals(0, search("\\A\\x{1f469}\\B{wb}\\x{0308}\\B{wb}\\x{200d}"
                + "\\B{wb}\\x{1f680}\\z", "\uD83D\uDC69\u0308\u200d\uD83D\uDE80"));
        assertEquals(0, search("\\A\\x{1f1e6}\\B{wb}\\x{0308}\\B{wb}"
                + "\\x{1f1e7}\\z", "\uD83C\uDDE6\u0308\uD83C\uDDE7"));
        assertEquals(0, search("\\A\\x{1f1e6}\\B{wb}\\x{1f1e7}\\B{wb}\\x{0308}"
                + "\\b{wb}\\x{1f1e8}\\z",
                "\uD83C\uDDE6\uD83C\uDDE7\u0308\uD83C\uDDE8"));
        assertEquals(0, search("\\A\\b{wb}\\x{0308}\\b{wb}A\\b{wb}\\z", "\u0308A"));
        assertEquals(0, search("\\A\\b{wb}A\\B{wb}\\x{0308}\\b{wb}\\z", "A\u0308"));
        assertEquals(0, search("\\A(?: \\B{wb} X| \\B{wb} \\B{wb}\\r\\b{wb}A)\\z", "  \rA"));
    }
}
