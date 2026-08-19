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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.joni.PerlPropertyValueMatcher;
import org.joni.exception.SyntaxException;
import org.junit.Test;

public class TestPerlPropertyValueMatcher {
    @Test
    public void defaultsToCaseInsensitiveSearchSemantics() {
        PerlPropertyValueMatcher matcher =
                PerlPropertyValueMatcher.compile("old i");
        assertTrue(matcher.matchesPropertyValue("Old Italic"));
        assertFalse(matcher.matchesPropertyValue("Greek"));
    }

    @Test
    public void honorsAnchorsAndInlineCasePolicy() {
        PerlPropertyValueMatcher exact =
                PerlPropertyValueMatcher.compile("\\A(?:yes|true)\\z");
        assertTrue(exact.matchesPropertyValue("TRUE"));
        assertFalse(exact.matchesPropertyValue("not true"));

        PerlPropertyValueMatcher caseSensitive =
                PerlPropertyValueMatcher.compile("(?-i:old)");
        assertTrue(caseSensitive.matchesPropertyValue("olditalic"));
        assertFalse(caseSensitive.matchesPropertyValue("Old_Italic"));
    }

    @Test
    public void supportsUnanchoredNumericValueSelection() {
        PerlPropertyValueMatcher matcher =
                PerlPropertyValueMatcher.compile("[0-5]");
        assertTrue(matcher.matchesPropertyValue("1/2"));
        assertFalse(matcher.matchesPropertyValue("NaN"));
    }

    @Test
    public void supportsCaseSensitivePropertyNameSelection() {
        PerlPropertyValueMatcher matcher =
                PerlPropertyValueMatcher.compile("KATAKANA", false);
        assertTrue(matcher.matchesPropertyValue("KATAKANA LETTER NE"));
        assertFalse(matcher.matchesPropertyValue("Katakana Letter Ne"));
    }

    @Test(expected = SyntaxException.class)
    public void rejectsMalformedPerlPatterns() {
        PerlPropertyValueMatcher.compile("[");
    }
}
