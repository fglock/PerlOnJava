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

import static org.joni.constants.SyntaxProperties.OP2_CCLASS_SET_OP;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.jcodings.Encoding;
import org.jcodings.specific.UTF8Encoding;
import org.joni.CharacterPropertyResolver;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.joni.WideScalarCodec;
import org.junit.Test;

public class TestWideCharacterPropertyResolver {
    private static final long FIRST_WIDE_SCALAR = 0x110000L;

    private static final CharacterPropertyResolver RESOLVER =
            (bytes, p, end, encoding, inCharacterClass) -> {
                String name = new String(bytes, p, end - p, StandardCharsets.UTF_8);
                return switch (name) {
                    case "IntOnly" -> new CharacterPropertyResolver.Result(
                            new int[] {2, 'A', 'A', 0x1f642, 0x1f642}, false);
                    case "Wide" -> new CharacterPropertyResolver.Result(null,
                            new long[] {2, FIRST_WIDE_SCALAR, FIRST_WIDE_SCALAR + 1,
                                    Long.MAX_VALUE, Long.MAX_VALUE}, false);
                    case "Other" -> new CharacterPropertyResolver.Result(null,
                            new long[] {1, FIRST_WIDE_SCALAR + 1,
                                    FIRST_WIDE_SCALAR + 2}, false);
                    case "Crossing" -> new CharacterPropertyResolver.Result(null,
                            new long[] {1, 0x10ffffL, FIRST_WIDE_SCALAR}, false);
                    case "Mixed" -> new CharacterPropertyResolver.Result(
                            new int[] {1, 'A', 'A'},
                            new long[] {1, FIRST_WIDE_SCALAR, FIRST_WIDE_SCALAR},
                            false);
                    case "All" -> new CharacterPropertyResolver.Result(null,
                            new long[] {1, 0, Long.MAX_VALUE}, false);
                    case "Empty" -> new CharacterPropertyResolver.Result(null,
                            new long[] {0}, false);
                    default -> null;
                };
            };

