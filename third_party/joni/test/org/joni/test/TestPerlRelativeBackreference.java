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
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.UTF8Encoding;
import org.joni.Matcher;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.joni.exception.JOniException;
import org.junit.Test;

public class TestPerlRelativeBackreference {
    private static Regex compile(String pattern) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        return new Regex(bytes, 0, bytes.length, Option.CAPTURE_GROUP,
                UTF8Encoding.INSTANCE, Syntax.PerlNG);
    }

    private static Matcher match(String pattern, String input) {
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        Matcher matcher = compile(pattern).matcher(bytes);
        assertEquals(0, matcher.search(0, bytes.length, Option.NONE));
        return matcher;
    }

    private static void assertInvalidBackreference(String pattern, String expectedMessage) {
        try {
            compile(pattern);
            fail("expected invalid backreference for " + pattern);
        } catch (JOniException error) {
            assertEquals(expectedMessage, error.getMessage());
        }
    }

    @Test
    public void unbracedRelativeReferenceMatchesPreviousCapture() {
        Matcher matcher = match("(bar)\\g-1", "barbar");
        assertEquals(0, matcher.getRegion().getBeg(1));
        assertEquals(3, matcher.getRegion().getEnd(1));
    }

    @Test
    public void relativeReferencesPreserveNestedCaptureNumbering() {
        Matcher matcher = match("((a)(b))\\g-1\\g-2\\g-3", "abbaab");
        assertEquals(0, matcher.getRegion().getBeg(1));
        assertEquals(2, matcher.getRegion().getEnd(1));
        assertEquals(0, matcher.getRegion().getBeg(2));
        assertEquals(1, matcher.getRegion().getEnd(2));
        assertEquals(1, matcher.getRegion().getBeg(3));
        assertEquals(2, matcher.getRegion().getEnd(3));
    }

    @Test
    public void multipleRelativeAndAbsoluteReferencesRemainDistinct() {
        match("(a)(b)(c)\\g-1\\g-2\\g-3", "abccba");
        match("(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)\\g-10", "abcdefghija");
        match("(a)(b)\\g1\\g2", "abab");
    }

    @Test
    public void zeroAndOutOfRangeRelativeReferencesAreRejected() {
        assertInvalidBackreference("(a)\\g-0", "Reference to invalid group 0 in regex");
        assertInvalidBackreference("(a)\\g-2", "Reference to nonexistent or unclosed group in regex");
        assertInvalidBackreference("(a\\g-1)", "Reference to nonexistent or unclosed group in regex");
    }
}
