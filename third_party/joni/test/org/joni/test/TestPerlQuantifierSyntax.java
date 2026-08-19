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

import static org.joni.exception.ErrorMessages.PERL_INVALID_QUANTIFIER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.UTF8Encoding;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.joni.WarnCallback;
import org.joni.exception.SyntaxException;
import org.junit.Test;

public class TestPerlQuantifierSyntax {
    private static void compile(String pattern) {
        compile(pattern, Syntax.PerlNG);
    }

    private static void compile(String pattern, Syntax syntax) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        new Regex(bytes, 0, bytes.length, Option.NONE, UTF8Encoding.INSTANCE,
                syntax, WarnCallback.NONE);
    }

    private static void assertInvalid(String pattern, int bytePosition) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        try {
            new Regex(bytes, 0, bytes.length, Option.NONE, UTF8Encoding.INSTANCE,
                    Syntax.PerlNG, WarnCallback.NONE);
            fail("expected invalid Perl quantifier for " + pattern);
        } catch (SyntaxException error) {
            assertEquals(PERL_INVALID_QUANTIFIER, error.getMessage());
            assertEquals(bytePosition, error.getPatternPosition());
        }
    }

    @Test
    public void rejectsLeadingZeroInLowerOrUpperBound() {
        assertInvalid("x{01,2}", 4);
        assertInvalid("x{1,02}", 6);
        assertInvalid("ネ{01}ネ", 6);
        assertInvalid("ネ{1,02}ネ", 8);
    }

    @Test
    public void retainsCanonicalZeroAndMultiDigitBounds() {
        compile("x{0}");
        compile("x{0,2}");
        compile("x{1,20}");
    }

    @Test
    public void leavesStockRubySyntaxUnchanged() {
        compile("x{01,2}", Syntax.RUBY);
        compile("x{1,02}", Syntax.RUBY);
    }
}
