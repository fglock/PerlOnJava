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

import static org.joni.constants.SyntaxProperties.OP2_OPTION_PERL;
import static org.joni.constants.SyntaxProperties.OP2_OPTION_RUBY;
import static org.joni.constants.SyntaxProperties.OP3_PERL_LITERAL_OPEN_IN_CC;
import static org.joni.constants.SyntaxProperties.OP_POSIX_BRACKET;
import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.ASCIIEncoding;
import org.joni.Matcher;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.junit.Test;

public class TestPerlAdapterRetirement {
    private static final Syntax PERL_LITERAL_CLASS = new Syntax(
            "PerlLiteralClass", Syntax.RUBY.op | OP_POSIX_BRACKET,
            (Syntax.RUBY.op2 & ~OP2_OPTION_RUBY) | OP2_OPTION_PERL,
            Syntax.RUBY.op3 | OP3_PERL_LITERAL_OPEN_IN_CC,
            Syntax.RUBY.behavior, Syntax.RUBY.options,
            Syntax.RUBY.metaCharTable);

    private static Matcher matcher(String pattern, String input, int options) {
        return matcher(pattern, input, options, PERL_LITERAL_CLASS);
    }

    private static Matcher matcher(String pattern, String input, int options, Syntax syntax) {
        byte[] patternBytes = pattern.getBytes(StandardCharsets.US_ASCII);
        byte[] inputBytes = input.getBytes(StandardCharsets.US_ASCII);
        Regex regex = new Regex(patternBytes, 0, patternBytes.length, options,
                ASCIIEncoding.INSTANCE, syntax);
        return regex.matcher(inputBytes);
    }

    private static int search(String pattern, String input, int options) {
        return matcher(pattern, input, options).search(0, input.length(), Option.NONE);
    }

    @Test
    public void abbreviatedMarkPublishesItsName() {
        Matcher matcher = matcher("(*:seen)a", "a", Option.NONE);
        assertEquals(0, matcher.search(0, 1, Option.NONE));
        assertEquals("seen", matcher.getControlMark());
    }

    @Test
    public void ordinaryPerlClassTreatsNestedOpenBracketAsLiteral() {
        assertEquals(0, search("[a[]", "[", Option.NONE));
        assertEquals(0, search("[a[]", "a", Option.NONE));
        assertEquals(0, search("[[:]+", "[", Option.NONE));
        assertEquals(0, search("[a[:]b[:c]", "abc", Option.NONE));
        assertEquals(0, search("[[:alpha:]]", "a", Option.NONE));
        assertEquals(0, search("[[:dgit:foo]]", "d]", Option.NONE));
        assertEquals(-1, search("[[:dgit:foo]]", "a]", Option.NONE));
    }

    @Test
    public void rubyClassSetSemanticsRemainAvailable() {
        assertEquals(0, matcher("[[a]b]", "a", Option.NONE, Syntax.RUBY)
                .search(0, 1, Option.NONE));
        assertEquals(0, matcher("[[a]b]", "b", Option.NONE, Syntax.RUBY)
                .search(0, 1, Option.NONE));
    }

    @Test
    public void extendMoreClassWhitespaceIsNativeAndScoped() {
        int xx = Option.EXTEND | Option.PERL_EXTEND_MORE;
        assertEquals(-1, search("[a b]", " ", xx));
        assertEquals(0, search("[a\\ b]", " ", xx));
        assertEquals(-1, search("(?xx:[a b])", " ", Option.NONE));
        assertEquals(0, search("(?x:[a b])", " ", Option.NONE));
        assertEquals(0, search("(?-x:[a b])", " ", xx));
    }

    @Test
    public void emptyPerlGroupIsZeroWidth() {
        assertEquals(0, search("(?)a", "a", Option.NONE));
    }
}
