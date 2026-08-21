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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.jcodings.Encoding;
import org.jcodings.specific.UTF8Encoding;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.joni.WideScalarCodec;
import org.joni.WideScalarDomainEnd;
import org.joni.exception.JOniException;
import org.junit.Test;

public class TestPerlClassInfinityEndpoint {
    private static final WideScalarCodec CODEC = new WideScalarCodec() {
        @Override
        public byte[] encode(long value, Encoding encoding) {
            if (value < 0) fail("symbolic infinity reached the executable codec");
            return marker(value);
        }

        @Override
        public Decoded decode(byte[] bytes, int p, int end, Encoding encoding) {
            if (p + 4 > end || bytes[p] != '~' || bytes[p + 1] != '<') return null;
            int close = p + 2;
            while (close < end && bytes[close] != '>') close++;
            if (close == end) return null;
            try {
                long value = Long.parseLong(new String(bytes, p + 2,
                        close - p - 2, StandardCharsets.US_ASCII), 16);
                return new Decoded(value, close + 1);
            } catch (NumberFormatException error) {
                return null;
            }
        }
    };

    private static final Syntax SYNTAX = new Syntax(
            "PerlClassInfinityEndpoint", Syntax.PerlNG.op, Syntax.PerlNG.op2,
            Syntax.PerlNG.op3, Syntax.PerlNG.behavior, Syntax.PerlNG.options,
            Syntax.PerlNG.metaCharTable, null, null, CODEC);

    @Test
    public void acceptsUvMaxOnlyAsTheRightHandRangeEndpoint() {
        compile("[\\x{0}-\\x{FFFF_FFFF_FFFF_FFFF}]");
        compile("[\\o{0}-\\o{1777777777777777777777}]");
        assertCompileFails("\\x{FFFF_FFFF_FFFF_FFFF}");
        assertCompileFails("[\\x{FFFF_FFFF_FFFF_FFFF}]");
        assertCompileFails("[\\x{FFFF_FFFF_FFFF_FFFF}-"
                + "\\x{FFFF_FFFF_FFFF_FFFF}]");
        assertCompileFails("[\\x{0}-\\x{8000_0000_0000_0000}]");
        assertCompileFails("[\\x{0}-\\x{FFFF_FFFF_FFFF_FFFE}]");
        assertCompileFails("[\\x{0}-\\x{1_0000_0000_0000_0000}]");
    }

    @Test
    public void preservesInfinityThroughCanonicalClassCoalescing() {
        Regex infinity = compile("[\\x{102}-\\x{104}"
                + "\\x{108}-\\x{10A}\\x{103}-\\x{109}"
                + "\\x{10C}-\\x{FFFF_FFFF_FFFF_FFFF}]");
        assertEquals("ANYOFH[0102-010A 010C-INFTY]",
                infinity.perlFirstProgramDebugDescription());
        Regex.DebugRange last = infinity.firstDebugProgramFact()
                .characterClass().ranges().get(1);
        assertEquals(WideScalarDomainEnd.PERL_INFINITY, last.domainEnd());

        Regex highest = compile("[\\x{101}-\\x{7FFF_FFFF_FFFF_FFFF}]");
        assertEquals("ANYOFH[0101-HIGHEST_CP]",
                highest.perlFirstProgramDebugDescription());
        assertEquals(WideScalarDomainEnd.HIGHEST_SCALAR,
                highest.firstDebugProgramFact().characterClass()
                        .ranges().get(0).domainEnd());
    }

    @Test
    public void requiresSymbolicInfinityForThePerlFullDomainShape() {
        Regex infinity = compile("[\\x{00}-\\x{FFFF_FFFF_FFFF_FFFF}]");
        assertEquals(Regex.DebugProgramKind.FULL_CLASS,
                infinity.firstDebugProgramFact().kind());
        assertEquals("SANY", infinity.perlFirstProgramDebugDescription());

        Regex highest = compile("[\\x{00}-\\x{7FFF_FFFF_FFFF_FFFF}]");
        assertEquals(Regex.DebugProgramKind.OTHER,
                highest.firstDebugProgramFact().kind());
        assertFalse("SANY".equals(highest.perlFirstProgramDebugDescription()));
    }

    @Test
    public void matchesOnlySignedExecutableSubjects() {
        String source = "[\\x{101}-\\x{FFFF_FFFF_FFFF_FFFF}]";
        assertTrue(matches(source, marker(0x110000)));
        assertTrue(matches(source, marker(Long.MAX_VALUE)));
        assertFalse(matches(source, marker(0x100)));
        try {
            new WideScalarCodec.Decoded(-1L, 1);
            fail("negative executable scalar accepted");
        } catch (IllegalArgumentException expected) {
            assertEquals("wide scalar must be nonnegative", expected.getMessage());
        }
    }

    private static Regex compile(String pattern) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        return new Regex(bytes, 0, bytes.length, Option.NONE,
                UTF8Encoding.INSTANCE, SYNTAX);
    }

    private static boolean matches(String pattern, byte[] input) {
        Regex regex = compile("\\A(?:" + pattern + ")\\z");
        return regex.matcher(input).search(0, input.length, Option.NONE) == 0;
    }

    private static void assertCompileFails(String pattern) {
        try {
            compile(pattern);
            fail("expected permissible-max failure for " + pattern);
        } catch (JOniException error) {
            assertTrue(error.getMessage().contains(
                    "permissible max is 0x7FFFFFFFFFFFFFFF"));
        }
    }

    private static byte[] marker(long value) {
        return ("~<" + Long.toHexString(value).toUpperCase(Locale.ROOT) + ">")
                .getBytes(StandardCharsets.US_ASCII);
    }
}
