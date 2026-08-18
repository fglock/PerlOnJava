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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jcodings.Encoding;
import org.jcodings.specific.UTF8Encoding;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.joni.WarnCallback;
import org.joni.WideScalarCodec;
import org.joni.exception.JOniException;
import org.junit.Test;

public class TestPerlWideScalar {
    private static final class TestCodec implements WideScalarCodec {
        private final List<NumericEscape> escapes = new ArrayList<>();

        @Override
        public byte[] encode(long value, Encoding encoding) {
            return marker(value);
        }

        @Override
        public Decoded decode(byte[] bytes, int p, int end, Encoding encoding) {
            if (p + 4 > end || bytes[p] != '~' || bytes[p + 1] != '<') return null;
            long value = 0;
            int cursor = p + 2;
            int digits = 0;
            while (cursor < end && bytes[cursor] != '>') {
                int digit = Character.digit((char)(bytes[cursor] & 0xff), 16);
                if (digit < 0 || digits == 16
                        || value > (Long.MAX_VALUE - digit) / 16) return null;
                value = value * 16 + digit;
                cursor++;
                digits++;
            }
            return digits == 0 || cursor >= end ? null : new Decoded(value, cursor + 1);
        }

        @Override
        public void parsedNumericEscape(NumericEscape escape) {
            escapes.add(escape);
        }
    }

    private static final TestCodec CODEC = new TestCodec();
    private static final Syntax WIDE_PERL = new Syntax(
            "WIDE_PERL", Syntax.PerlNG.op, Syntax.PerlNG.op2, Syntax.PerlNG.op3,
            Syntax.PerlNG.behavior, Syntax.PerlNG.options, Syntax.PerlNG.metaCharTable,
            null, null, CODEC);

    private static byte[] marker(long value) {
        return ("~<" + Long.toHexString(value).toUpperCase(Locale.ROOT) + ">")
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static Regex compile(String pattern, Syntax syntax, WarnCallback warnings) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        return new Regex(bytes, 0, bytes.length, Option.NONE,
                UTF8Encoding.INSTANCE, syntax, warnings);
    }

    private static boolean matches(String pattern, byte[] input) {
        Regex regex = compile("\\A(?:" + pattern + ")\\z", WIDE_PERL, WarnCallback.NONE);
        return regex.matcher(input).search(0, input.length, Option.NONE) == 0;
    }

    @Test
    public void matchesHexAndOctalWideScalarLiteralsAtomically() {
        byte[] value = marker(0x110000L);
        assertTrue(matches("\\x{11_0000}", value));
        assertTrue(matches("\\o{4_200_000}", value));
        assertFalse(matches("\\x{11_0001}", value));
        assertTrue(matches("(?:\\x{11_0000})+", concat(value, value)));
        assertTrue(matches("(?:\\x{11_0000}){2}", concat(value, value)));
        assertTrue(matches("\\x{11_0000}*[\\x{11_0000}]", value));
    }

    @Test
    public void matchesPositiveNegativeAndRangedWideClasses() {
        byte[] lower = marker(0x110000L);
        byte[] upper = marker(0x110001L);
        assertTrue(matches("[\\x{11_0000}]", lower));
        assertFalse(matches("[^\\x{11_0000}]", lower));
        assertTrue(matches("[^\\x{11_0001}]", lower));
        assertTrue(matches("[\\x{11_0000}-\\x{11_0001}]", lower));
        assertTrue(matches("[\\x{11_0000}-\\x{11_0001}]", upper));
        assertTrue(matches("[\\x{11_0000}-\\x{7FFF_FFFF_FFFF_FFFF}]",
                marker(Long.MAX_VALUE)));
        assertTrue(matches("[\\x{11_0000}]*[\\x{11_0000}]", lower));
        assertTrue(matches("[^\\x{10ffff}-\\x{10ffff}]", lower));
    }

    @Test
    public void splitsRangesAtTheUnicodeBoundary() {
        byte[] unicodeMax = new String(Character.toChars(0x10ffff))
                .getBytes(StandardCharsets.UTF_8);
        assertTrue(matches("[\\x{10FFFF}-\\x{11_0000}]", unicodeMax));
        assertTrue(matches("[\\x{10FFFF}-\\x{11_0000}]", marker(0x110000L)));
        assertFalse(matches("[\\x{10FFFF}-\\x{11_0000}]", marker(0x110001L)));
    }

    @Test
    public void preservesLegacyConstructorsWhenNoCodecIsPresent() {
        try {
            compile("\\x{11_0000}", Syntax.PerlNG, WarnCallback.NONE);
            fail("expected the stock Perl syntax Unicode bound");
        } catch (JOniException error) {
            assertEquals(ERR_TOO_BIG_WIDE_CHAR_VALUE, error.getMessage());
        }
    }

    @Test
    public void exposesStructuredTruncationAndRejectsSignedIvOverflow() {
        CODEC.escapes.clear();
        List<String> warnings = new ArrayList<>();
        Regex regex = compile("\\x{11_0000_}", WIDE_PERL, warnings::add);
        byte[] value = marker(0x110000L);
        assertEquals(0, regex.matcher(value).search(0, value.length, Option.NONE));
        assertTrue(CODEC.escapes.get(0).truncated());
        assertEquals('_', CODEC.escapes.get(0).invalidCodePoint());
        assertTrue(warnings.get(0).contains("Non-hex character '_'"));

        try {
            compile("\\x{8000_0000_0000_0000}", WIDE_PERL, WarnCallback.NONE);
            fail("expected signed-IV overflow");
        } catch (JOniException error) {
            assertTrue(error.getMessage().contains("permissible max is 0x7FFFFFFFFFFFFFFF"));
        }

        try {
            compile("\\x{11_0000", WIDE_PERL, WarnCallback.NONE);
            fail("expected missing right brace");
        } catch (JOniException error) {
            assertTrue(error.getMessage().contains("Missing right brace"));
        }

        try {
            compile("[\\x{11_0001}-\\x{11_0000}]", WIDE_PERL, WarnCallback.NONE);
            fail("expected descending range rejection");
        } catch (JOniException error) {
            assertTrue(error.getMessage().contains("empty range in char class"));
        }
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] result = new byte[left.length + right.length];
        System.arraycopy(left, 0, result, 0, left.length);
        System.arraycopy(right, 0, result, left.length, right.length);
        return result;
    }
}
