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

    private static final Syntax SYNTAX = new Syntax(
            "ResidualClassRendering", Syntax.PerlNG.op, Syntax.PerlNG.op2,
            Syntax.PerlNG.op3, Syntax.PerlNG.behavior, Syntax.PerlNG.options,
            Syntax.PerlNG.metaCharTable, null, RESOLVER, null);

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
        assertDescription("[\\xC5\\xE5]", "");
        assertDescription("[a]", "");
        assertDescription("[[:digit:]]", "POSIXU[\\d]");
        assertDescription("(?l)[aA]", "");
        assertDescription("[aA]", ISO8859_1Encoding.INSTANCE, "");
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
