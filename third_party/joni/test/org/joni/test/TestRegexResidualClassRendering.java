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

public class TestRegexResidualClassRendering {
    private static final CharacterPropertyResolver RESOLVER =
            new CharacterPropertyResolver() {
                @Override
                public Result resolve(byte[] bytes, int p, int end,
                        Encoding encoding, boolean inCharacterClass) {
                    return null;
                }

                @Override
                public boolean hasAuthoritativePerlClassSemantics() {
                    return true;
                }
            };

    private static final WideScalarCodec CODEC = new WideScalarCodec() {
        @Override
        public byte[] encode(long value, Encoding encoding) {
            return Long.toHexString(value).getBytes(StandardCharsets.US_ASCII);
        }

        @Override
        public Decoded decode(byte[] bytes, int p, int end,
                Encoding encoding) {
            return null;
        }
    };

    private static final Syntax SYNTAX = new Syntax(
            "ResidualClassRendering", Syntax.PerlNG.op, Syntax.PerlNG.op2,
            Syntax.PerlNG.op3, Syntax.PerlNG.behavior, Syntax.PerlNG.options,
            Syntax.PerlNG.metaCharTable, null, RESOLVER, CODEC);

    @Test
    public void rendersAnyofmAndNanyofmFamilies() {
        assertDescription("[[{]", "ANYOFM[\\[\\{]");
        assertDescription("[[:ascii:]]", "ANYOFM[\\x00-\\x7F]");
        assertDescription("[[:^ascii:]]", "NANYOFM[\\x00-\\x7F]");
        assertDescription("[[:^ascii:]\\x{2C2}]",
                "NANYOFM[\\x00-\\x7F]");
        assertDescription("(?u)[[:ascii:]]", "ANYOFM[\\x00-\\x7F]");
        assertDescription("(?u)[[:^ascii:]]", "NANYOFM[\\x00-\\x7F]");
        assertDescription("(?a)[[:ascii:]]", "ANYOFM[\\x00-\\x7F]");
        assertDescription("(?a)[[:^ascii:]]", "NANYOFM[\\x00-\\x7F]");
        assertDescription("(?a)[[:^ascii:]\\x{2C2}]",
                "NANYOFM[\\x00-\\x7F]");
        assertDescription("(?i)b[s]\\xe0", "ANYOFM[Bb]");
        assertDescription("[aA]", "ANYOFM[Aa]");
        assertDescription("[bB]", "ANYOFM[Bb]");
        assertDescription("[kK]", "ANYOFM[Kk]");
        assertDescription("(?i:[^:])", "NANYOFM[:]");
    }

    @Test
    public void keepsMaskFalsePositiveControlsOnFallback() {
        assertDescription("[a]", "");
        assertDescription("[[:digit:]]", "POSIXU[\\d]");
        assertDescription("(?l)[aA]", "");
        assertDescription("[aA]", ISO8859_1Encoding.INSTANCE, "");
    }

    @Test
    public void rendersGenericWideAndInvertedFamilies() {
        assertDescription("[\\xC5\\xE5]", "ANYOF[\\xC5\\xE5]");
        assertDescription("[^\\S ]",
                "ANYOFD[\\t\\n\\x0B\\f\\r{utf8}\\x85\\xA0]"
                + "[1680 2000-200A 2028-2029 202F 205F 3000]");
        assertDescription("[^\\n\\r]", "ANYOF[^\\n\\r][0100-INFTY]");
        assertDescription("[^\\/\\|,\\$\\%%\\@\\ \\%\\\"\\<\\>"
                + "\\:\\#\\&\\*\\{\\}\\[\\]\\(\\)]",
                "ANYOF[^ \"\\#$%&()*,/:<>@\\[\\]\\{|\\}]"
                + "[0100-INFTY]");
        assertDescription("[^[:^print:][:^ascii:]b]",
                "ANYOF[^\\x00-\\x1Fb\\x7F-\\xFF][0100-INFTY]");
        assertDescription("[_[:blank:]]",
                "ANYOFD[\\t _{utf8}\\xA0]"
                + "[1680 2000-200A 202F 205F 3000]");
        assertDescription("[\\xA0[:^blank:]]",
                "ANYOF[^\\t ][0100-167F 1681-1FFF 200B-202E "
                + "2030-205E 2060-2FFF 3001-INFTY]");
        assertDescription("[\\p{Any}]",
                "ANYOF[\\x00-\\xFF][0100-10FFFF]");
        assertDescription("[\\x{00}-\\x{7FFFFFFFFFFFFFFF}]",
                "ANYOF[\\x00-\\xFF][0100-HIGHEST_CP]");
    }

    @Test
    public void keepsGenericFalsePositiveControlsOnFallback() {
        assertDescription("[\\p{Digit}]", "");
        assertDescription("[^\\n]", "REG_ANY");
    }

    @Test
    public void rendersLocaleRequiredUtf8Families() {
        assertDescription("(?l)(?[\\x{2029}])",
                "ANYOFL{utf8-locale-reqd}[2029]");
        assertDescription("(?il)(?[\\x{212A}])",
                "ANYOFL{utf8-locale-reqd}[Kk][212A]");
        assertDescription("(?li:[a-z])",
                "ANYOFL{i}[a-z{utf8 locale}\\x{017F}\\x{212A}]");
    }

    @Test
    public void keepsLocaleFalsePositiveControlsOnFallback() {
        assertDescription("(?u:[a-z])", "");
        assertDescription("(?a:[a-z])", "");
        assertDescription("(?l)[a-z]", "");
        assertDescription("(?li:[a-z])", ISO8859_1Encoding.INSTANCE, "");
    }

    private static void assertDescription(String pattern, String expected) {
        assertDescription(pattern, UTF8Encoding.INSTANCE, expected);
    }

    private static void assertDescription(String pattern, Encoding encoding,
            String expected) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        Regex regex = new Regex(bytes, 0, bytes.length,
                Option.CAPTURE_GROUP, encoding, SYNTAX);
        assertEquals(pattern + "\n" + regex.byteCodeDebugDescription(),
                expected, regex.perlFirstProgramDebugDescription());
    }
}
