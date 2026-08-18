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

import static org.joni.exception.ErrorMessages.PERL_EMPTY_NAMED_CHARACTER_ESCAPE;
import static org.joni.exception.ErrorMessages.PERL_MISSING_RIGHT_BRACE_ON_NAMED_CHARACTER_ESCAPE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.jcodings.Encoding;
import org.jcodings.specific.ISO8859_1Encoding;
import org.jcodings.specific.UTF8Encoding;
import org.joni.NamedCharacterResolver;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.joni.WarnCallback;
import org.joni.exception.JOniException;
import org.junit.Test;

public class TestPerlNamedCharacter {
    private static Syntax syntax(NamedCharacterResolver resolver) {
        return new Syntax("PerlNamedCharacter", Syntax.PerlNG.op, Syntax.PerlNG.op2,
                Syntax.PerlNG.op3, Syntax.PerlNG.behavior, Syntax.PerlNG.options,
                Syntax.PerlNG.metaCharTable, resolver);
    }

    private static String decode(byte[] bytes, int p, int end, Encoding encoding) {
        Charset charset = encoding == ISO8859_1Encoding.INSTANCE
                ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8;
        return new String(bytes, p, end - p, charset);
    }

    private static final NamedCharacterResolver RESOLVER = (bytes, p, end, encoding) ->
            switch (decode(bytes, p, end, encoding)) {
                case "CAPITAL" -> 'A';
                case "LOWER" -> 'a';
                case "HASH" -> '#';
                case "SPACE" -> ' ';
                case "BYTE" -> 0xe9;
                case "SUPPLEMENTARY" -> 0x1f642;
                default -> throw new IllegalArgumentException("unknown fake name");
            };

    private static Regex compile(String pattern, Encoding encoding,
                                 NamedCharacterResolver resolver) {
        Charset charset = encoding == ISO8859_1Encoding.INSTANCE
                ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8;
        byte[] bytes = pattern.getBytes(charset);
        return new Regex(bytes, 0, bytes.length, Option.NONE, encoding,
                syntax(resolver), WarnCallback.NONE);
    }

    private static int search(String pattern, String input) {
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        return compile(pattern, UTF8Encoding.INSTANCE, RESOLVER)
                .matcher(bytes).search(0, bytes.length, Option.NONE);
    }

    private static void assertSyntaxError(String pattern, String expected) {
        try {
            compile(pattern, UTF8Encoding.INSTANCE, RESOLVER);
            fail("expected syntax error for " + pattern);
        } catch (JOniException error) {
            assertEquals(expected, error.getMessage());
        }
    }

    @Test
    public void resolvesNamesInsideAndOutsideCharacterClasses() {
        assertEquals(0, search("\\N{CAPITAL}", "A"));
        assertEquals(0, search("[\\N{CAPITAL}]", "A"));
        assertEquals(-1, search("\\N{CAPITAL}", "B"));
        assertEquals(-1, search("[\\N{CAPITAL}]", "B"));
    }

    @Test
    public void preservesOptionsAndSupplementaryCodePoints() {
        assertEquals(0, search("(?i)\\N{LOWER}", "A"));
        assertEquals(0, search("(?x)\\N{HASH}", "#"));
        assertEquals(0, search("(?x)[\\N{SPACE}]", " "));
        assertEquals(0, search("\\N{SUPPLEMENTARY}", "\ud83d\ude42"));
        assertEquals(0, search("[\\N{SUPPLEMENTARY}]", "\ud83d\ude42"));
    }

    @Test
    public void reportsMalformedEscapesBeforeCallingResolver() {
        assertSyntaxError("\\N{}", PERL_EMPTY_NAMED_CHARACTER_ESCAPE);
        assertSyntaxError("\\N{CAPITAL", PERL_MISSING_RIGHT_BRACE_ON_NAMED_CHARACTER_ESCAPE);
    }

    @Test
    public void preservesResolverExceptions() {
        IllegalArgumentException expected = new IllegalArgumentException("resolver failure");
        try {
            compile("\\N{FAIL}", UTF8Encoding.INSTANCE,
                    (bytes, p, end, encoding) -> { throw expected; });
            fail("expected resolver exception");
        } catch (IllegalArgumentException error) {
            assertSame(expected, error);
        }
    }

    @Test
    public void suppliesTheActiveSingleByteEncoding() {
        NamedCharacterResolver resolver = (bytes, p, end, encoding) -> {
            assertSame(ISO8859_1Encoding.INSTANCE, encoding);
            assertEquals("BYTE", decode(bytes, p, end, encoding));
            return 0xe9;
        };
        byte[] input = {(byte)0xe9};
        Regex regex = compile("\\N{BYTE}", ISO8859_1Encoding.INSTANCE, resolver);
        assertEquals(0, regex.matcher(input).search(0, input.length, Option.NONE));
    }

    @Test
    public void leavesUnbracedEscapesAndSyntaxesWithoutAResolverUnchanged() {
        assertEquals(0, search("\\N", "N"));
        byte[] pattern = "\\N".getBytes(StandardCharsets.UTF_8);
        byte[] input = "N".getBytes(StandardCharsets.UTF_8);
        Regex regex = new Regex(pattern, 0, pattern.length, Option.NONE,
                UTF8Encoding.INSTANCE, Syntax.PerlNG, WarnCallback.NONE);
        assertEquals(0, regex.matcher(input).search(0, input.length, Option.NONE));
    }
}
