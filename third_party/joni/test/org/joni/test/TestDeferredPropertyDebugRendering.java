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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.jcodings.Encoding;
import org.jcodings.specific.UTF8Encoding;
import org.joni.CharacterPropertyResolver;
import org.joni.Matcher;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.junit.Test;

public class TestDeferredPropertyDebugRendering {
    private static final CharacterPropertyResolver PARSER_RESOLVER =
            new CharacterPropertyResolver() {
                @Override
                public Result resolve(byte[] bytes, int p, int end,
                        Encoding encoding, boolean inCharacterClass) {
                    return resolve(bytes, p, end, encoding,
                            inCharacterClass ? Context.STANDARD_CHARACTER_CLASS
                                    : Context.OUTSIDE_CHARACTER_CLASS,
                            Option.NONE);
                }

                @Override
                public Result resolve(byte[] bytes, int p, int end,
                        Encoding encoding, Context context, int option) {
                    String raw = new String(bytes, p, end - p,
                            encoding.getCharset());
                    if (raw.equals("All")) {
                        return new Result(new int[] {1, 0, 0x10ffff},
                                new long[] {1, 0x110000L, Long.MAX_VALUE},
                                false);
                    }
                    return Result.deferred(bytes("pkg::" + raw));
                }
            };

    private static final Syntax SYNTAX = new Syntax(
            "DeferredPropertyDebugRendering", Syntax.PerlNG.op,
            Syntax.PerlNG.op2, Syntax.PerlNG.op3, Syntax.PerlNG.behavior,
            Syntax.PerlNG.options, Syntax.PerlNG.metaCharTable, null,
            PARSER_RESOLVER);

    @Test
    public void rendersImportedDeferredClassFamilies() {
        assertDescription("[^[:^print:][:^ascii:]b\\p{X}]",
                "ANYOF[^\\x00-\\x1Fb\\x7F-\\xFF{+pkg::X}0100-INFTY]");
        assertDescription("[\\p{X}]", "ANYOF[+pkg::X]");
        assertDescription("[^\\p{X}]", "ANYOF[^{+pkg::X}]");
        assertDescription("[a\\p{X}]", "ANYOF[a][+pkg::X]");
        assertDescription("[^a\\p{X}]", "ANYOF[^a{+pkg::X}]");
        assertDescription("[^a\\x{100}\\p{X}]",
                "ANYOF[^a{+pkg::X}0100]");
        assertDescription("[^\\x{100}-\\x{10ffff}\\p{X}]",
                "ANYOF[^{+pkg::X}0100-10FFFF]");
        assertDescription("[^\\p{All}\\p{X}]", "OPFAIL");
        assertDescription("[\\p{All}\\p{X}]", "SANY");
    }

    @Test
    public void preservesOrderedTermsAndImmutableFacts() {
        Regex regex = compile("[\\p{One}\\P{Two}\\p{One}]");
        assertEquals("ANYOF[+pkg::One !pkg::Two +pkg::One]",
                regex.perlFirstProgramDebugDescription());

        Regex.DebugDeferredCharacterClassFact fact = regex
                .firstDeferredCharacterClassFact().orElseThrow();
        assertEquals(List.of("pkg::One", "pkg::Two", "pkg::One"),
                fact.terms().stream().map(
                        Regex.DebugDeferredPropertyFact::displayName).toList());
        assertFalse(fact.terms().get(0).tokenNegated());
        assertTrue(fact.terms().get(1).tokenNegated());
        assertThrows(UnsupportedOperationException.class,
                () -> fact.terms().add(fact.terms().get(0)));
    }

    @Test
    public void descriptionDoesNotResolveAndMatchingStillUsesTheMatcherService() {
        Regex regex = compile("[\\p{Lazy}]");
        AtomicInteger calls = new AtomicInteger();
        CharacterPropertyResolver.DeferredResolver resolver =
                (name, context, option, position, encoding) -> {
                    calls.incrementAndGet();
                    return new CharacterPropertyResolver.Result(
                            new int[] {1, 'A', 'A'}, false);
                };

        assertEquals("ANYOF[+pkg::Lazy]",
                regex.perlFirstProgramDebugDescription());
        assertEquals(0, calls.get());
        byte[] input = bytes("A");
        Matcher matcher = regex.matcher(input);
        matcher.setDeferredPropertyResolver(resolver);
        assertEquals(0, matcher.search(0, input.length, Option.NONE));
        assertEquals(1, calls.get());
    }

    @Test
    public void nonLeadingDeferredClassUsesTheNativeFallback() {
        Regex regex = compile("A[\\p{Later}]");
        assertTrue(regex.firstDeferredCharacterClassFact().isEmpty());
        assertEquals("", regex.perlFirstProgramDebugDescription());
    }

    private static void assertDescription(String pattern, String expected) {
        assertEquals(expected, compile(pattern).perlFirstProgramDebugDescription());
    }

    private static Regex compile(String pattern) {
        byte[] bytes = bytes(pattern);
        return new Regex(bytes, 0, bytes.length, Option.CAPTURE_GROUP,
                UTF8Encoding.INSTANCE, SYNTAX);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
