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
package org.joni;

import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;

import org.jcodings.Encoding;
import org.jcodings.specific.ISO8859_1Encoding;
import org.jcodings.specific.UTF8Encoding;
import org.junit.Test;

public class TestPerlBackreferenceFoldExpansion {
    private static Regex compile(String pattern, Encoding encoding, int options) {
        byte[] bytes = encoding == UTF8Encoding.INSTANCE
                ? pattern.getBytes(StandardCharsets.UTF_8)
                : pattern.getBytes(StandardCharsets.ISO_8859_1);
        return new Regex(bytes, 0, bytes.length, options,
                encoding, Syntax.Perl);
    }

    private static int search(String pattern, String input, Encoding encoding,
                              int options, boolean reverse) {
        byte[] bytes = encoding == UTF8Encoding.INSTANCE
                ? input.getBytes(StandardCharsets.UTF_8)
                : input.getBytes(StandardCharsets.ISO_8859_1);
        Matcher matcher = compile(pattern, encoding, options).matcher(bytes);
        return reverse
                ? matcher.search(bytes.length, 0, Option.NONE)
                : matcher.search(0, bytes.length, Option.NONE);
    }

    @Test
    public void streamsFullFoldsAcrossBackreferenceCharacterBoundaries() {
        int options = Option.IGNORECASE;
        assertEquals(0, search("(ß)\\1", "ßss",
                UTF8Encoding.INSTANCE, options, false));
        assertEquals(0, search("(ss)\\1", "ssß",
                UTF8Encoding.INSTANCE, options, false));
        assertEquals(0, search("([ß])\\1", "ßss",
                UTF8Encoding.INSTANCE, options, false));
        assertEquals(0, search("(ß)\\1", "ßss",
                UTF8Encoding.INSTANCE, options, true));
    }

    @Test
    public void bytePatternBackreferencesOnlyFoldAscii() {
        int options = Option.IGNORECASE | Option.PERL_BYTE_PATTERN;
        assertEquals(Matcher.FAILED, search("(à)\\1", "àÀ",
                ISO8859_1Encoding.INSTANCE, options, false));
        assertEquals(0, search("(à)\\1", "àà",
                ISO8859_1Encoding.INSTANCE, options, false));
        assertEquals(0, search("(a)\\1", "aA",
                ISO8859_1Encoding.INSTANCE, options, false));
        assertEquals(Matcher.FAILED, search("(à)\\1", "àÀ",
                ISO8859_1Encoding.INSTANCE, options, true));
    }

    @Test
    public void unicodeBackreferencesRetainSimpleLatinFolds() {
        assertEquals(0, search("(à)\\1", "àÀ",
                UTF8Encoding.INSTANCE, Option.IGNORECASE, false));
    }
}
