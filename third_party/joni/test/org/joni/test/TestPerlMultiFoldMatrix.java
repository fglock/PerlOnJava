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
import org.joni.Matcher;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.junit.Test;

public class TestPerlMultiFoldMatrix {
    private static int search(String pattern, String input, int options) {
        return search(pattern, input, options, Syntax.PerlNG);
    }

    private static int search(String pattern, String input, int options, Syntax syntax) {
        byte[] patternBytes = pattern.getBytes(StandardCharsets.UTF_8);
        byte[] inputBytes = input.getBytes(StandardCharsets.UTF_8);
        Regex regex = new Regex(patternBytes, 0, patternBytes.length, options,
                UTF8Encoding.INSTANCE, syntax);
        Matcher matcher = regex.matcher(inputBytes);
        return matcher.search(0, inputBytes.length, Option.NONE);
    }

    private static void matches(String pattern, String input) {
        assertEquals(0, search(pattern, input, Option.IGNORECASE));
    }

    private static void misses(String pattern, String input) {
        assertEquals(-1, search(pattern, input, Option.IGNORECASE));
    }

    @Test
    public void foldsSharpSAndLigaturesInBothDirections() {
        matches("^ß$", "ss");
        matches("^ss$", "ß");
        matches("^ß$", "ſſ");
        matches("^[ﬆ]$", "st");
        matches("^st$", "ﬆ");
        matches("^ﬃ$", "ffi");
        matches("^ffi$", "ﬃ");
    }

    @Test
    public void foldsTwoAndThreeCodepointSequencesInBothDirections() {
        matches("^ǰ$", "ǰ");
        matches("^ǰ$", "ǰ");
        matches("^ΐ$", "ΐ");
        matches("^ΐ$", "ΐ");
    }

    @Test
    public void foldsCapitalAndLowercaseSharpSSiblings() {
        matches("^ẞ$", "ß");
        matches("^ß$", "ẞ");
    }

    @Test
    public void scopesIgnoreCaseAndAsciiStrictOptions() {
        assertEquals(0, search("^x(?i:ß)y$", "xssy", Option.NONE));
        assertEquals(-1, search("^x(?-i:ß)y$", "xssy", Option.IGNORECASE));
        assertEquals(0, search("^(?i:s)(?iaa:s)(?i:s)$", "ſsſ", Option.NONE));
    }

    @Test
    public void distinguishesPerlAsciiAndAsciiStrictFolding() {
        assertEquals(0, search("^ss$", "ß", Option.IGNORECASE | Option.ASCII_RANGE));
        assertEquals(0, search("^ß$", "ss", Option.IGNORECASE | Option.ASCII_RANGE));
        assertEquals(-1, search("^ss$", "ß",
                Option.IGNORECASE | Option.PERL_ASCII_STRICT));
        assertEquals(-1, search("^ß$", "ss",
                Option.IGNORECASE | Option.PERL_ASCII_STRICT));
        assertEquals(0, search("^ä$", "Ä",
                Option.IGNORECASE | Option.PERL_ASCII_STRICT));
        assertEquals(-1, search("^ﬃ$", "ffi",
                Option.IGNORECASE | Option.PERL_ASCII_STRICT));
        assertEquals(-1, search("^ﬆ$", "st",
                Option.IGNORECASE | Option.PERL_ASCII_STRICT));
        assertEquals(0, search("^ΐ$", "ΐ",
                Option.IGNORECASE | Option.PERL_ASCII_STRICT));
    }

    @Test
    public void appliesFoldsToPositiveAndNegativeClasses() {
        matches("^[k]$", "K");
        misses("^[^k]$", "K");
        matches("^[^k]$", "x");
        matches("^[ﬆ]$", "st");
        misses("^[^ﬆ]$", "ﬆ");
    }

    @Test
    public void appliesEligibleFoldsInsideComposedClasses() {
        String pattern = "^[[ksä]&&[^x]]$";
        int options = Option.IGNORECASE;
        assertEquals(0, search(pattern, "K", options, Syntax.DEFAULT));
        assertEquals(0, search(pattern, "ſ", options, Syntax.DEFAULT));
        assertEquals(0, search(pattern, "Ä", options, Syntax.DEFAULT));
        assertEquals(-1, search(pattern, "x", options, Syntax.DEFAULT));
    }
}
