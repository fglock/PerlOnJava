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

public class TestPerlAsciiStrictBackreferenceFold {
    private static final int AA = Option.IGNORECASE | Option.PERL_ASCII_STRICT;

    private static int search(String pattern, String input, int options) {
        byte[] patternBytes = pattern.getBytes(StandardCharsets.UTF_8);
        byte[] inputBytes = input.getBytes(StandardCharsets.UTF_8);
        Regex regex = new Regex(patternBytes, 0, patternBytes.length, options,
                UTF8Encoding.INSTANCE, Syntax.PerlNG);
        return regex.matcher(inputBytes).search(0, inputBytes.length, Option.NONE);
    }

    private static void matches(String pattern, String input, int options) {
        assertEquals(0, search(pattern, input, options));
    }

    private static void misses(String pattern, String input, int options) {
        assertEquals(-1, search(pattern, input, options));
    }

    @Test
    public void retainsSafeNumberedAndNamedMultiSourceFolds() {
        matches("^(ſſ)\\1$", "ſſß", AA);
        matches("^(ß)\\1$", "ßſſ", AA);
        matches("^(?<g>ſſ)\\k<g>$", "ſſẞ", AA);
        matches("^(?<g>ẞ)\\k<g>$", "ẞſſ", AA);
    }

    @Test
    public void rejectsAsciiCrossingsUnderAsciiStrict() {
        misses("^(K)\\1$", "KK", AA);
        misses("^(K)\\1$", "KK", AA);
        misses("^(?<g>s)\\k<g>$", "sſ", AA);
        misses("^(?<g>ſ)\\k<g>$", "ſs", AA);
    }

    @Test
    public void retainsPlainIgnoreCaseAndSingleAsciiModifierBehavior() {
        matches("^(K)\\1$", "KK", Option.IGNORECASE);
        matches("(?ia:^(K)\\1$)", "KK", Option.NONE);
        matches("^(s)\\1$", "sſ", Option.IGNORECASE);
        matches("(?ia:^(s)\\1$)", "sſ", Option.NONE);
    }

    @Test
    public void retainsOrdinaryAsciiAndNonAsciiFolds() {
        matches("^(ß)\\1$", "ßẞ", AA);
        matches("^(?<g>ẞ)\\k<g>$", "ẞß", AA);
        matches("^(abc)\\1$", "abcabc", AA);
        matches("^(ä)\\1$", "äÄ", AA);
    }

}
