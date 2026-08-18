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
import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.UTF8Encoding;
import org.joni.Matcher;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.joni.exception.SyntaxException;
import org.junit.Test;

public class TestPerlLookBehindNativeAcceptance {
    private static Matcher matcher(String pattern, String input, Syntax syntax) {
        byte[] patternBytes = pattern.getBytes(StandardCharsets.UTF_8);
        byte[] inputBytes = input.getBytes(StandardCharsets.UTF_8);
        Regex regex = new Regex(patternBytes, 0, patternBytes.length, Option.NONE,
                UTF8Encoding.INSTANCE, syntax);
        return regex.matcher(inputBytes);
    }

    @Test
    public void nestedLookAheadEndAnchorsRemainValidInsidePerlLookBehind() {
        String pattern = "(.*)/(.*)/(.*)\\."
                + "(?<=(?=(?:\\.(?!\\d+\\b)\\w{1,4}$)$)\\.)"
                + "(.*)$()";
        String input = "my/dir/audio_07.mp3";
        assertEquals(0, matcher(pattern, input, Syntax.Perl)
                .search(0, input.length(), Option.NONE));
        assertThrows(SyntaxException.class, () -> matcher(pattern, input, Syntax.RUBY));
    }

    @Test
    public void caseInsensitiveNegatedClassLookBehindKeepsOneCharacterWidth() {
        assertEquals(1, matcher("(?i:(?<=[^-.])(?=[-]))", "a-", Syntax.Perl)
                .search(0, 2, Option.NONE));
    }
}
