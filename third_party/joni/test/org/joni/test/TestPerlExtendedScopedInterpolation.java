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
import org.joni.exception.JOniException;
import org.junit.Test;

public class TestPerlExtendedScopedInterpolation {
    private static Regex compile(String pattern) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        return new Regex(bytes, 0, bytes.length, Option.NONE,
                UTF8Encoding.INSTANCE, Syntax.PerlNG, WarnCallback.NONE);
    }

    private static int search(String pattern, String input) {
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        return compile(pattern).matcher(bytes).search(0, bytes.length, Option.NONE);
    }

    private static void assertMatches(String pattern, String yes, String no) {
        assertEquals(pattern + " should match " + yes, 0, search(pattern, yes));
        assertEquals(pattern + " should not match " + no, -1, search(pattern, no));
    }

    private static void assertSyntaxError(String pattern, String fragment) {
        try {
            compile(pattern);
            fail("expected syntax error for " + pattern);
        } catch (JOniException error) {
            if (!error.getMessage().contains(fragment)) {
                fail("expected '" + fragment + "' in '" + error.getMessage() + "'");
            }
        }
    }

    @Test
    public void acceptsRecursivelyScopedExtendedClassLeaves() {
        assertMatches("(?[ (?^:(?[ [x] ])) ])", "x", "y");
        assertMatches("(?[ (?^:(?x:(?[ [x] ]))) ])", "x", "y");
        assertMatches("(?[ (?^:(?x:(?i:(?[ [x] ])))) ])", "X", "y");
    }

    @Test
    public void restoresOptionsAfterNestedScopedInterpolation() {
        assertMatches("(?[ (?^:(?i:(?[ [a] ]))) - [A] ])", "a", "A");
    }

    @Test
    public void stillRequiresAnExtendedClassLeaf() {
        String expected = "Expecting interpolated extended charclass";
        assertSyntaxError("(?[ (?^:(?x:[x])) ])", expected);
        assertSyntaxError("(?[ (?^:(?x:([x]))) ])", expected);
        assertSyntaxError("(?[ (?^:(?x:(?i:[x]))) ])", expected);
    }
}
