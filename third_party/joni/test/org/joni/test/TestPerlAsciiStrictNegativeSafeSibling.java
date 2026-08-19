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

public class TestPerlAsciiStrictNegativeSafeSibling {
    private static int search(String pattern, String input) {
        byte[] patternBytes = pattern.getBytes(StandardCharsets.UTF_8);
        byte[] inputBytes = input.getBytes(StandardCharsets.UTF_8);
        Regex regex = new Regex(patternBytes, 0, patternBytes.length,
                Option.IGNORECASE | Option.PERL_ASCII_STRICT,
                UTF8Encoding.INSTANCE, Syntax.PerlNG);
        return regex.matcher(inputBytes).search(0, inputBytes.length, Option.NONE);
    }

    private static void matches(String pattern, String input) {
        assertEquals(0, search(pattern, input));
    }

    private static void misses(String pattern, String input) {
        assertEquals(-1, search(pattern, input));
    }

    @Test
    public void appliesSafeSiblingsToOrdinaryPositiveAndNegativeClasses() {
        matches("^[ß]$", "ẞ");
        misses("^[^ß]$", "ẞ");
        matches("^[ﬅ]$", "ﬆ");
        misses("^[^ﬅ]$", "ﬆ");
        matches("^[^ß]$", "ä");
        matches("^[^ﬅ]$", "ä");
    }

    @Test
    public void rejectsAsciiAtEveryPositionOfAMultiCharacterFold() {
        misses("^[ŉ]$", "ʼn");
        misses("^[ŉ]$", "ʼN");
        matches("^[ŉ]$", "ŉ");
        matches("^[^ŉ]$", "ʼ");
    }
}
