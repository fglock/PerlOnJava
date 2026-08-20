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

import static org.joni.constants.SyntaxProperties.ALLOW_MULTIPLEX_DEFINITION_NAME_CALL;
import static org.joni.constants.SyntaxProperties.OP2_OPTION_PERL;
import static org.joni.constants.SyntaxProperties.OP2_OPTION_RUBY;
import static org.joni.constants.SyntaxProperties.OP_ESC_C_CONTROL;
import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.UTF8Encoding;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.junit.Test;

public class TestPerlExtendedEmptyPosixLeaf {
    private static final Syntax SYNTAX = new Syntax(
            "PerlExtendedEmptyPosixLeaf", Syntax.RUBY.op | OP_ESC_C_CONTROL,
            (Syntax.RUBY.op2 & ~OP2_OPTION_RUBY) | OP2_OPTION_PERL,
            Syntax.RUBY.op3,
            Syntax.RUBY.behavior | ALLOW_MULTIPLEX_DEFINITION_NAME_CALL,
            Syntax.RUBY.options & ~(Option.ASCII_RANGE
                    | Option.POSIX_BRACKET_ALL_RANGE | Option.WORD_BOUND_ALL_RANGE),
            Syntax.RUBY.metaCharTable);

    private static int search(String patternSource, String input) {
        byte[] pattern = patternSource.getBytes(StandardCharsets.UTF_8);
        byte[] subject = input.getBytes(StandardCharsets.UTF_8);
        Regex regex = new Regex(pattern, 0, pattern.length, Option.NONE,
                UTF8Encoding.INSTANCE, SYNTAX);
        return regex.matcher(subject).search(0, subject.length, Option.NONE);
    }

    @Test
    public void treatsEmptyPosixLookingLeafAsOrdinaryClass() {
        assertEquals(0, search("(?[ [:] ])", ":"));
        assertEquals(-1, search("(?[ [:] ])", "a"));
    }

    @Test
    public void ignoresParenthesizedCommentsBetweenOperands() {
        assertEquals(0, search("(?[ (?# comment) [a] ])", "a"));
        assertEquals(-1, search("(?[ (?# comment) [a] ])", "b"));
    }

    @Test
    public void acceptsControlEscapesAsSingleOperands() {
        assertEquals(0, search("(?[\\c#])", "c"));
        assertEquals(0, search("(?[\\c[])", "\u001b"));
        assertEquals(0, search("(?[\\c\\])", "\u001c"));
        assertEquals(0, search("(?[\\c]])", "\u001d"));
    }
}
