/*
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
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
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.joni.test;

import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.ISO8859_1Encoding;
import org.jcodings.specific.UTF8Encoding;
import org.joni.Matcher;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.junit.Test;

public class TestPerlInlineDefaultByteWordClass {
    private static int matchEnd(String pattern, byte[] input, int options,
            org.jcodings.Encoding encoding) {
        byte[] patternBytes = pattern.getBytes(StandardCharsets.ISO_8859_1);
        Regex regex = new Regex(patternBytes, 0, patternBytes.length,
                options, encoding, Syntax.PerlNG);
        org.joni.Matcher matcher = regex.matcher(input);
        assertEquals(0, matcher.search(0, input.length, Option.NONE));
        return matcher.getEnd();
    }

    @Test
    public void defaultResetUsesByteWordClassesForBytePatterns() {
        byte[] input = {'F', (byte) 0xf8, 'o'};
        assertEquals(1, matchEnd("^(?^:\\w+)", input,
                Option.PERL_BYTE_PATTERN, ISO8859_1Encoding.INSTANCE));
        assertEquals(1, matchEnd("^(?d:\\w+)", input,
                Option.PERL_BYTE_PATTERN, ISO8859_1Encoding.INSTANCE));
    }

    @Test
    public void defaultResetRetainsUnicodeWordClassesForUtf8Input() {
        byte[] input = "F\u00f8o".getBytes(StandardCharsets.UTF_8);
        assertEquals(input.length, matchEnd("^(?^:\\w+)", input,
                Option.NONE, UTF8Encoding.INSTANCE));
    }

    @Test
    public void byteRangeSearchDoesNotTreatFollowingAsciiAsOutsideRange() {
        byte[] input = {'F', (byte) 0xf8, 'o', '(', '?', '<', 'a', '>'};
        byte[] pattern = "([^\\x20-\\x7e])".getBytes(StandardCharsets.ISO_8859_1);
        Regex regex = new Regex(pattern, 0, pattern.length,
                Option.PERL_BYTE_PATTERN, ISO8859_1Encoding.INSTANCE,
                Syntax.PerlNG);
        Matcher matcher = regex.matcher(input);

        assertEquals(1, matcher.search(0, input.length, Option.NONE));
        assertEquals(2, matcher.getEnd());
        assertEquals(-1, matcher.search(0, 2, input.length, Option.NONE));

        Matcher freshMatcher = regex.matcher(input);
        assertEquals(-1, freshMatcher.search(2, input.length, Option.NONE));

        Regex asciiRangeRegex = new Regex(pattern, 0, pattern.length,
                Option.PERL_BYTE_PATTERN | Option.ASCII_RANGE,
                ISO8859_1Encoding.INSTANCE, Syntax.PerlNG);
        assertEquals(-1, asciiRangeRegex.matcher(input)
                .search(2, input.length, Option.NONE));
        Regex runtimeOptionsRegex = new Regex(pattern, 0, pattern.length,
                Option.PERL_BYTE_PATTERN | Option.ASCII_RANGE
                        | Option.SINGLELINE | Option.CAPTURE_GROUP,
                ISO8859_1Encoding.INSTANCE, Syntax.PerlNG);
        assertEquals(-1, runtimeOptionsRegex.matcher(input)
                .search(2, input.length, Option.NONE));

        byte[] utf8Input = "F\u00f8o(?<a>".getBytes(StandardCharsets.UTF_8);
        Regex utf8Regex = new Regex(pattern, 0, pattern.length,
                Option.ASCII_RANGE, UTF8Encoding.INSTANCE, Syntax.PerlNG);
        Matcher utf8Matcher = utf8Regex.matcher(utf8Input);
        assertEquals(1, utf8Matcher.search(0, utf8Input.length, Option.NONE));
        assertEquals(3, utf8Matcher.getEnd());
        assertEquals(-1, utf8Regex.matcher(utf8Input)
                .search(3, utf8Input.length, Option.NONE));
    }
}
