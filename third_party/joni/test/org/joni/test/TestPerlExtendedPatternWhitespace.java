/*
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to
 * deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or
 * sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.joni.test;

import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.UTF8Encoding;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.junit.Test;

public class TestPerlExtendedPatternWhitespace {
    private static boolean matches(String pattern, String input) {
        byte[] patternBytes = pattern.getBytes(StandardCharsets.UTF_8);
        Regex regex = new Regex(patternBytes, 0, patternBytes.length,
                Option.EXTEND, UTF8Encoding.INSTANCE, Syntax.PerlNG);
        byte[] inputBytes = input.getBytes(StandardCharsets.UTF_8);
        return regex.matcher(inputBytes)
                .search(0, inputBytes.length, Option.NONE) == 0;
    }

    @Test
    public void ignoresPerlPatternWhitespaceUnderExtend() {
        int[] whitespace = {
            0x0009, 0x000a, 0x000b, 0x000c, 0x000d, 0x0020,
            0x0085, 0x200e, 0x200f, 0x2028, 0x2029
        };
        for (int codePoint : whitespace) {
            String pattern = "a" + Character.toString(codePoint) + "b";
            assertEquals(String.format("U+%04X", codePoint), true,
                    matches(pattern, "ab"));
        }
    }

    @Test
    public void retainsOtherUnicodeSpacesAsLiteralsUnderExtend() {
        int[] literals = {0x00a0, 0x1680};
        for (int codePoint : literals) {
            String pattern = "a" + Character.toString(codePoint) + "b";
            assertEquals(String.format("U+%04X", codePoint), false,
                    matches(pattern, "ab"));
            assertEquals(String.format("U+%04X literal", codePoint), true,
                    matches(pattern, "a" + Character.toString(codePoint) + "b"));
        }
    }
}
