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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.UTF8Encoding;
import org.junit.Test;

public class TestOptimizationIntrospection {
    private static Regex compile(String source) {
        byte[] pattern = source.getBytes(StandardCharsets.UTF_8);
        return new Regex(pattern, 0, pattern.length, Option.NONE,
                UTF8Encoding.INSTANCE, Syntax.Perl);
    }

    @Test
    public void exposesSelectedExactSearchFacts() {
        Regex.OptimizationInfo exact = compile("abc").getOptimizationInfo();
        assertEquals(3, exact.minimumLength());
        assertEquals("abc", exact.exact());
        assertEquals(0, exact.minimumOffset());
        assertEquals(Integer.valueOf(0), exact.maximumOffset());
        assertTrue(exact.exactReachEnd());
        assertNotEquals("NONE", exact.searchAlgorithm());
        assertFalse(exact.characterMap());
        assertFalse(exact.hasCaptures());

        assertTrue(compile("a()bc").getOptimizationInfo().hasCaptures());

        Regex.OptimizationInfo floating = compile("x?abc").getOptimizationInfo();
        assertEquals(3, floating.minimumLength());
        assertEquals("abc", floating.exact());
        assertEquals(0, floating.minimumOffset());
        assertEquals(Integer.valueOf(1), floating.maximumOffset());
    }

    @Test
    public void reportsNoExactSearchForEmptyPattern() {
        Regex.OptimizationInfo empty = compile("").getOptimizationInfo();
        assertEquals(0, empty.minimumLength());
        assertNull(empty.exact());
        assertEquals("NONE", empty.searchAlgorithm());
    }
}
