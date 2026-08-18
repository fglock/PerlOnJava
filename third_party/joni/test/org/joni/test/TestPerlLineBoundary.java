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

public class TestPerlLineBoundary {
    private static int search(String pattern, String input) {
        byte[] patternBytes = pattern.getBytes(StandardCharsets.UTF_8);
        byte[] inputBytes = input.getBytes(StandardCharsets.UTF_8);
        Regex regex = new Regex(patternBytes, 0, patternBytes.length, Option.NONE,
                UTF8Encoding.INSTANCE, Syntax.PerlNG);
        return regex.matcher(inputBytes).search(0, inputBytes.length, Option.NONE);
    }

    private static String pattern(String left, String assertion, String right) {
        return "\\A\\Q" + left + "\\E" + assertion + "\\Q" + right + "\\E\\z";
    }

    private static void assertBoundary(String left, String right) {
        String input = left + right;
        assertEquals(0, search(pattern(left, "\\b{lb}", right), input));
        assertEquals(-1, search(pattern(left, "\\B{lb}", right), input));
    }

    private static void assertNoBoundary(String left, String right) {
        String input = left + right;
        assertEquals(-1, search(pattern(left, "\\b{lb}", right), input));
        assertEquals(0, search(pattern(left, "\\B{lb}", right), input));
    }

    @Test
    public void implementsUnicodeLineBreakRules() {
        assertNoBoundary("", "A");
        assertBoundary("A", "");
        assertBoundary("\u000b", "A");
        assertNoBoundary("\r", "\n");
        assertBoundary("\r", "A");
        assertNoBoundary("A", "\n");
        assertNoBoundary("A", " ");
        assertBoundary("\u200b ", "A");
        assertNoBoundary("\u200d", "A");
        assertNoBoundary("A", "\u0308");
        assertBoundary(" ", "\u0308");
        assertNoBoundary("A", "\u2060");
        assertNoBoundary("\u2060", "A");
        assertNoBoundary("\u00a0", "A");
        assertNoBoundary("A", "\u00a0");
        assertBoundary(" ", "\u00a0");
        assertNoBoundary("A ", "]");
        assertNoBoundary("( ", "A");
        assertBoundary(" ", ".5");
        assertNoBoundary("A ", ",");
        assertNoBoundary(") ", "\u3041");
        assertNoBoundary("\u2014 ", "\u2014");
        assertBoundary("A ", "B");
        assertNoBoundary("A", "\"");
        assertBoundary("A", "\ufffc");
        assertBoundary("\ufffc", "A");
        assertNoBoundary(" -", "A");
        assertNoBoundary("A", "-");
        assertNoBoundary("A", "\u2026");
        assertNoBoundary("A", "1");
        assertNoBoundary("$", "\u4e2d");
        assertNoBoundary("$", "A");
        assertNoBoundary("12,", "3");
        assertNoBoundary("\u1100", "\u1161");
        assertNoBoundary("A", "B");
        assertNoBoundary("\ud804\udc03", "\u1b05");
        assertNoBoundary(".", "A");
        assertNoBoundary("A", "(");
        assertNoBoundary("\ud83c\udde6", "\ud83c\udde7");
        assertBoundary("\ud83c\udde6\ud83c\udde7", "\ud83c\udde8");
        assertNoBoundary("\u261d", "\ud83c\udffb");
        assertBoundary("\u4e2d", "\u6587");
    }
}
