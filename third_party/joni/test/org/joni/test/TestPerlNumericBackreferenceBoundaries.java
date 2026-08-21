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

import static org.joni.exception.ErrorMessages.INVALID_BACKREF;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.ISO8859_1Encoding;
import org.jcodings.specific.UTF8Encoding;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.joni.WarnCallback;
import org.joni.exception.SyntaxException;
import org.junit.Test;

public class TestPerlNumericBackreferenceBoundaries {
    private static Regex compile(String pattern) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        return new Regex(bytes, 0, bytes.length, Option.CAPTURE_GROUP,
                UTF8Encoding.INSTANCE, Syntax.PerlNG, WarnCallback.NONE);
    }

    private static void assertInvalid(String pattern) {
        SyntaxException error = assertThrows(SyntaxException.class,
                () -> compile(pattern));
        assertEquals(INVALID_BACKREF, error.getMessage());
    }

    @Test
    public void rejectsNonexistentAndOverflowingDecimalBackreferences() {
        assertInvalid("\\87");
        assertInvalid("a\\87");
        assertInvalid("a\\97");
        for (String digits : new String[] {"2147483648", "2147483649",
                "2147483650", "4294967296", "4294967297", "4294967298"}) {
            assertInvalid("(.)\\g" + digits + "}");
            assertInvalid("(.)\\g{" + digits + "}");
            assertInvalid("(.)\\g{ " + digits + " }");
            assertInvalid("a(.)\\g" + digits + "}");
            assertInvalid("a(.)\\g{" + digits + "}");
            assertInvalid("a(.)\\g{ " + digits + " }");
        }
    }

    @Test
    public void longDecimalEscapesUseOnlyTheMaximalOctalPrefix() {
        for (String digits : new String[] {"2147483648", "2147483649",
                "2147483650", "4294967296", "4294967297", "4294967298"}) {
            int octalLength = 0;
            while (octalLength < 3 && octalLength < digits.length()
                    && digits.charAt(octalLength) <= '7') octalLength++;
            int octal = Integer.parseInt(digits.substring(0, octalLength), 8);
            String tail = digits.substring(octalLength);
            String pattern = "(.)\\" + digits;
            byte[] patternBytes = pattern.getBytes(StandardCharsets.ISO_8859_1);
            Regex regex = new Regex(patternBytes, 0, patternBytes.length,
                    Option.CAPTURE_GROUP, ISO8859_1Encoding.INSTANCE,
                    Syntax.PerlNG, WarnCallback.NONE);
            byte[] input = ("b" + (char)octal + tail)
                    .getBytes(StandardCharsets.ISO_8859_1);
            assertEquals(0, regex.matcher(input).search(0, input.length, Option.NONE));
        }
    }
}
