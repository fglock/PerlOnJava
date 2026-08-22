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

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.jcodings.Encoding;
import org.jcodings.specific.UTF8Encoding;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.joni.WideScalarCodec;
import org.junit.Test;

public class TestRegexSemanticDebugFacts {
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
            "RegexSemanticDebugFacts", Syntax.PerlNG.op, Syntax.PerlNG.op2,
            Syntax.PerlNG.op3, Syntax.PerlNG.behavior, Syntax.PerlNG.options,
            Syntax.PerlNG.metaCharTable, null, null, CODEC);

    @Test
    public void classifiesSemanticUnicodeProgramShapesWithoutMutatingMatching() {
        Regex full = compile("[\\x{0}-\\x{7FFF_FFFF_FFFF_FFFF}]");
        assertStable(full, "A", true, "SANY",
                Regex.DebugProgramKind.FULL_CLASS);
        assertStable(full, "\n", true, "SANY",
                Regex.DebugProgramKind.FULL_CLASS);
        assertStable(full, "Ā", true, "SANY",
                Regex.DebugProgramKind.FULL_CLASS);
        assertStable(full, new String(Character.toChars(0x10ffff)), true,
                "SANY", Regex.DebugProgramKind.FULL_CLASS);
        assertStable(full, marker(0x110000L), true, "SANY",
                Regex.DebugProgramKind.FULL_CLASS);

        Regex empty = compile("[^\\x{0}-\\x{7FFF_FFFF_FFFF_FFFF}]");
        assertStable(empty, "A", false, "OPFAIL",
                Regex.DebugProgramKind.EMPTY_CLASS);

        Regex exceptNewline = compile("[^\\n]");
        assertStable(exceptNewline, "A", true, "REG_ANY",
                Regex.DebugProgramKind.ALL_EXCEPT_NEWLINE_CLASS);
        assertStable(exceptNewline, "\n", false, "REG_ANY",
                Regex.DebugProgramKind.ALL_EXCEPT_NEWLINE_CLASS);
        assertStable(exceptNewline, marker(0x110000L), true, "REG_ANY",
                Regex.DebugProgramKind.ALL_EXCEPT_NEWLINE_CLASS);

        Regex ordinary = compile("[ab]");
        assertStable(ordinary, "a", true, "", Regex.DebugProgramKind.OTHER);
        assertStable(ordinary, "c", false, "", Regex.DebugProgramKind.OTHER);

        assertStable(compile("[\\x{0}-\\x{10ffff}]"), marker(0x110000L),
                false, "", Regex.DebugProgramKind.OTHER);
        assertStable(compile("[^\\x{0}-\\x{10ffff}]"), marker(0x110000L),
                true, "", Regex.DebugProgramKind.OTHER);
        assertStable(compile("[^\\n\\x{110000}]"), marker(0x110000L),
                false, "", Regex.DebugProgramKind.OTHER);
        assertStable(compile("[^\\x{100}]"), "Ā", false, "",
                Regex.DebugProgramKind.OTHER);
        assertStable(compile("[\\x{100}]"), "Ā", true, "",
                Regex.DebugProgramKind.OTHER);
        assertStable(compile("[[:alpha:][:^alpha:]]"), marker(0x110000L),
                false, "", Regex.DebugProgramKind.OTHER);
    }

    @Test
    public void crossesTheStaticOptionInstructionWrapperOnlyAtProgramStart() {
        Regex wrapped = compile(
                "(?aa:[\\x{0}-\\x{7FFF_FFFF_FFFF_FFFF}])");
        assertStable(wrapped, "A", true, "SANY",
                Regex.DebugProgramKind.FULL_CLASS);
    }

    private static Regex compile(String pattern) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        return new Regex(bytes, 0, bytes.length, Option.CAPTURE_GROUP,
                UTF8Encoding.INSTANCE, SYNTAX);
    }

    private static void assertStable(Regex regex, String input,
            boolean expectedMatch, String expectedDescription,
            Regex.DebugProgramKind expectedKind) {
        assertStable(regex, input.getBytes(StandardCharsets.UTF_8), expectedMatch,
                expectedDescription, expectedKind);
    }

    private static void assertStable(Regex regex, byte[] bytes,
            boolean expectedMatch, String expectedDescription,
            Regex.DebugProgramKind expectedKind) {
        assertEquals(expectedMatch, matches(regex, bytes));
        assertEquals(regex.byteCodeDebugDescription(), expectedKind,
                regex.firstDebugProgramFact().kind());
        assertEquals(expectedDescription, regex.perlFirstProgramDebugDescription());
        if (expectedMatch) {
            assertTrue(matches(regex, bytes));
        } else {
            assertFalse(matches(regex, bytes));
        }
    }

    private static boolean matches(Regex regex, byte[] input) {
        return regex.matcher(input).search(0, input.length, Option.NONE) >= 0;
    }

    private static byte[] marker(long value) {
        return ("~<" + Long.toHexString(value).toUpperCase(Locale.ROOT) + ">")
                .getBytes(StandardCharsets.US_ASCII);
    }
}
