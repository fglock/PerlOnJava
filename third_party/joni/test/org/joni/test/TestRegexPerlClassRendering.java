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

import org.jcodings.Encoding;
import org.jcodings.specific.ISO8859_1Encoding;
import org.jcodings.specific.UTF8Encoding;
import org.joni.CharacterPropertyResolver;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.joni.WideScalarCodec;
import org.junit.Test;

public class TestRegexPerlClassRendering {
    private static final WideScalarCodec CODEC = new WideScalarCodec() {
        @Override
        public byte[] encode(long value, Encoding encoding) {
            return ("~<" + Long.toHexString(value) + ">")
                    .getBytes(StandardCharsets.US_ASCII);
        }

        @Override
        public Decoded decode(byte[] bytes, int p, int end, Encoding encoding) {
            if (p + 4 > end || bytes[p] != '~' || bytes[p + 1] != '<') {
                return null;
            }
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
            return digits == 0 || cursor >= end
                    ? null : new Decoded(value, cursor + 1);
        }
    };

    private static final CharacterPropertyResolver RANGE_PROPERTY =
            (bytes, p, end, encoding, inCharacterClass) ->
                    new CharacterPropertyResolver.Result(
                            new int[]{1, 0x102, 0x104}, false);

    private static final Syntax SYNTAX = new Syntax(
            "RegexPerlClassRendering", Syntax.PerlNG.op, Syntax.PerlNG.op2,
            Syntax.PerlNG.op3, Syntax.PerlNG.behavior, Syntax.PerlNG.options,
            Syntax.PerlNG.metaCharTable, null, null, CODEC);

    private static final Syntax PROPERTY_SYNTAX = new Syntax(
            "RegexPerlClassRenderingProperty", Syntax.PerlNG.op,
            Syntax.PerlNG.op2, Syntax.PerlNG.op3, Syntax.PerlNG.behavior,
            Syntax.PerlNG.options, Syntax.PerlNG.metaCharTable,
            null, RANGE_PROPERTY, CODEC);

    @Test
    public void rendersProvenFiniteHighRangeFamilies() {
        assertDescription("[\\x{102}-\\x{104}]", "ANYOFRb[0102-0104]");
        assertDescription("[\\x{100}-\\x{13f}]", "ANYOFRb[0100-013F]");
        assertDescription("[\\x{13e}-\\x{141}]", "ANYOFR[013E-0141]");
        assertDescription("[\\x{7ff}-\\x{800}]", "ANYOFR[07FF-0800]");
        assertDescription("[\\x{100}-\\x{10ff}]", "ANYOFR[0100-10FF]");
        assertDescription("[\\x{102}\\x{104}]", "ANYOFHbbm[0102 0104]");
        assertDescription("[\\x{100}\\x{13f}]", "ANYOFHbbm[0100 013F]");
        assertDescription("[\\x{102}-\\x{104}\\x{108}-\\x{10a}]",
                "ANYOFHbbm[0102-0104 0108-010A]");
    }

    @Test
    public void leavesUnprovenClassFamiliesOnTheNativeFallback() {
        assertDescription("[\\x{100}]", "");
        assertDescription("[a\\x{100}]", "");
        assertDescription("[^\\x{102}-\\x{104}]", "");
        assertDescription("[\\x{102}\\x{302}]", "");
        assertDescription("[\\x{13f}\\x{140}]", "");
        assertDescription("[\\x{800}\\x{802}]", "");
        assertDescription("[\\x{100000}-\\x{100001}]", "");
        assertDescription("[\\x{100}-\\x{1100}]", "");
        assertDescription("[\\x{102}-\\x{104}\\x{10ffff}]", "");
        assertDescription("[\\x{110000}-\\x{110002}]", "");
        assertDescription("(?i)[\\x{102}]", "");
        assertDescription("(?i:[\\x{102}])", "");
        assertDescription("[\\x{103}\\x{102}]", "");
        assertDescription("[\\p{RendererRanges}]", UTF8Encoding.INSTANCE,
                PROPERTY_SYNTAX, "");
        assertDescription("[[:xdigit:]]", UTF8Encoding.INSTANCE,
                PROPERTY_SYNTAX, "");
        assertDescription("[\\x{102}-\\x{104}]", UTF8Encoding.INSTANCE,
                Syntax.PerlNG, "");
        assertDescription("(?i)[\\x{102}]", UTF8Encoding.INSTANCE,
                Syntax.PerlNG, "");
        assertDescription("(?i:[\\x{102}])", UTF8Encoding.INSTANCE,
                Syntax.PerlNG, "");
        assertDescription("(?l:[\\x{102}-\\x{104}])", "");
        assertDescription("(?l:[\\x{102}\\x{104}])", "");
        assertDescription("[\\x{102}-\\x{104}]",
                UTF8Encoding.INSTANCE, SYNTAX, Option.PERL_LOCALE, "");
        assertDescription("[\\x{80}-\\x{82}]", ISO8859_1Encoding.INSTANCE,
                "");
    }

    private static void assertDescription(String pattern, String expected) {
        assertDescription(pattern, UTF8Encoding.INSTANCE, expected);
    }

    private static void assertDescription(String pattern, Encoding encoding,
            String expected) {
        assertDescription(pattern, encoding, SYNTAX, expected);
    }

    private static void assertDescription(String pattern, Encoding encoding,
            Syntax syntax, String expected) {
        assertDescription(pattern, encoding, syntax, Option.CAPTURE_GROUP,
                expected);
    }

    private static void assertDescription(String pattern, Encoding encoding,
            Syntax syntax, int options, String expected) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        Regex regex = new Regex(bytes, 0, bytes.length, options,
                encoding, syntax);
        assertEquals(expected, regex.perlFirstProgramDebugDescription());
    }
}
