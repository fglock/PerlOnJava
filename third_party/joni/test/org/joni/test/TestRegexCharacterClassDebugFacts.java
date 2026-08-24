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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import org.jcodings.Encoding;
import org.jcodings.specific.UTF8Encoding;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.joni.WideScalarCodec;
import org.junit.Test;

public class TestRegexCharacterClassDebugFacts {
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
            "RegexCharacterClassDebugFacts", Syntax.PerlNG.op, Syntax.PerlNG.op2,
            Syntax.PerlNG.op3, Syntax.PerlNG.behavior, Syntax.PerlNG.options,
            Syntax.PerlNG.metaCharTable, null, null, CODEC);

    @Test
    public void snapshotsCanonicalPositiveMembershipRanges() {
        Regex.DebugProgramFact fact = compile(
                "[a-c\\x{100}-\\x{102}\\x{110000}]")
                        .firstDebugProgramFact();

        assertEquals(Regex.DebugProgramKind.OTHER, fact.kind());
        assertEquals(false, fact.characterClass().storageNegated());
        assertEquals(List.of(
                new Regex.DebugRange('a', 'c'),
                new Regex.DebugRange(0x100, 0x102),
                new Regex.DebugRange(0x110000, 0x110000)),
                fact.characterClass().ranges());
        assertThrows(UnsupportedOperationException.class,
                () -> fact.characterClass().ranges().add(
                        new Regex.DebugRange(1, 1)));
        assertTrue(matches(regexFor(
                "[a-c\\x{100}-\\x{102}\\x{110000}]"), "b"));
        assertTrue(matches(regexFor(
                "[a-c\\x{100}-\\x{102}\\x{110000}]"), "Ā"));
        assertTrue(matches(regexFor(
                "[a-c\\x{100}-\\x{102}\\x{110000}]"), marker(0x110000)));
    }

    @Test
    public void snapshotsComplementAcrossUnicodeAndSignedWideDomains() {
        Regex regex = compile("[^a-c\\x{100}-\\x{102}\\x{110000}]");
        Regex.DebugProgramFact fact = regex.firstDebugProgramFact();

        assertEquals(Regex.DebugProgramKind.OTHER, fact.kind());
        assertTrue(fact.characterClass().storageNegated());
        assertEquals(List.of(
                new Regex.DebugRange(0, 'a' - 1),
                new Regex.DebugRange('c' + 1, 0xff),
                new Regex.DebugRange(0x103, 0x10ffff),
                new Regex.DebugRange(0x110001, Long.MAX_VALUE)),
                fact.characterClass().ranges());
        assertEquals(fact, regex.firstDebugProgramFact());
        assertEquals("", regex.perlFirstProgramDebugDescription());
        assertTrue(matches(regex, "d"));
        assertTrue(!matches(regex, "b"));
        assertTrue(!matches(regex, marker(0x110000)));
    }

    @Test
    public void joinsTheUtf8ByteAndUnicodeBoundariesStructurally() {
        assertWide("[\\x{7f}-\\x{101}]", false,
                List.of(new Regex.DebugRange(0x7f, 0x101)));
        assertWide("[^\\x{7f}-\\x{101}]", true,
                List.of(new Regex.DebugRange(0, 0x7e),
                        new Regex.DebugRange(0x102, Long.MAX_VALUE)));
    }

    @Test
    public void includesMembershipOnEverySemanticShapeFact() {
        Regex.DebugProgramFact full = compile(
                "[\\x{0}-\\x{7FFF_FFFF_FFFF_FFFF}]")
                        .firstDebugProgramFact();
        assertEquals(Regex.DebugProgramKind.FULL_CLASS, full.kind());
        assertEquals(List.of(new Regex.DebugRange(0, Long.MAX_VALUE)),
                full.characterClass().ranges());

        Regex.DebugProgramFact empty = compile(
                "[^\\x{0}-\\x{7FFF_FFFF_FFFF_FFFF}]")
                        .firstDebugProgramFact();
        assertEquals(Regex.DebugProgramKind.EMPTY_CLASS, empty.kind());
        assertEquals(List.of(), empty.characterClass().ranges());

        Regex.DebugProgramFact any = compile("[^\\n]").firstDebugProgramFact();
        assertEquals(Regex.DebugProgramKind.ALL_EXCEPT_NEWLINE_CLASS, any.kind());
        assertEquals(List.of(new Regex.DebugRange(0, 9),
                new Regex.DebugRange(11, Long.MAX_VALUE)),
                any.characterClass().ranges());

        assertWide("[^\\x{110000}-\\x{7FFF_FFFF_FFFF_FFFF}]", true,
                List.of(new Regex.DebugRange(0, 0x10ffff)));
        Regex.DebugProgramFact exact = compile("a").firstDebugProgramFact();
        assertEquals(Regex.DebugProgramKind.OTHER, exact.kind());
        assertEquals(null, exact.characterClass());
    }

    @Test
    public void decodesEveryOrdinaryCompiledClassOperandShape() {
        assertOrdinary("[a-c]", false,
                List.of(new Regex.DebugRange('a', 'c')));
        assertOrdinary("[^a-c]", true,
                List.of(new Regex.DebugRange(0, 'a' - 1),
                        new Regex.DebugRange('c' + 1, 0x7fffffffL)));
        assertOrdinary("[\\x{100}-\\x{102}]", false,
                List.of(new Regex.DebugRange(0x100, 0x102)));
        assertOrdinary("[^\\x{100}-\\x{102}]", true,
                List.of(new Regex.DebugRange(0, 0xff),
                        new Regex.DebugRange(0x103, 0x7fffffffL)));
        assertOrdinary("[a-c\\x{100}-\\x{102}]", false,
                List.of(new Regex.DebugRange('a', 'c'),
                        new Regex.DebugRange(0x100, 0x102)));
        assertOrdinary("[^a-c\\x{100}-\\x{102}]", true,
                List.of(new Regex.DebugRange(0, 'a' - 1),
                        new Regex.DebugRange('c' + 1, 0xff),
                        new Regex.DebugRange(0x103, 0x7fffffffL)));
    }

    private static void assertOrdinary(String pattern, boolean negated,
            List<Regex.DebugRange> ranges) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        Regex regex = new Regex(bytes, 0, bytes.length, Option.CAPTURE_GROUP,
                UTF8Encoding.INSTANCE, Syntax.PerlNG);
        Regex.DebugProgramFact fact = regex.firstDebugProgramFact();
        assertEquals(Regex.DebugProgramKind.OTHER, fact.kind());
        assertEquals(negated, fact.characterClass().storageNegated());
        assertEquals(ranges, fact.characterClass().ranges());
    }

    private static Regex compile(String pattern) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        return new Regex(bytes, 0, bytes.length, Option.CAPTURE_GROUP,
                UTF8Encoding.INSTANCE, SYNTAX);
    }

    private static Regex regexFor(String pattern) {
        return compile(pattern);
    }

    private static void assertWide(String pattern, boolean storageNegated,
            List<Regex.DebugRange> ranges) {
        Regex.DebugProgramFact fact = compile(pattern).firstDebugProgramFact();
        assertEquals(storageNegated, fact.characterClass().storageNegated());
        assertEquals(ranges, fact.characterClass().ranges());
    }

    private static boolean matches(Regex regex, String input) {
        return matches(regex, input.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean matches(Regex regex, byte[] input) {
        return regex.matcher(input).search(0, input.length, Option.NONE) >= 0;
    }

    private static byte[] marker(long value) {
        return ("~<" + Long.toHexString(value).toUpperCase(Locale.ROOT) + ">")
                .getBytes(StandardCharsets.US_ASCII);
    }
}
