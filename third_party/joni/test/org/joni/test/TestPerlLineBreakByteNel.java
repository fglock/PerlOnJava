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
import static org.joni.constants.SyntaxProperties.ALLOW_MULTIPLEX_DEFINITION_NAME_CALL;
import static org.joni.constants.SyntaxProperties.OP2_ESC_H_HORIZONTAL_WHITESPACE;
import static org.joni.constants.SyntaxProperties.OP2_ESC_V_VERTICAL_WHITESPACE;
import static org.joni.constants.SyntaxProperties.OP2_OPTION_PERL;
import static org.joni.constants.SyntaxProperties.OP2_OPTION_RUBY;
import static org.joni.constants.SyntaxProperties.OP2_PLUS_POSSESSIVE_INTERVAL;
import static org.joni.constants.SyntaxProperties.OP3_PERL_LITERAL_OPEN_IN_CC;
import static org.joni.constants.SyntaxProperties.OP_ESC_C_CONTROL;
import static org.joni.constants.SyntaxProperties.OP_POSIX_BRACKET;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.jcodings.Encoding;
import org.jcodings.specific.ISO8859_1Encoding;
import org.jcodings.specific.UTF8Encoding;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.joni.WarnCallback;
import org.junit.Test;

public class TestPerlLineBreakByteNel {
    private static final Syntax SYNTAX = new Syntax(
            "PerlLineBreakByteNel", Syntax.RUBY.op | OP_POSIX_BRACKET
                    | OP_ESC_C_CONTROL,
            (Syntax.RUBY.op2 & ~OP2_OPTION_RUBY) | OP2_OPTION_PERL
                    | OP2_PLUS_POSSESSIVE_INTERVAL
                    | OP2_ESC_H_HORIZONTAL_WHITESPACE
                    | OP2_ESC_V_VERTICAL_WHITESPACE,
            Syntax.RUBY.op3 | OP3_PERL_LITERAL_OPEN_IN_CC,
            Syntax.RUBY.behavior | ALLOW_MULTIPLEX_DEFINITION_NAME_CALL,
            Syntax.RUBY.options & ~(Option.ASCII_RANGE
                    | Option.POSIX_BRACKET_ALL_RANGE
                    | Option.WORD_BOUND_ALL_RANGE),
            Syntax.RUBY.metaCharTable, null);

    private static int search(String pattern, byte[] input, Encoding encoding,
                              Charset charset, int options) {
        byte[] patternBytes = pattern.getBytes(charset);
        Regex regex = new Regex(patternBytes, 0, patternBytes.length, options,
                encoding, SYNTAX, WarnCallback.NONE);
        return regex.matcher(input).search(0, input.length, Option.NONE);
    }

    private static void assertByteMatch(String pattern, byte[] input) {
        assertEquals(0, search(pattern, input,
                ISO8859_1Encoding.INSTANCE, StandardCharsets.ISO_8859_1,
                Option.PERL_BYTE_PATTERN));
    }

    private static void assertByteNoMatch(String pattern, byte[] input) {
        assertEquals(-1, search(pattern, input,
                ISO8859_1Encoding.INSTANCE, StandardCharsets.ISO_8859_1,
                Option.PERL_BYTE_PATTERN));
    }

    private static void assertUtf8Match(String pattern, String input) {
        assertEquals(0, search(pattern,
                input.getBytes(StandardCharsets.UTF_8), UTF8Encoding.INSTANCE,
                StandardCharsets.UTF_8, Option.NONE));
    }

    @Test
    public void includesNelInSingleByteLineBreaks() {
        byte[] nel = {(byte) 0x85};
        assertByteMatch("\\R", nel);
        assertByteNoMatch("\\V", nel);
        assertByteMatch("\\V\\R", new byte[] {'o', (byte) 0x85});
        assertByteMatch("foo(\\R+)bar", new byte[] {'f', 'o', 'o', '\r', '\n',
                (byte) 0x85, '\r', '\n', '\n', 'b', 'a', 'r'});
    }

    @Test
    public void preservesUnicodeAndCrLfLineBreaks() {
        assertUtf8Match("\\R", "\u0085");
        assertUtf8Match("\\R", "\u2028");
        assertUtf8Match("\\R", "\u2029");
        assertByteMatch("\\R", new byte[] {'\r', '\n'});
        assertByteMatch("\\R", new byte[] {'\r'});
        assertByteMatch("\\R", new byte[] {'\n'});
    }
}
