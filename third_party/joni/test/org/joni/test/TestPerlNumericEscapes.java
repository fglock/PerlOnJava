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

import static org.joni.exception.ErrorMessages.ERR_TOO_BIG_WIDE_CHAR_VALUE;
import static org.joni.exception.ErrorMessages.PERL_EMPTY_OCTAL_ESCAPE;
import static org.joni.exception.ErrorMessages.PERL_MISSING_RIGHT_BRACE_ON_HEX_ESCAPE;
import static org.joni.exception.ErrorMessages.PERL_MISSING_RIGHT_BRACE_ON_OCTAL_ESCAPE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.jcodings.Encoding;
import org.jcodings.specific.ISO8859_1Encoding;
import org.jcodings.specific.UTF8Encoding;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.joni.WarnCallback;
import org.joni.exception.JOniException;
import org.junit.Test;

public class TestPerlNumericEscapes {
    private static Regex compile(String pattern, Encoding encoding, WarnCallback warnings) {
        byte[] bytes = pattern.getBytes(encoding == ISO8859_1Encoding.INSTANCE
                ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8);
        return new Regex(bytes, 0, bytes.length, Option.NONE, encoding, Syntax.PerlNG, warnings);
    }

    private static int search(String pattern, String input) {
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        return compile(pattern, UTF8Encoding.INSTANCE, WarnCallback.NONE)
                .matcher(bytes).search(0, bytes.length, Option.NONE);
    }

    private static void assertSyntaxError(String pattern, String expected) {
        try {
            compile(pattern, UTF8Encoding.INSTANCE, WarnCallback.NONE);
            fail("expected syntax error for " + pattern);
        } catch (JOniException error) {
            assertEquals(expected, error.getMessage());
        }
    }

    @Test
    public void parsesUnbracedHighCodePointsWithoutTreatingThemAsUtf8Bytes() {
        assertEquals(0, search("\\x8e", "\u008e"));
        assertEquals(0, search("[\\x8e]", "\u008e"));
        assertEquals(0, search("\\337", "\u00df"));
        assertEquals(0, search("[\\337]", "\u00df"));
        assertEquals(0, search("\\x8e\\337", "\u008e\u00df"));
    }

    @Test
    public void preservesSingleByteOctalPatterns() {
        byte[] input = {(byte)0xdf};
        Regex regex = compile("\\337", ISO8859_1Encoding.INSTANCE, WarnCallback.NONE);
        assertEquals(0, regex.matcher(input).search(0, input.length, Option.NONE));
        regex = compile("[\\337]", ISO8859_1Encoding.INSTANCE, WarnCallback.NONE);
        assertEquals(0, regex.matcher(input).search(0, input.length, Option.NONE));
    }

    @Test
    public void acceptsPerlUnderscoresInBracedHexAndOctalEscapes() {
        assertEquals(0, search("\\x{0_0_4_1}", "A"));
        assertEquals(0, search("[\\x{_4_1}]", "A"));
        assertEquals(0, search("\\o{0_0_1_0_1}", "A"));
        assertEquals(0, search("[\\o{_1_01}]", "A"));
        assertEquals(0, search("\\x{1_0_F}", "\u010f"));
        assertEquals(0, search("\\o{4_1_7}", "\u010f"));
        assertEquals(0, search("\\x{0_0000_0041}", "A"));
    }

    @Test
    public void truncatesInvalidSuffixesAndReportsWarnings() {
        List<String> warnings = new ArrayList<>();
        Regex regex = compile("\\x{41_}", UTF8Encoding.INSTANCE, warnings::add);
        byte[] input = "A".getBytes(StandardCharsets.UTF_8);
        assertEquals(0, regex.matcher(input).search(0, input.length, Option.NONE));
        assertTrue(warnings.get(0).contains(
                "Non-hex character '_' terminates \\x early. Resolved as \"\\x{41}\""));

        warnings.clear();
        regex = compile("\\o{1__01}", UTF8Encoding.INSTANCE, warnings::add);
        input = new byte[] {1};
        assertEquals(0, regex.matcher(input).search(0, input.length, Option.NONE));
        assertTrue(warnings.get(0).contains(
                "Non-octal character '_' terminates \\o early. Resolved as \"\\o{001}\""));
    }

    @Test
    public void preservesLexerOwnedStructuralErrorsAndUnicodeBound() {
        assertEquals(0, search("\\x{}", "\0"));
        assertSyntaxError("\\o{}", PERL_EMPTY_OCTAL_ESCAPE);
        assertSyntaxError("\\x{4_1", PERL_MISSING_RIGHT_BRACE_ON_HEX_ESCAPE);
        assertSyntaxError("\\o{1_01", PERL_MISSING_RIGHT_BRACE_ON_OCTAL_ESCAPE);
        assertSyntaxError("\\x{11_0000}", ERR_TOO_BIG_WIDE_CHAR_VALUE);
        assertSyntaxError("\\o{4_200_000}", ERR_TOO_BIG_WIDE_CHAR_VALUE);
    }
}
