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
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.joni.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.nio.charset.StandardCharsets;

import org.jcodings.Encoding;
import org.jcodings.specific.UTF8Encoding;
import org.joni.CharacterPropertyResolver;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.joni.WideScalarCodec;
import org.junit.Test;

public class TestRegexAnyofAcceptanceRegressions {
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
            "AnyofAcceptanceRegressions", Syntax.PerlNG.op,
            Syntax.PerlNG.op2, Syntax.PerlNG.op3, Syntax.PerlNG.behavior,
            Syntax.PerlNG.options, Syntax.PerlNG.metaCharTable, null,
            RESOLVER, CODEC);

    @Test
    public void exactModeRetainsMaskPresentationAtPerlNodeBoundaries() {
        assertDescription("[aA]", "ANYOFM[Aa]");
        assertDescription("[bB]", "ANYOFM[Bb]");
        assertDescription("(?i)b[s]\\xe0", "ANYOFM[Bb]");

        assertNotDescription("[\\x{e0}\\x{c0}]", "ANYOFM[\\xC0\\xE0]");
    }

    @Test
    public void ignoresOnlyRedundantLiteralsInNegatedBlankClasses() {
        assertDescription("[_[:^blank:]]", "NPOSIXD[:blank:]");
        assertDescription("(?d:[_[:^blank:]])", "NPOSIXD[:blank:]");
        assertDescription("[[:^blank:]\\x{2C2}]", "NPOSIXU[:blank:]");
        assertDescription("(?l)[[:^blank:]\\x{2C2}]", "NPOSIXL[:blank:]");
        assertDescription("(?a)[[:^blank:]\\x{2C2}]", "NPOSIXA[:blank:]");

        assertNotDescription("[ [:^blank:]]", "NPOSIXD[:blank:]");
        assertNotDescription("[\\t[:^blank:]]", "NPOSIXD[:blank:]");
        assertNotDescription("[\\x{A0}[:^blank:]]", "NPOSIXU[:blank:]");
    }

    private static void assertDescription(String pattern, String expected) {
        Regex regex = compile(pattern);
        assertEquals(pattern + "\n" + regex.byteCodeDebugDescription(),
                expected, regex.perlFirstProgramDebugDescription(true));
    }

    private static void assertNotDescription(String pattern,
            String unexpected) {
        Regex regex = compile(pattern);
        assertNotEquals(pattern + "\n" + regex.byteCodeDebugDescription(),
                unexpected, regex.perlFirstProgramDebugDescription(true));
    }

    private static Regex compile(String pattern) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        return new Regex(bytes, 0, bytes.length, Option.CAPTURE_GROUP,
                UTF8Encoding.INSTANCE, SYNTAX);
    }
}
