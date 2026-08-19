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

import org.jcodings.specific.ASCIIEncoding;
import org.joni.Matcher;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.junit.Test;

public class TestPerlNegativeLookBehindCapture {
    private static int search(String pattern, String input) {
        byte[] patternBytes = pattern.getBytes(StandardCharsets.US_ASCII);
        byte[] inputBytes = input.getBytes(StandardCharsets.US_ASCII);
        Regex regex = new Regex(patternBytes, 0, patternBytes.length, Option.NONE,
                ASCIIEncoding.INSTANCE, Syntax.PerlNG);
        Matcher matcher = regex.matcher(inputBytes);
        return matcher.search(0, inputBytes.length, Option.NONE);
    }

    @Test
    public void permitsCapturesInNegativeLookBehind() {
        assertEquals(0, search("(?<!(a))b", "b"));
        assertEquals(-1, search("(?<!(a))b", "ab"));
    }

    @Test
    public void acceptTerminatesNegativeLookBehindPath() {
        String pattern = "(?<!([cd](*ACCEPT)|x)gggg)blrph";
        assertEquals(-1, search(pattern, "cblrph"));
        assertEquals(-1, search(pattern, "dblrph"));
        assertEquals(-1, search(pattern, "xggggblrph"));
        assertEquals(1, search(pattern, "qblrph"));
    }
}
