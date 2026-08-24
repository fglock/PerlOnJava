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

public class TestRegexInfinityAnyofRendering {
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
            "InfinityAnyofRendering", Syntax.PerlNG.op, Syntax.PerlNG.op2,
            Syntax.PerlNG.op3, Syntax.PerlNG.behavior, Syntax.PerlNG.options,
            Syntax.PerlNG.metaCharTable, null, RESOLVER, CODEC);

    @Test
    public void distinguishesPerlInfinityFromTheFiniteScalarCeiling() {
        assertDescription("[\\x{00}-\\x{FFFFFFFFFFFFFFFF}]", "SANY");
        assertDescription("[\\x{00}-\\x{7FFFFFFFFFFFFFFF}]",
                "ANYOF[\\x00-\\xFF][0100-HIGHEST_CP]");
        assertDescription("[^\\x{00}-\\x{FFFFFFFFFFFFFFFF}]", "OPFAIL");
        assertNotDescription("[\\x{01}-\\x{FFFFFFFFFFFFFFFF}]", "SANY");
    }

    @Test
    public void keepsLiteralAndDistinctUniversalControlsSeparate() {
        assertExactDescription("[\\x{00}]", "EXACT <\\x{0}>");
        assertDescription("[\\p{Any}]",
                "ANYOF[\\x00-\\xFF][0100-10FFFF]");
    }

    private static void assertDescription(String pattern, String expected) {
        Regex regex = compile(pattern);
        assertEquals(pattern + "\n" + regex.byteCodeDebugDescription(),
                expected, regex.perlFirstProgramDebugDescription());
    }

    private static void assertExactDescription(String pattern,
            String expected) {
        Regex regex = compile(pattern);
        assertEquals(pattern + "\n" + regex.byteCodeDebugDescription(),
                expected, regex.perlFirstProgramDebugDescription(true));
    }

    private static void assertNotDescription(String pattern,
            String unexpected) {
        Regex regex = compile(pattern);
        assertNotEquals(pattern + "\n" + regex.byteCodeDebugDescription(),
                unexpected, regex.perlFirstProgramDebugDescription());
    }

    private static Regex compile(String pattern) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        return new Regex(bytes, 0, bytes.length, Option.CAPTURE_GROUP,
                UTF8Encoding.INSTANCE, SYNTAX);
    }
}
