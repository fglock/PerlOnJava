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

public class TestPerlPropertyDiagnostics {
    private static void compile(String pattern, Syntax syntax) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        new Regex(bytes, 0, bytes.length, Option.NONE, UTF8Encoding.INSTANCE,
                syntax, WarnCallback.NONE);
    }

    private static void assertInvalid(String pattern, String message, int bytePosition) {
        try {
            compile(pattern, Syntax.PerlNG);
            fail("expected invalid Perl property for " + pattern);
        } catch (SyntaxException error) {
            assertEquals(message, error.getMessage());
            assertEquals(bytePosition, error.getPatternPosition());
        }
    }

    @Test
    public void reportsBareAndEmptyPropertyEscapes() {
        assertInvalid("\\p", "Empty \\p", 2);
        assertInvalid("\\P", "Empty \\P", 2);
        assertInvalid("\\p{}", "Empty \\p{}", 3);
        assertInvalid("\\P{}", "Empty \\P{}", 3);
        assertInvalid("[\\p{}]", "Empty \\p{}", 4);
        assertInvalid("[\\P{}]", "Empty \\P{}", 4);
    }

    @Test
    public void retainsSingleLetterAndNamedProperties() {
        compile("\\pL", Syntax.PerlNG);
        compile("\\PL", Syntax.PerlNG);
        compile("\\p{Latin}", Syntax.PerlNG);
        compile("[\\P{Latin}]", Syntax.PerlNG);
    }

    @Test
    public void leavesStockRubySyntaxUnchanged() {
        compile("\\p", Syntax.RUBY);
    }
}
