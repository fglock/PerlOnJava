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
import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.jcodings.Encoding;
import org.jcodings.specific.UTF8Encoding;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.joni.WideScalarCodec;
import org.junit.Test;

public class TestRegexExactProgramRendering {
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
            "RegexExactProgramRendering", Syntax.PerlNG.op,
            Syntax.PerlNG.op2, Syntax.PerlNG.op3,
            Syntax.PerlNG.behavior, Syntax.PerlNG.options,
            Syntax.PerlNG.metaCharTable, null, null, CODEC);

    @Test
    public void exposesImmutableCompiledPayloadAndOpcodeFacts() {
        Regex.DebugProgramFact fact = compile("(?i)[\\x{100}]", Option.NONE)
                .firstCompiledProgramFact();
        assertEquals(Regex.DebugProgramKind.EXACT, fact.kind());
        assertEquals(List.of(0xc4, 0x81), fact.exact().bytes());
        assertEquals(List.of(0x101L), fact.exact().codePoints());
        assertEquals(true, fact.exact().ignoreCaseOpcode());
        assertEquals(false, fact.exact().singleByteFoldOpcode());
        assertThrows(UnsupportedOperationException.class,
                () -> fact.exact().bytes().clear());
    }

    @Test
    public void rendersCompiledExactAndFoldModes() {
        assertDescription("[\\x{100}]", Option.NONE,
                "EXACT_REQ8 <\\x{100}>");
        assertDescription("(?i)[\\x{100}]", Option.NONE,
                "EXACTFU_REQ8 <\\x{101}>");
        assertDescription("(?il)[\\x{100}]", Option.NONE,
                "EXACTFLU8 <\\x{101}>");
        assertDescription("(?il)[\\x{212A}]", Option.NONE,
                "EXACTFL <\\x{212a}>");
        assertDescription("(?iaa)[\\x{1E9E}]", Option.NONE,
                "EXACTFAA <\\x{17f}\\x{17f}>");
        assertDescription("(?i)[\\x{1E9E}]", Option.NONE,
                "EXACTFU <ss>");
        assertDescription("[\\x{7fffffffffffffff}]", Option.NONE,
                "EXACT_REQ8 <\\x{7fffffffffffffff}>");
        assertDescription("(?i)[\\x{345}\\x{399}\\x{3B9}\\x{1FBE}]",
                Option.NONE, "EXACTFU_REQ8 <\\x{3b9}>");
        assertDescription("(?i)[\\x{2bc}]", Option.NONE,
                "EXACTFU_REQ8 <\\x{2bc}>");
        assertDescription("(?i)[\\x{2029}]", Option.NONE,
                "EXACT_REQ8 <\\x{2029}>");
        assertDescription("(?il)[\\x{2029}]", Option.NONE,
                "EXACTL <\\x{2029}>");
        assertDescription("(?i)[s][s]", Option.PERL_BYTE_PATTERN,
                "EXACTF <ss>");
        assertDescription("(?iu)[s][s]", Option.PERL_BYTE_PATTERN,
                "EXACTFUP <ss>");
        assertDescription("(?ia)[s][s]", Option.PERL_BYTE_PATTERN,
                "EXACTFUP <ss>");
    }

    @Test
    public void joinsOnlyCompiledExactControlFlow() {
        assertDescription("[a]\\x{100}", Option.NONE,
                "EXACT_REQ8 <a\\x{100}>");
        assertDescription("(?i)[b]st[s]st", Option.NONE,
                "EXACTF <bstsst>");
        assertDescription("a[bc]d", Option.NONE, "EXACT <a>");
    }

    @Test
    public void leavesClassFocusedAccessorsBackwardCompatible() {
        Regex regex = compile("[\\x{100}]", Option.NONE);
        assertEquals(Regex.DebugProgramKind.OTHER,
                regex.firstDebugProgramFact().kind());
        assertEquals("", regex.perlFirstProgramDebugDescription());
    }

    private static void assertDescription(String pattern, int option,
            String expected) {
        assertEquals(expected, compile(pattern, option)
                .perlFirstProgramDebugDescription(true));
    }

    @Test
    public void enumeratesLongByteExactSegmentsInControlFlowOrder() {
        Regex regex = compile("0123456789012345678", Option.NONE);
        Regex.DebugExactProgram program = regex.compiledExactProgram(5, 8)
                .orElseThrow();
        assertEquals(3, program.segments().size());
        assertEquals(List.of(8, 8, 3), program.segments().stream()
                .map(Regex.DebugExactProgramSegment::byteLength).toList());
        assertEquals(List.of(0, 8, 16), program.segments().stream()
                .map(Regex.DebugExactProgramSegment::programByteOffset)
                .toList());
        assertEquals(List.of(true, true, false), program.segments().stream()
                .map(Regex.DebugExactProgramSegment::longForm).toList());
        assertEquals("LEXACT\nLEXACT\nEXACT\nEND",
                regex.perlExactProgramDebugDescription(5, 8));
        byte[] input = "0123456789012345678".getBytes(StandardCharsets.UTF_8);
        assertEquals(0, regex.matcher(input).search(0, input.length,
                Option.NONE));
    }

    @Test
    public void enumeratesWideRequirementAcrossNativeExactInstructions() {
        Regex regex = compile("aaaaaa\u0100aaaaaaaaaaa", Option.NONE);
        Regex.DebugExactProgram program = regex.compiledExactProgram(5, 8)
                .orElseThrow();
        assertEquals(List.of(8, 8, 3), program.segments().stream()
                .map(Regex.DebugExactProgramSegment::byteLength).toList());
        assertEquals(List.of(true, false, false), program.segments().stream()
                .map(Regex.DebugExactProgramSegment::requiresUtf8Target)
                .toList());
        assertEquals("LEXACT_REQ8\nLEXACT\nEXACT\nEND",
                regex.perlExactProgramDebugDescription(5, 8));
        byte[] input = "aaaaaa\u0100aaaaaaaaaaa"
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(0, regex.matcher(input).search(0, input.length,
                Option.NONE));
    }

    private static Regex compile(String pattern, int option) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        return new Regex(bytes, 0, bytes.length,
                option | Option.CAPTURE_GROUP,
                UTF8Encoding.INSTANCE, SYNTAX);
    }
}
