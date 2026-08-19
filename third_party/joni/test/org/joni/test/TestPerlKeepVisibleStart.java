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
import static org.joni.constants.SyntaxProperties.OP2_OPTION_PERL;
import static org.joni.constants.SyntaxProperties.OP2_OPTION_RUBY;

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.UTF8Encoding;
import org.joni.Matcher;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.junit.Test;

public class TestPerlKeepVisibleStart {
    private static final Syntax PERL_SYNTAX = new Syntax(
            "PerlKeepVisibleStart", Syntax.RUBY.op,
            (Syntax.RUBY.op2 & ~OP2_OPTION_RUBY) | OP2_OPTION_PERL,
            Syntax.RUBY.op3, Syntax.RUBY.behavior, Syntax.RUBY.options,
            Syntax.RUBY.metaCharTable);

    @Test
    public void searchResultRetainsConsumedStartBeforeVisibleKeepStart() {
        byte[] pattern = "(.)\\K".getBytes(StandardCharsets.UTF_8);
        byte[] input = "abc".getBytes(StandardCharsets.UTF_8);
        Regex regex = new Regex(pattern, 0, pattern.length, Option.NONE,
                UTF8Encoding.INSTANCE, PERL_SYNTAX);

        Matcher matcher = regex.matcher(input);
        assertEquals(0, matcher.search(0, input.length, Option.NONE));
        assertEquals(1, matcher.getBegin());
        assertEquals(1, matcher.getEnd());

        matcher = regex.matcher(input);
        assertEquals(1, matcher.search(1, input.length, Option.NONE));
        assertEquals(2, matcher.getBegin());
        assertEquals(2, matcher.getEnd());
    }
}
