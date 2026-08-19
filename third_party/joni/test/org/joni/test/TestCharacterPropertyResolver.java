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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.UTF8Encoding;
import org.joni.CharacterPropertyResolver;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.junit.Test;

public class TestCharacterPropertyResolver {
    private static final CharacterPropertyResolver RESOLVER =
            (bytes, p, end, encoding, inCharacterClass) -> {
                String name = new String(bytes, p, end - p, StandardCharsets.UTF_8);
                return switch (name) {
                    case "Fake" -> new CharacterPropertyResolver.Result(
                            new int[] {3, 'A', 'A', 0xdf, 0xdf, 0x1f642, 0x1f642}, true);
                    case "FakeNoFold" -> new CharacterPropertyResolver.Result(
                            new int[] {2, 'A', 'A', 0xdf, 0xdf}, false);
                    default -> null;
                };
            };

    private static Syntax syntax(CharacterPropertyResolver resolver) {
        return new Syntax("CharacterPropertyResolver", Syntax.PerlNG.op,
                Syntax.PerlNG.op2 | OP2_CCLASS_SET_OP, Syntax.PerlNG.op3,
                Syntax.PerlNG.behavior,
                Syntax.PerlNG.options, Syntax.PerlNG.metaCharTable, null, resolver);
    }

    private static Regex compile(String pattern, CharacterPropertyResolver resolver) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        return new Regex(bytes, 0, bytes.length, Option.NONE,
                UTF8Encoding.INSTANCE, syntax(resolver));
    }

    private static int search(String pattern, String input) {
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        return compile(pattern, RESOLVER).matcher(bytes)
                .search(0, bytes.length, Option.NONE);
    }

    @Test
    public void resolvesRangesInsideAndOutsideCharacterClasses() {
        assertEquals(0, search("\\p{Fake}", "A"));
        assertEquals(0, search("[\\p{Fake}]", "\ud83d\ude42"));
        assertEquals(-1, search("\\p{Fake}", "B"));
        assertEquals(0, search("\\P{Fake}", "B"));
        assertEquals(-1, search("[\\P{Fake}]", "A"));
        assertEquals(0, search("(?i)\\p{Fake}", "a"));
        assertEquals(-1, search("(?i)\\P{Fake}", "A"));
        assertEquals(0, search("(?i)\\p{FakeNoFold}", "A"));
        assertEquals(-1, search("(?i)\\p{FakeNoFold}", "a"));
    }

    @Test
    public void preservesFoldPolicyInsidePositiveAndNegativeClasses() {
        assertEquals(0, search("(?i)[\\p{Fake}]", "a"));
        assertEquals(0, search("(?i)[\\p{FakeNoFold}]", "A"));
        assertEquals(-1, search("(?i)[\\p{FakeNoFold}]", "a"));
        assertEquals(-1, search("(?i)[\\P{FakeNoFold}]", "A"));
        assertEquals(0, search("(?i)[\\P{FakeNoFold}]", "a"));
        assertEquals(-1, search("(?i)[^\\p{FakeNoFold}]", "A"));
        assertEquals(0, search("(?i)[^\\p{FakeNoFold}]", "a"));
    }

    @Test
    public void composesFoldPolicyThroughUnionsIntersectionsAndNestedClasses() {
        assertEquals(0, search("(?i)[\\p{FakeNoFold}\\p{Fake}]", "a"));
        assertEquals(0, search("(?i)[\\p{Fake}&&\\p{FakeNoFold}]", "A"));
        assertEquals(-1, search("(?i)[\\p{Fake}&&\\p{FakeNoFold}]", "a"));
        assertEquals(0, search("(?i)[[\\p{FakeNoFold}]B]", "A"));
        assertEquals(-1, search("(?i)[[\\p{FakeNoFold}]B]", "a"));
        assertEquals(0, search("(?i)[[\\p{FakeNoFold}]B]", "b"));
    }

    @Test
    public void foldsOnlyEligibleMembersOfMixedClasses() {
        assertEquals(0, search("(?i)[B\\p{FakeNoFold}]", "A"));
        assertEquals(-1, search("(?i)[B\\p{FakeNoFold}]", "a"));
        assertEquals(0, search("(?i)[B\\p{FakeNoFold}]", "b"));
        assertEquals(0, search("(?i)[\\p{Fake}]", "ss"));
        assertEquals(-1, search("(?i)[\\p{FakeNoFold}]", "ss"));
    }

    @Test
    public void fallsBackToEncodingProperties() {
        assertEquals(0, search("\\p{Digit}", "7"));
        assertEquals(-1, search("\\p{Digit}", "A"));
    }

    @Test
    public void preservesResolverExceptions() {
        IllegalArgumentException expected = new IllegalArgumentException("failure");
        try {
            compile("\\p{Fake}", (bytes, p, end, encoding, inCharacterClass) -> {
                throw expected;
            });
            fail("expected resolver exception");
        } catch (IllegalArgumentException error) {
            assertSame(expected, error);
        }
    }

    @Test
    public void rejectsMalformedRangeResults() {
        try {
            compile("\\p{Fake}", (bytes, p, end, encoding, inCharacterClass) ->
                    new CharacterPropertyResolver.Result(new int[] {1, 2}, true));
            fail("expected invalid range result");
        } catch (IllegalArgumentException error) {
            assertEquals("invalid character property ranges", error.getMessage());
        }
    }
}
