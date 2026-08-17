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

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.UTF8Encoding;
import org.joni.Matcher;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.junit.Test;

public class TestPerlBranchReset {
    private static Matcher matcher(String pattern, String input) {
        byte[] patternBytes = pattern.getBytes(StandardCharsets.UTF_8);
        byte[] inputBytes = input.getBytes(StandardCharsets.UTF_8);
        Regex regex = new Regex(patternBytes, 0, patternBytes.length,
                Option.CAPTURE_GROUP, UTF8Encoding.INSTANCE, Syntax.RUBY);
        return regex.matcher(inputBytes);
    }

    @Test
    public void alternativesReuseCaptureNumbers() {
        Matcher first = matcher("^((?|([a-z]+)-(\\d+)|([a-z]+):([A-Z]+)?:(\\d+)))$", "abc-12");
        assertEquals(0, first.search(0, 6, Option.NONE));
        assertEquals(0, first.getRegion().getBeg(2));
        assertEquals(3, first.getRegion().getEnd(2));
        assertEquals(4, first.getRegion().getBeg(3));

        Matcher second = matcher("^((?|([a-z]+)-(\\d+)|([a-z]+):([A-Z]+)?:(\\d+)))$", "abc:X:12");
        assertEquals(0, second.search(0, 8, Option.NONE));
        assertEquals(0, second.getRegion().getBeg(2));
        assertEquals(4, second.getRegion().getBeg(3));
        assertEquals(6, second.getRegion().getBeg(4));
    }
}
