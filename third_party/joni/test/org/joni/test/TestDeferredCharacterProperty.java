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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import org.jcodings.Encoding;
import org.jcodings.specific.UTF8Encoding;
import org.joni.CharacterPropertyResolver;
import org.joni.Matcher;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.joni.WideScalarCodec;
import org.joni.exception.SyntaxException;
import org.junit.Test;

public class TestDeferredCharacterProperty {
    private static final CharacterPropertyResolver PARSER_RESOLVER =
            new CharacterPropertyResolver() {
                @Override
                public Result resolve(byte[] bytes, int p, int end,
                        Encoding encoding, boolean inCharacterClass) {
                    return Result.deferred();
                }

                @Override
                public Result resolve(byte[] bytes, int p, int end,
                        Encoding encoding, Context context, int option) {
                    return Result.deferred();
                }
            };

    private static final Syntax SYNTAX = new Syntax(
            "DeferredCharacterProperty", Syntax.PerlNG.op, Syntax.PerlNG.op2,
            Syntax.PerlNG.op3, Syntax.PerlNG.behavior, Syntax.PerlNG.options,
            Syntax.PerlNG.metaCharTable, null, PARSER_RESOLVER);

    private static final WideScalarCodec WIDE_CODEC = new WideScalarCodec() {
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

    private static final Syntax WIDE_SYNTAX = new Syntax(
            "DeferredWideCharacterProperty", Syntax.PerlNG.op,
            Syntax.PerlNG.op2, Syntax.PerlNG.op3,
            Syntax.PerlNG.behavior, Syntax.PerlNG.options,
            Syntax.PerlNG.metaCharTable, null, PARSER_RESOLVER, WIDE_CODEC);

    @Test
    public void resolvesOnlyWhenTheDeferredOpcodeIsReached() {
        AtomicInteger calls = new AtomicInteger();
        CharacterPropertyResolver.DeferredResolver resolver =
                (name, context, option, position, encoding) -> {
                    calls.incrementAndGet();
                    return member('A');
                };

        assertTrue(matches("^(?:Z|\\p{Lazy})$", "Z", resolver));
        assertEquals(0, calls.get());
        assertTrue(matches("^(?:\\p{Optional})?$", "", resolver));
        assertEquals(0, calls.get());

        Regex optimized = compile("\\p{Optimized}Z");
        assertEquals("Z", optimized.getOptimizationInfo().exact());
        assertFalse(matches(optimized, "QQQ", resolver));
        assertEquals(0, calls.get());
        assertTrue(matches(optimized, "AZ", resolver));
        assertEquals(1, calls.get());
    }

    @Test
    public void preservesFollowingExactOptimizationForWideRuntimeMembers() {
        Regex regex = compile("\\p{Wide}Z", WIDE_SYNTAX);
        assertEquals("Z", regex.getOptimizationInfo().exact());
        AtomicInteger calls = new AtomicInteger();
        CharacterPropertyResolver.DeferredResolver resolver =
                (name, context, option, position, encoding) -> {
                    calls.incrementAndGet();
                    return new CharacterPropertyResolver.Result(null,
                            new long[] {1, 0x110000L, 0x110000L}, false);
                };
        assertFalse(matches(regex, bytes("QQQ"), resolver));
        assertEquals(0, calls.get());
        byte[] marker = marker(0x110000L);
        byte[] input = java.util.Arrays.copyOf(marker, marker.length + 1);
        input[marker.length] = 'Z';
        assertTrue(matches(regex, input, resolver));
        assertEquals(1, calls.get());
    }

    @Test
    public void composesStaticTokenAndOuterNegationExactlyOnce() {
        AtomicInteger calls = new AtomicInteger();
        CharacterPropertyResolver.DeferredResolver resolver =
                (name, context, option, position, encoding) -> {
                    calls.incrementAndGet();
                    return member('A');
                };

        assertTrue(matches("^[Q\\p{Mix}]$", "Q", resolver));
        assertTrue(matches("^[Q\\p{Mix}]$", "A", resolver));
        assertFalse(matches("^[Q\\p{Mix}]$", "B", resolver));
        assertTrue(matches("^[^\\P{Mix}]$", "A", resolver));
        assertFalse(matches("^[^\\P{Mix}]$", "B", resolver));
        assertEquals(5, calls.get());
    }

    @Test
    public void passesTokenLocalIgnoreCaseWithoutFoldingTheRuntimeResult() {
        List<Boolean> modes = new ArrayList<>();
        CharacterPropertyResolver.DeferredResolver resolver =
                (name, context, option, position, encoding) -> {
                    modes.add(Option.isIgnoreCase(option));
                    return member('a');
                };

        assertTrue(matches("(?i)^\\p{Mode}$", "a", resolver));
        assertFalse(matches("(?i)^\\p{Mode}$", "A", resolver));
        assertEquals(List.of(true, true), modes);

        CharacterPropertyResolver.DeferredResolver foldable =
                (name, context, option, position, encoding) ->
                        new CharacterPropertyResolver.Result(
                                new int[] {1, 'A', 'A'}, true);
        assertTrue(matches("(?i)^\\p{Foldable}$", "a", foldable));
    }

    @Test
    public void failedResolutionIsRetryableAndSuccessfulResultsAreSnapshotted() {
        Regex regex = compile("^\\p{Retry}$");
        byte[] input = bytes("A");
        Matcher matcher = regex.matcher(input);
        AtomicInteger calls = new AtomicInteger();
        CharacterPropertyResolver.Result returned = member('A');
        matcher.setDeferredPropertyResolver((name, context, option, position, encoding) -> {
            if (calls.getAndIncrement() == 0) return null;
            return returned;
        });

        try {
            matcher.search(0, input.length, Option.NONE);
            fail("missing deferred result must fail");
        } catch (CharacterPropertyResolver.ResolutionException expected) {
            assertNotNull(expected.getMessage());
        }
        assertEquals(0, matcher.search(0, input.length, Option.NONE));
        returned.ranges[1] = 'B';
        assertEquals(0, matcher.search(0, input.length, Option.NONE));
        assertEquals(2, calls.get());
    }

    @Test
    public void retainsContextsAndRejectsExtendedUnknownsAtCompileTime() {
        List<CharacterPropertyResolver.Context> contexts = new ArrayList<>();
        CharacterPropertyResolver resolver = new CharacterPropertyResolver() {
            @Override
            public Result resolve(byte[] name, int p, int end,
                    Encoding encoding, boolean inClass) {
                return Result.deferred();
            }

            @Override
            public Result resolve(byte[] name, int p, int end,
                    Encoding encoding, Context context, int option) {
                contexts.add(context);
                return Result.deferred();
            }
        };
        String outsidePattern = "\\p{Outside}";
        String standardPattern = "[\\p{Standard}]";
        Regex outside = compile(outsidePattern, resolver);
        Regex standard = compile(standardPattern, resolver);
        assertEquals(List.of(CharacterPropertyResolver.Context.OUTSIDE_CHARACTER_CLASS,
                CharacterPropertyResolver.Context.STANDARD_CHARACTER_CLASS), contexts);

        List<Integer> positions = new ArrayList<>();
        CharacterPropertyResolver.DeferredResolver runtimeResolver =
                (name, context, option, position, encoding) -> {
                    positions.add(position);
                    return member('A');
                };
        assertTrue(matches(outside, "A", runtimeResolver));
        assertTrue(matches(standard, "A", runtimeResolver));
        assertEquals(List.of(outsidePattern.indexOf('}') + 1,
                standardPattern.indexOf('}') + 1), positions);

        try {
            compile("(?[ \\p{Extended} ])", resolver);
            fail("extended unknown must remain compile-fatal");
        } catch (SyntaxException expected) {
            assertTrue(expected.getMessage().contains("Extended"));
        }
        assertEquals(Regex.DebugProgramKind.OTHER,
                compile("\\p{Debug}").firstDebugProgramFact().kind());
    }

    private static CharacterPropertyResolver.Result member(int codePoint) {
        return new CharacterPropertyResolver.Result(
                new int[] {1, codePoint, codePoint}, false);
    }

    private static Regex compile(String pattern) {
        return compile(pattern, PARSER_RESOLVER);
    }

    private static Regex compile(String pattern,
            CharacterPropertyResolver resolver) {
        byte[] source = bytes(pattern);
        Syntax syntax = resolver == PARSER_RESOLVER ? SYNTAX : new Syntax(
                "DeferredCharacterProperty", Syntax.PerlNG.op,
                Syntax.PerlNG.op2, Syntax.PerlNG.op3,
                Syntax.PerlNG.behavior, Syntax.PerlNG.options,
                Syntax.PerlNG.metaCharTable, null, resolver);
        return new Regex(source, 0, source.length, Option.NONE,
                UTF8Encoding.INSTANCE, syntax);
    }

    private static Regex compile(String pattern, Syntax syntax) {
        byte[] source = bytes(pattern);
        return new Regex(source, 0, source.length, Option.NONE,
                UTF8Encoding.INSTANCE, syntax);
    }

    private static boolean matches(String pattern, String input,
            CharacterPropertyResolver.DeferredResolver resolver) {
        return matches(compile(pattern), input, resolver);
    }

    private static boolean matches(Regex regex, String input,
            CharacterPropertyResolver.DeferredResolver resolver) {
        return matches(regex, bytes(input), resolver);
    }

    private static boolean matches(Regex regex, byte[] value,
            CharacterPropertyResolver.DeferredResolver resolver) {
        Matcher matcher = regex.matcher(value);
        matcher.setDeferredPropertyResolver(resolver);
        return matcher.search(0, value.length, Option.NONE) >= 0;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] marker(long value) {
        return ("~<" + Long.toHexString(value).toUpperCase(Locale.ROOT) + ">")
                .getBytes(StandardCharsets.US_ASCII);
    }
}
