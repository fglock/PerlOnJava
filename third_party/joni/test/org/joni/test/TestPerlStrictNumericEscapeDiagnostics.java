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
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.joni.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

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
import org.joni.exception.SyntaxException;
import org.junit.Test;

public class TestPerlStrictNumericEscapeDiagnostics {
    private record Warning(String message, int position) {}

    private static byte[] bytes(String pattern) {
        return pattern.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String pattern, Encoding encoding) {
        return pattern.getBytes(encoding == ISO8859_1Encoding.INSTANCE
                ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8);
    }

    private static SyntaxException error(String pattern, int options) {
        return error(pattern, options, UTF8Encoding.INSTANCE);
    }

    private static SyntaxException error(String pattern, int options, Encoding encoding) {
        byte[] bytes = bytes(pattern, encoding);
        return assertThrows(SyntaxException.class,
                () -> new Regex(bytes, 0, bytes.length, options,
                        encoding, Syntax.PerlNG, WarnCallback.NONE));
    }

    private static void assertError(String pattern, int options, String message,
                                    int bytePosition) {
        SyntaxException error = error(pattern, options);
        assertEquals(message, error.getMessage());
        assertEquals(bytePosition, error.getPatternPosition());
    }

    private static List<Warning> warnings(String pattern) {
        List<Warning> warnings = new ArrayList<>();
        byte[] bytes = bytes(pattern);
        WarnCallback callback = new WarnCallback() {
            @Override
            public void warn(String message) {
                throw new AssertionError("warning missing position: " + message);
            }

            @Override
            public void warn(String message, int position) {
                warnings.add(new Warning(message, position));
            }

            @Override
            public boolean supportsPositions() {
                return true;
            }
        };
        new Regex(bytes, 0, bytes.length, Option.NONE,
                UTF8Encoding.INSTANCE, Syntax.PerlNG, callback);
        return warnings;
    }

    @Test
    public void strictBracedEscapesAreFatalAtTheInvalidCharacter() {
        int strict = Option.PERL_RE_STRICT;
        assertError("\\o{789}", strict, "Non-octal character", 5);
        assertError("[\\o{789}]", strict, "Non-octal character", 6);
        assertError("\\x{defg}", strict, "Non-hex character", 7);
        assertError("\\o{ 1 20 }", strict, "Non-octal character", 6);
        assertError("\\x{}", strict, "Empty \\x{}", 4);
        assertError("[\\x{}]", strict, "Empty \\x{}", 5);
    }

    @Test
    public void extendedClassBracedEscapesAreFatalWithoutStrictOption() {
        String octal = "(?[ \\o{1038} ])";
        assertError(octal, Option.NONE, "Non-octal character",
                bytes(octal.substring(0, octal.indexOf('8') + 1)).length);
        String hex = "(?[ \\x{defg} ])";
        assertError(hex, Option.NONE, "Non-hex character",
                bytes(hex.substring(0, hex.indexOf('g') + 1)).length);
        String unicode = "(?[ \\o{ネ} ])";
        assertError(unicode, Option.NONE, "Non-octal character",
                bytes(unicode.substring(0, unicode.indexOf('ネ') + 1)).length);

        String bytePattern = "(?[ \\x{ï} ])";
        SyntaxException byteError = error(
                bytePattern, Option.NONE, ISO8859_1Encoding.INSTANCE);
        assertEquals("Non-hex character", byteError.getMessage());
        assertEquals(bytePattern.indexOf('ï') + 1, byteError.getPatternPosition());
    }

    @Test
    public void strictUnbracedHexErrorsRetainTokenEndPositions() {
        int strict = Option.PERL_RE_STRICT;
        assertError("\\xABC", strict,
                "Use \\x{...} for more than two hex characters", 5);
        assertError("[\\xABC]", strict,
                "Use \\x{...} for more than two hex characters", 6);
        assertError("\\xAG", strict, "Non-hex character", 4);
        assertError("\\x", strict, "Empty \\x", 2);
    }

    @Test
    public void strictClassLegacyOctalRequiresExactlyThreeDigits() {
        int strict = Option.PERL_RE_STRICT;
        assertError("[\\08]", strict, "Need exactly 3 octal digits", 4);
        assertError("[\\018]", strict, "Need exactly 3 octal digits", 5);
        assertError("[\\0]", strict, "Need exactly 3 octal digits", 4);
        assertError("[\\07]", strict, "Need exactly 3 octal digits", 5);
        assertError("[\\0005]", strict, "Need exactly 3 octal digits", 7);
        assertError("[\\_\\0]", strict, "Need exactly 3 octal digits", 6);
    }

    @Test
    public void nonStrictWarningsRetainPerlTextAndPositions() {
        assertEquals(List.of(new Warning(
                "Non-octal character '8' terminates \\0 early.  Resolved as \"\\0008\"",
                3)), warnings("\\08"));
        assertEquals(List.of(new Warning(
                "Non-octal character '8' terminates \\0 early.  Resolved as \"\\0008\"",
                4)), warnings("[\\08]"));
        assertEquals(List.of(new Warning(
                "Non-hex character 'G' terminates \\x early.  Resolved as \"\\x0AG\"",
                3)), warnings("\\xAG"));
        assertEquals(List.of(new Warning(
                "Non-hex character 'g' terminates \\x early.  Resolved as \"\\x{ABC}\"",
                8)), warnings("\\x{ABCg}"));
    }
}
