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

import static org.joni.exception.ErrorMessages.PERL_LOOK_BEHIND_LONGER_THAN_255;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;

import org.jcodings.Encoding;
import org.jcodings.specific.ISO8859_1Encoding;
import org.jcodings.specific.UTF8Encoding;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.joni.exception.SyntaxException;
import org.junit.Test;

public class TestPerlLookBehindLengthLimit {
    private static Regex compile(String pattern, Encoding encoding) {
        byte[] patternBytes = pattern.getBytes(encoding == ISO8859_1Encoding.INSTANCE
                ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8);
        return new Regex(patternBytes, 0, patternBytes.length, Option.NONE,
                encoding, Syntax.Perl);
    }

    private static void assertAcceptedInByteAndUnicode(String pattern) {
        compile(pattern, ISO8859_1Encoding.INSTANCE);
        compile(pattern, UTF8Encoding.INSTANCE);
    }

    private static void assertTooLongInByteAndUnicode(String pattern) {
        for (Encoding encoding : new Encoding[] {
                ISO8859_1Encoding.INSTANCE, UTF8Encoding.INSTANCE}) {
            SyntaxException error = assertThrows(SyntaxException.class,
                    () -> compile(pattern, encoding));
            assertEquals(PERL_LOOK_BEHIND_LONGER_THAN_255, error.getMessage());
        }
    }

    @Test
    public void perlLookBehindCeilingCountsTheWholeAnalysedWidth() {
        assertAcceptedInByteAndUnicode("(?<= a{253})z");
        assertAcceptedInByteAndUnicode("(?<= a{254})z");
        assertTooLongInByteAndUnicode("(?<= a{255})z");
        assertTooLongInByteAndUnicode("(?<= a{200}b{55})z");
        assertTooLongInByteAndUnicode("(?<= x{1000})z");
    }
}