    private static final WideScalarCodec CODEC = new WideScalarCodec() {
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
            return digits == 0 || cursor >= end
                    ? null : new Decoded(value, cursor + 1);
        }
    };

    private static final Syntax SYNTAX = new Syntax(
            "WideCharacterPropertyResolver", Syntax.PerlNG.op,
            Syntax.PerlNG.op2 | OP2_CCLASS_SET_OP, Syntax.PerlNG.op3,
            Syntax.PerlNG.behavior, Syntax.PerlNG.options,
            Syntax.PerlNG.metaCharTable, null, RESOLVER, CODEC);

    private static byte[] marker(long value) {
        return ("~<" + Long.toHexString(value).toUpperCase(Locale.ROOT) + ">")
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] utf8(int codePoint) {
        return new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8);
    }

    private static Regex compile(String pattern, CharacterPropertyResolver resolver) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        Syntax syntax = resolver == RESOLVER ? SYNTAX : new Syntax(
                "WideCharacterPropertyResolver", Syntax.PerlNG.op,
                Syntax.PerlNG.op2 | OP2_CCLASS_SET_OP, Syntax.PerlNG.op3,
                Syntax.PerlNG.behavior, Syntax.PerlNG.options,
                Syntax.PerlNG.metaCharTable, null, resolver, CODEC);
        return new Regex(bytes, 0, bytes.length, Option.NONE,
                UTF8Encoding.INSTANCE, syntax);
    }

    private static boolean matches(String pattern, byte[] input) {
        Regex regex = compile("\\A(?:" + pattern + ")\\z", RESOLVER);
        return regex.matcher(input).search(0, input.length, Option.NONE) == 0;
    }

    @Test
    public void preservesTheExistingIntRangeApi() {
        assertTrue(matches("\\p{IntOnly}", new byte[] {'A'}));
        assertTrue(matches("[\\p{IntOnly}]", utf8(0x1f642)));
        assertFalse(matches("\\p{IntOnly}", new byte[] {'B'}));
        assertTrue(matches("\\P{IntOnly}", new byte[] {'B'}));
    }

    @Test
    public void executesWidePropertiesAndComplementsThroughBytecode() {
        assertTrue(matches("\\p{Wide}", marker(FIRST_WIDE_SCALAR)));
        assertTrue(matches("\\p{Wide}", marker(FIRST_WIDE_SCALAR + 1)));
        assertTrue(matches("\\p{Wide}", marker(Long.MAX_VALUE)));
        assertFalse(matches("\\p{Wide}", marker(FIRST_WIDE_SCALAR + 2)));
        assertFalse(matches("\\p{Wide}", utf8(0x10ffff)));

        assertFalse(matches("\\P{Wide}", marker(FIRST_WIDE_SCALAR)));
        assertTrue(matches("\\P{Wide}", marker(FIRST_WIDE_SCALAR + 2)));
        assertTrue(matches("\\P{Wide}", new byte[] {'A'}));
        assertTrue(matches("\\p{All}", new byte[] {'A'}));
        assertTrue(matches("\\p{All}", marker(Long.MAX_VALUE)));
        assertFalse(matches("\\P{All}", marker(Long.MAX_VALUE)));
        assertFalse(matches("\\p{Empty}", marker(Long.MAX_VALUE)));
        assertTrue(matches("\\P{Empty}", marker(Long.MAX_VALUE)));
    }

    @Test
    public void composesWidePropertiesInCharacterClassSetOperations() {
        assertTrue(matches("[\\p{Wide}]", marker(FIRST_WIDE_SCALAR)));
        assertFalse(matches("[^\\p{Wide}]", marker(FIRST_WIDE_SCALAR)));
        assertTrue(matches("[^\\p{Wide}]", marker(FIRST_WIDE_SCALAR + 2)));

        assertTrue(matches("[\\p{Wide}\\p{Other}]",
                marker(FIRST_WIDE_SCALAR + 2)));
        assertTrue(matches("[\\p{Wide}&&\\p{Other}]",
                marker(FIRST_WIDE_SCALAR + 1)));
        assertFalse(matches("[\\p{Wide}&&\\p{Other}]",
                marker(FIRST_WIDE_SCALAR + 2)));
        assertTrue(matches("[\\p{All}&&\\P{Wide}]",
                marker(FIRST_WIDE_SCALAR + 2)));
        assertFalse(matches("[\\p{All}&&\\P{Wide}]",
                marker(FIRST_WIDE_SCALAR)));
    }

    @Test
    public void splitsCrossingAndMixedResolverRangesAtTheUnicodeBoundary() {
        assertTrue(matches("\\p{Crossing}", utf8(0x10ffff)));
        assertTrue(matches("\\p{Crossing}", marker(FIRST_WIDE_SCALAR)));
        assertFalse(matches("\\p{Crossing}", marker(FIRST_WIDE_SCALAR + 1)));

        assertTrue(matches("\\p{Mixed}", new byte[] {'A'}));
        assertTrue(matches("\\p{Mixed}", marker(FIRST_WIDE_SCALAR)));
        assertFalse(matches("\\p{Mixed}", new byte[] {'B'}));
        assertFalse(matches("\\P{Mixed}", new byte[] {'A'}));
        assertFalse(matches("\\P{Mixed}", marker(FIRST_WIDE_SCALAR)));
        assertTrue(matches("\\P{Mixed}", new byte[] {'B'}));
        assertTrue(matches("\\P{Mixed}", marker(FIRST_WIDE_SCALAR + 1)));
    }

    @Test
    public void rejectsMalformedWideRangeResults() {
        rejects(new long[] {1, FIRST_WIDE_SCALAR});
        rejects(new long[] {Long.MAX_VALUE});
        rejects(new long[] {1, -1, 0});
        rejects(new long[] {1, 2, 1});
        rejects(new long[] {2, 1, 2, 2, 3});
        rejects(new long[] {2, 3, 4, 1, 2});

        try {
            compile("\\p{Bad}", (bytes, p, end, encoding, inCharacterClass) ->
                    new CharacterPropertyResolver.Result(null, null, false));
            fail("expected invalid empty result");
        } catch (IllegalArgumentException error) {
            assertEquals("invalid character property ranges", error.getMessage());
        }
    }

    private static void rejects(long[] ranges) {
        try {
            compile("\\p{Bad}", (bytes, p, end, encoding, inCharacterClass) ->
                    new CharacterPropertyResolver.Result(null, ranges, false));
            fail("expected invalid wide range result");
        } catch (IllegalArgumentException error) {
            assertEquals("invalid character property ranges", error.getMessage());
        }
    }
}
