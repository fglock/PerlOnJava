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
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.UTF8Encoding;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.joni.WarnCallback;
import org.joni.exception.SyntaxException;
import org.junit.Test;

public class TestPerlBackrefDiagnostics {
    private static void compile(String pattern, Syntax syntax) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        new Regex(bytes, 0, bytes.length, Option.NONE, UTF8Encoding.INSTANCE,
                syntax, WarnCallback.NONE);
    }

    private static void assertInvalid(String pattern, String message, int bytePosition) {
        try {
            compile(pattern, Syntax.PerlNG);
            fail("expected invalid Perl backreference for " + pattern);
        } catch (SyntaxException error) {
            assertEquals(message, error.getMessage());
            assertEquals(bytePosition, error.getPatternPosition());
        }
    }

    @Test
    public void reportsUnterminatedAndMalformedBackreferences() {
        assertInvalid("\\g", "Unterminated \\g... pattern", 2);
        assertInvalid("\\g{", "Sequence \\g{... not terminated", 3);
        assertInvalid("\\g{1", "Unterminated \\g{...} pattern", 4);
        assertInvalid("\\g{-abc}",
                "Group name must start with a non-digit word character", 4);
    }

    @Test
    public void retainsValidNumericAndRelativeBackreferences() {
        compile("(x)\\g{1}", Syntax.PerlNG);
        compile("(x)\\g{-1}", Syntax.PerlNG);
    }

    @Test
    public void leavesStockRubySyntaxUnchanged() {
        compile("\\g", Syntax.RUBY);
    }
}
