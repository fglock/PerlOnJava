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
package org.joni;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.UTF8Encoding;
import org.junit.Test;

public class TestPerlMultiFoldOptimizerBoundary {
    private static Regex compile(String pattern) {
        return compile(pattern, Syntax.PerlNG);
    }

    private static Regex compile(String pattern, Syntax syntax) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        return new Regex(bytes, 0, bytes.length, Option.IGNORECASE,
                UTF8Encoding.INSTANCE, syntax);
    }

    private static int search(Regex regex, String input) {
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        return regex.matcher(bytes).search(0, bytes.length, Option.NONE);
    }

    @Test
    public void rejectsExactAndMapCandidatesAtNonFinalReverseFoldComponents() {
        for (String prefix : new String[] {"t", "ft", "ift", "sift"}) {
            String head = "b".repeat(120) + "\u00dc";
            String pattern = head + prefix + "enKalt";
            Regex regex = compile(pattern);

            assertNull("unsafe literal exact for " + prefix, regex.exact);
            assertNull("unsafe literal map for " + prefix, regex.map);
            assertEquals("matching remains independent of literal extraction for " + prefix,
                    0, search(regex, pattern));
        }
    }

    @Test
    public void retainsLiteralOptimizationForSafeOrdinaryBoundaries() {
        Regex regex = compile("qux");
        assertNotNull("safe ordinary literal retains exact search", regex.exact);
        assertEquals(0, search(regex, "qux"));
    }

    @Test
    public void doesNotApplyPerlBoundaryGuardToOtherSyntaxes() {
        Regex regex = compile("tenKalt", Syntax.RUBY);
        assertNotNull("non-Perl syntax retains its ordinary exact search", regex.exact);
        assertEquals(0, search(regex, "tenKalt"));
    }
}
