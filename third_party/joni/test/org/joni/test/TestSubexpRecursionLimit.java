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

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.ASCIIEncoding;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.joni.exception.ValueException;
import org.junit.Test;

public class TestSubexpRecursionLimit {
    @Test(expected = ValueException.class)
    public void deepPurePatternRecursionStopsInsideTheMatcher() {
        byte[] pattern = "(?<nested>a\\g<nested>?b)".getBytes(StandardCharsets.US_ASCII);
        String inputString = "a".repeat(1100) + "b".repeat(1100);
        byte[] input = inputString.getBytes(StandardCharsets.US_ASCII);
        Regex regex = new Regex(pattern, 0, pattern.length, Option.NONE,
                ASCIIEncoding.INSTANCE, Syntax.RUBY);

        regex.matcher(input).search(0, input.length, Option.NONE);
    }
}
