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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.UTF8Encoding;
import org.junit.Test;

public class TestPerlSyntheticStartClassIntrospection {
    private static Regex compile(String source, int option) {
        byte[] pattern = source.getBytes(StandardCharsets.UTF_8);
        return new Regex(pattern, 0, pattern.length, option,
                UTF8Encoding.INSTANCE, Syntax.Perl);
    }

    @Test
    public void reportsRetainedStartMapBesideFloatingExact() {
        assertTrue(compile("a?c", Option.NONE).hasSyntheticStartClass());
        assertTrue(compile("a?c", Option.IGNORECASE).hasSyntheticStartClass());
        assertTrue(compile("[ab]?c", Option.NONE).hasSyntheticStartClass());
        assertTrue(compile("\\R?c", Option.NONE).hasSyntheticStartClass());
        assertTrue(compile("\\d?c", Option.NONE).hasSyntheticStartClass());
        assertTrue(compile("\\w?c", Option.NONE).hasSyntheticStartClass());
        assertTrue(compile("\\s?c", Option.NONE).hasSyntheticStartClass());
        assertTrue(compile("[[:lower:]]?c", Option.NONE).hasSyntheticStartClass());
        assertTrue(compile("x*abc", Option.NONE).hasSyntheticStartClass());
        assertTrue(compile("a{0,2}c", Option.NONE).hasSyntheticStartClass());
    }

    @Test
    public void doesNotInventStartMapWithoutTheRetainedOptimizerFact() {
        assertFalse(compile("abc", Option.NONE).hasSyntheticStartClass());
        assertFalse(compile("x+abc", Option.NONE).hasSyntheticStartClass());
        assertFalse(compile("(?:x|)abc", Option.NONE).hasSyntheticStartClass());
        assertFalse(compile("a?c|z", Option.NONE).hasSyntheticStartClass());
    }
}
