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
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.joni.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.ASCIIEncoding;
import org.joni.Matcher;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.junit.Test;

public class TestLiteralAlternationOptimization {
    @Test
    public void captureFreeByteAlternationSelectsAndPreservesBranchOrder() {
        Regex shortFirst = regex("a|ab", Option.NONE);
        assertTrue(shortFirst.hasLiteralAlternationOptimization());
        assertMatch(shortFirst, "ab", 0, 1);

        Regex longFirst = regex("ab|a", Option.NONE);
        assertTrue(longFirst.hasLiteralAlternationOptimization());
        assertMatch(longFirst, "ab", 0, 2);

        Regex portfolioShape = regex("42|gamma|epsilon", Option.NONE);
        assertTrue(portfolioShape.hasLiteralAlternationOptimization());
        assertMatch(portfolioShape, "alpha:gamma:42", 6, 11);
    }

    @Test
    public void capturesEmptyBranchesAndCaseFoldingUseTheOrdinaryMachine() {
        assertFalse(regex("(a)|b", Option.NONE).hasLiteralAlternationOptimization());
        assertFalse(regex("a|", Option.NONE).hasLiteralAlternationOptimization());
        assertFalse(regex("a|b", Option.IGNORECASE).hasLiteralAlternationOptimization());
        assertFalse(regex("a|b", Option.FIND_LONGEST).hasLiteralAlternationOptimization());
    }

    private static Regex regex(String source, int options) {
        byte[] bytes = source.getBytes(StandardCharsets.ISO_8859_1);
        return new Regex(bytes, 0, bytes.length, options, ASCIIEncoding.INSTANCE, Syntax.PerlNG);
    }

    private static void assertMatch(Regex regex, String input, int begin, int end) {
        byte[] bytes = input.getBytes(StandardCharsets.ISO_8859_1);
        Matcher matcher = regex.matcher(bytes);
        assertTrue(matcher.search(0, bytes.length, Option.NONE) >= 0);
        assertEquals(begin, matcher.getBegin());
        assertEquals(end, matcher.getEnd());
    }
}
