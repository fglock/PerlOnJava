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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.ASCIIEncoding;
import org.joni.Matcher;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.joni.exception.SyntaxException;
import org.junit.Test;

public class TestPerlMarkShorthand {
    private static Regex regex(String pattern) {
        byte[] bytes = pattern.getBytes(StandardCharsets.US_ASCII);
        return new Regex(bytes, 0, bytes.length, Option.NONE,
                ASCIIEncoding.INSTANCE, Syntax.RUBY);
    }

    private static Matcher matcher(String pattern, String input) {
        return regex(pattern).matcher(input.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    public void shorthandReportsTheSuccessfulBranchAndRestoresOnBacktrack() {
        Matcher matcher = matcher("a(*:first)b|a(*:second)c", "ac");

        assertEquals(0, matcher.search(0, 2, Option.NONE));
        assertEquals(2, matcher.getEnd());
        assertEquals("second", matcher.getControlMark());
        assertNull(matcher.getControlError());
    }

    @Test
    public void failedShorthandPathPublishesItsLabelAsTheControlError() {
        Matcher matcher = matcher("a(*:failed)(*FAIL)", "ac");

        assertEquals(-1, matcher.search(0, 2, Option.NONE));
        assertNull(matcher.getControlMark());
        assertEquals("failed", matcher.getControlError());
    }

    @Test
    public void namedSkipFindsAShorthandMark() {
        Matcher matcher = matcher("a(*:after-a)b(*SKIP:after-a)(*FAIL)|b", "aab");

        assertEquals(2, matcher.search(0, 3, Option.NONE));
        assertEquals(3, matcher.getEnd());
    }

    @Test
    public void shorthandRetainsALongLabelExactly() {
        Matcher matcher = matcher("(*:YYYYYYYYYYYYYYYY)B", "B");

        assertEquals(0, matcher.search(0, 1, Option.NONE));
        assertEquals("YYYYYYYYYYYYYYYY", matcher.getControlMark());
    }

    @Test
    public void shorthandRejectsEmptyAndUnterminatedLabels() {
        assertThrows(SyntaxException.class, () -> regex("(*:)"));
        assertThrows(SyntaxException.class, () -> regex("(*:unterminated"));
    }
}
