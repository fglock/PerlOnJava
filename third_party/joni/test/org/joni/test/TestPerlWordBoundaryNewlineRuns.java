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

public class TestPerlWordBoundaryNewlineRuns {
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
    public void keepsRunsOfNewlinePropertiesTogether() {
        assertNoBoundary("\r", "\r");
        assertNoBoundary("\r", "\n");
        assertNoBoundary("\n", "\r");
        assertNoBoundary("\n", "\n");
        assertNoBoundary("\r", "\u000b");
        assertNoBoundary("\u000b", "\r");
        assertNoBoundary("\u000b", "\u000b");
        assertNoBoundary("\u0085", "\u2028");
        assertNoBoundary("\u2028", "\u2029");
    }

    @Test
    public void stillBreaksAtNewlineRunEdges() {
        assertBoundary("\r", "A");
        assertBoundary("A", "\r");
        assertBoundary("\u000b", "_");
        assertEquals(0, search("\\A\\b{wb}\\r\\B{wb}\\r\\b{wb}A\\b{wb}\\z", "\r\rA"));
        assertEquals(-1, search("\\A\\B{wb}\\r\\r\\z", "\r\r"));
        assertEquals(-1, search("\\A\\r\\r\\B{wb}\\z", "\r\r"));
    }
}
