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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.jcodings.Encoding;
import org.jcodings.specific.UTF8Encoding;
import org.joni.CharacterPropertyResolver;
import org.joni.Matcher;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.junit.Test;

public class TestDeferredCharacterPropertyFacts {
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
                    return Result.deferred(bytes("pkg::" + raw));
                }
            };

    private static final Syntax SYNTAX = new Syntax(
            "DeferredCharacterPropertyFacts", Syntax.PerlNG.op,
            Syntax.PerlNG.op2, Syntax.PerlNG.op3, Syntax.PerlNG.behavior,
            Syntax.PerlNG.options, Syntax.PerlNG.metaCharTable, null,
            PARSER_RESOLVER);

    @Test
    public void enumeratesImmutableFactsInParserOrder() {
        String pattern = "\\p{First}(?i:[\\P{Second}])\\p{Third}";
        Regex regex = compile(pattern);
        List<CharacterPropertyResolver.DeferredProperty> facts =
                regex.deferredCharacterProperties();

        assertEquals(3, facts.size());
        assertFact(facts.get(0), "First", "pkg::First",
                CharacterPropertyResolver.Context.OUTSIDE_CHARACTER_CLASS,
                false, pattern.indexOf('}') + 1, false);
        int secondClose = pattern.indexOf('}', pattern.indexOf("Second"));
        assertFact(facts.get(1), "Second", "pkg::Second",
                CharacterPropertyResolver.Context.STANDARD_CHARACTER_CLASS,
                true, secondClose + 1, true);
        int thirdClose = pattern.indexOf('}', pattern.indexOf("Third"));
        assertFact(facts.get(2), "Third", "pkg::Third",
                CharacterPropertyResolver.Context.OUTSIDE_CHARACTER_CLASS,
                false, thirdClose + 1, false);

        byte[] raw = facts.get(0).name();
        byte[] display = facts.get(0).displayName();
        raw[0] = 'X';
        display[0] = 'X';
        assertArrayEquals(bytes("First"), facts.get(0).name());
        assertArrayEquals(bytes("pkg::First"), facts.get(0).displayName());
        assertThrows(UnsupportedOperationException.class,
                () -> facts.add(facts.get(0)));
    }

    @Test
    public void negatedOutsideTokenUsesTheSharedDescriptorOnce() {
        Regex regex = compile("^\\P{Denied}$");
        assertEquals(1, regex.deferredCharacterProperties().size());
        assertTrue(regex.deferredCharacterProperties().get(0).negated());
        byte[] a = bytes("A");
        byte[] b = bytes("B");
        Matcher aMatcher = regex.matcher(a);
        Matcher bMatcher = regex.matcher(b);
        CharacterPropertyResolver.DeferredResolver resolver =
                (name, context, option, position, encoding) ->
                        new CharacterPropertyResolver.Result(
                                new int[] {1, 'A', 'A'}, false);
        aMatcher.setDeferredPropertyResolver(resolver);
        bMatcher.setDeferredPropertyResolver(resolver);
        assertEquals(-1, aMatcher.search(0, a.length, Option.NONE));
        assertEquals(0, bMatcher.search(0, b.length, Option.NONE));
    }

    @Test
    public void recordsOnlyAcceptedPropertyTokens() {
        assertTrue(compile("\\p{Real}").hasCharacterProperty());
        assertTrue(compile("[\\P{Real}]").hasCharacterProperty());
        assertFalse(compile("\\Q\\p{Quoted}\\E").hasCharacterProperty());
        assertFalse(compile("\\\\p\\{Escaped\\}").hasCharacterProperty());
        assertFalse(compile("(?# \\p{Commented})A").hasCharacterProperty());
    }

    private static void assertFact(
            CharacterPropertyResolver.DeferredProperty fact,
            String raw, String display,
            CharacterPropertyResolver.Context context,
            boolean ignoreCase, int position, boolean negated) {
        assertArrayEquals(bytes(raw), fact.name());
        assertArrayEquals(bytes(display), fact.displayName());
        assertEquals(context, fact.context());
        assertEquals(ignoreCase, Option.isIgnoreCase(fact.option()));
        assertEquals(position, fact.position());
        assertEquals(negated, fact.negated());
    }

    private static Regex compile(String pattern) {
        byte[] bytes = bytes(pattern);
        return new Regex(bytes, 0, bytes.length, Option.NONE,
                UTF8Encoding.INSTANCE, SYNTAX);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
