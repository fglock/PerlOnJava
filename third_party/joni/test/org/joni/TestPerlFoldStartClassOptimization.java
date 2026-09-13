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
package org.joni;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.UTF8Encoding;
import org.junit.Test;

public class TestPerlFoldStartClassOptimization {
    private static Regex compile(String pattern) {
        byte[] source = pattern.getBytes(StandardCharsets.UTF_8);
        return new Regex(source, 0, source.length, Option.IGNORECASE,
                UTF8Encoding.INSTANCE, Syntax.Perl);
    }

    private static void assertMapSearchBothDirections(String pattern,
            String input, int expected) {
        Regex regex = compile(pattern);
        assertTrue(regex.getOptimizationInfo().characterMap());
        byte[] target = input.getBytes(StandardCharsets.UTF_8);
        assertEquals(expected,
                regex.matcher(target).search(0, target.length, Option.NONE));
        assertEquals(expected,
                regex.matcher(target).search(target.length, 0, Option.NONE));
    }

    @Test
    public void pinnedSimpleFoldPartitionsRetainStartClassSearch() {
        String prefix = "x".repeat(4096);
        assertMapSearchBothDirections("k", prefix + "\u212a", 4096);
        assertMapSearchBothDirections("[k]", prefix + "\u212a", 4096);
        assertMapSearchBothDirections("\u00e5", prefix + "\u212b", 4096);
        assertMapSearchBothDirections("[\u00e5]", prefix + "\u212b", 4096);
        assertMapSearchBothDirections("\u2c65", prefix + "\u023a", 4096);
        assertMapSearchBothDirections("[\u2c65]", prefix + "\u023a", 4096);
    }

    @Test
    public void nonSingletonAndNegatedClassesRemainConservative() {
        assertFalse(compile("[\u0100-\u0200]")
                .getOptimizationInfo().characterMap());
        assertFalse(compile("[^\u0100]")
                .getOptimizationInfo().characterMap());
    }
}
