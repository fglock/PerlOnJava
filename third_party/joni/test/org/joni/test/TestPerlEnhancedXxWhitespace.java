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

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.jcodings.Encoding;
import org.jcodings.specific.ISO8859_1Encoding;
import org.jcodings.specific.UTF8Encoding;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.joni.WarnCallback;
import org.junit.Test;

public class TestPerlEnhancedXxWhitespace {
    private static int search(String pattern, String input, Encoding encoding,
                              Charset charset, int options) {
        byte[] patternBytes = pattern.getBytes(charset);
        byte[] inputBytes = input.getBytes(charset);
        Regex regex = new Regex(patternBytes, 0, patternBytes.length,
                Option.EXTEND | Option.PERL_EXTEND_MORE
                        | Option.PERL_ENHANCED_XX | options,
                encoding, Syntax.PerlNG, WarnCallback.NONE);
        return regex.matcher(inputBytes).search(
                0, inputBytes.length, Option.NONE);
    }

    @Test
    public void ignoresOnlyAsciiWhitespaceInsideEnhancedClasses() {
        for (int code : new int[]{0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x20}) {
            String character = new String(Character.toChars(code));
            assertEquals(-1, search("[a" + character + "b]", character,
                    UTF8Encoding.INSTANCE, StandardCharsets.UTF_8, Option.NONE));
        }
        for (int code : new int[]{0x85, 0x200e, 0x200f, 0x2028, 0x2029}) {
            String character = new String(Character.toChars(code));
            assertEquals(0, search("[a" + character + "b]", character,
                    UTF8Encoding.INSTANCE, StandardCharsets.UTF_8, Option.NONE));
        }
    }

    @Test
    public void preservesSingleByteNelInsideEnhancedClasses() {
        String nel = new String(new byte[]{(byte) 0x85},
                StandardCharsets.ISO_8859_1);
        assertEquals(0, search("[a" + nel + "b]", nel,
                ISO8859_1Encoding.INSTANCE, StandardCharsets.ISO_8859_1,
                Option.PERL_BYTE_PATTERN));
    }
}
