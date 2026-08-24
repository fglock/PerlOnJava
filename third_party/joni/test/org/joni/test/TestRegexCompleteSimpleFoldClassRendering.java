/*
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
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

import java.nio.charset.StandardCharsets;

import org.jcodings.Encoding;
import org.jcodings.specific.UTF8Encoding;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.joni.WideScalarCodec;
import org.junit.Test;

public class TestRegexCompleteSimpleFoldClassRendering {
    private static final WideScalarCodec CODEC = new WideScalarCodec() {
        @Override
        public byte[] encode(long value, Encoding encoding) {
            return ("~<" + Long.toHexString(value) + ">")
                    .getBytes(StandardCharsets.US_ASCII);
        }

        @Override
        public Decoded decode(byte[] bytes, int p, int end,
                Encoding encoding) {
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
    private static final Syntax SYNTAX = new Syntax(
            "RegexCompleteSimpleFoldClassRendering", Syntax.PerlNG.op,
            Syntax.PerlNG.op2, Syntax.PerlNG.op3,
            Syntax.PerlNG.behavior, Syntax.PerlNG.options,
            Syntax.PerlNG.metaCharTable, null, null, CODEC);
    private static final int[] LATIN1_LOWER = {
            0xe0, 0xe1, 0xe2, 0xe3, 0xe4, 0xe6, 0xe7,
            0xe8, 0xe9, 0xea, 0xeb, 0xec, 0xee, 0xef,
            0xf0, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6,
            0xf8, 0xf9, 0xfa, 0xfb, 0xfc, 0xfd, 0xfe
    };

    @Test
    public void rendersCompleteExplicitSimpleFoldClasses() {
        for (int lower : LATIN1_LOWER) {
            int upper = Character.toUpperCase(lower);
            assertDescription(String.format("[\\x{%x}\\x{%x}]", lower, upper),
                    String.format("EXACTFU <\\x{%x}>", lower));
        }
        assertDescription("[\\x{103}\\x{102}]",
                "EXACTFU_REQ8 <\\x{103}>");
    }

    @Test
    public void retainsNonFoldNamesForU2029InEveryCharsetMode() {
        assertDescription("(?i)[\\x{2029}]", "EXACT_REQ8 <\\x{2029}>");
        assertDescription("(?iu)[\\x{2029}]", "EXACT_REQ8 <\\x{2029}>");
        assertDescription("(?il)[\\x{2029}]", "EXACTL <\\x{2029}>");
        assertDescription("(?iaa)[\\x{2029}]", "EXACT_REQ8 <\\x{2029}>");
    }

    @Test
    public void rejectsIncompleteOrUnsafeFoldClassShapes() {
        assertDescription("[\\x{e0}]", "EXACT_REQ8 <\\x{e0}>");
        assertNotExact("[\\x{e0}\\x{c0}x]");
        assertNotExact("[^\\x{e0}\\x{c0}]");
        assertNotExact("(?l)[\\x{e0}\\x{c0}]");
        assertNotExact("[\\x{e0}\\x{c0}\\p{Latin}]");
        assertNotExact("[\\x{df}\\x{1e9e}]");
    }

    private static void assertDescription(String pattern, String expected) {
        assertEquals(expected, compile(pattern)
                .perlFirstProgramDebugDescription(true));
    }

    private static void assertNotExact(String pattern) {
        assertFalse(compile(pattern).perlFirstProgramDebugDescription(true)
                .startsWith("EXACT"));
    }

    private static Regex compile(String pattern) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        return new Regex(bytes, 0, bytes.length,
                Option.CAPTURE_GROUP, UTF8Encoding.INSTANCE, SYNTAX);
    }
}
