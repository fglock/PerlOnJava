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

import org.jcodings.specific.ISO8859_1Encoding;
import org.jcodings.specific.UTF8Encoding;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.junit.Test;

public class TestPerlGraphemeBoundary {
    private static int search(String pattern, String input) {
        byte[] patternBytes = pattern.getBytes(StandardCharsets.UTF_8);
        byte[] inputBytes = input.getBytes(StandardCharsets.UTF_8);
        Regex regex = new Regex(patternBytes, 0, patternBytes.length, Option.NONE,
                UTF8Encoding.INSTANCE, Syntax.PerlNG);
        return regex.matcher(inputBytes).search(0, inputBytes.length, Option.NONE);
    }

    private static void assertBoundary(String left, String right) {
        assertEquals(0, search("\\A" + left + "\\b{gcb}" + right + "\\z", left + right));
    }

    private static void assertNoBoundary(String left, String right) {
        assertEquals(0, search("\\A" + left + "\\B{gcb}" + right + "\\z", left + right));
    }

    @Test
    public void implementsUnicodeGraphemeBreakRules() {
        assertEquals(0, search("\\A\\r\\B{gcb}\\n\\z", "\r\n"));
        assertBoundary("A", "B");
        assertNoBoundary("ᄀ", "ᅡ");
        assertNoBoundary("A", "̈");
        assertNoBoundary("؀", "A");
        assertNoBoundary("क्", "क");
        assertNoBoundary("👩̈‍", "🚀");
        assertNoBoundary("🇦", "🇧");
        assertBoundary("🇦🇧", "🇨");
    }

    @Test
    public void recognizesCrLfInBytePatterns() {
        byte[] input = "\r\n".getBytes(StandardCharsets.ISO_8859_1);
        byte[] pattern = "\\A\r\\B{gcb}\n\\z".getBytes(StandardCharsets.ISO_8859_1);
        Regex regex = new Regex(pattern, 0, pattern.length, Option.PERL_BYTE_PATTERN,
                ISO8859_1Encoding.INSTANCE, Syntax.PerlNG);
        assertEquals(0, regex.matcher(input).search(0, input.length, Option.NONE));

        input = "AB".getBytes(StandardCharsets.ISO_8859_1);
        pattern = "\\AA\\b{gcb}B\\z".getBytes(StandardCharsets.ISO_8859_1);
        regex = new Regex(pattern, 0, pattern.length, Option.PERL_BYTE_PATTERN,
                ISO8859_1Encoding.INSTANCE, Syntax.PerlNG);
        assertEquals(0, regex.matcher(input).search(0, input.length, Option.NONE));
    }
}
