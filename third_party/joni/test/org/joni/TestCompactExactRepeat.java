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
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.UTF8Encoding;
import org.junit.Test;

public class TestCompactExactRepeat {
    private static Regex compile(String source) {
        byte[] pattern = source.getBytes(StandardCharsets.UTF_8);
        return new Regex(pattern, 0, pattern.length, Option.NONE,
                UTF8Encoding.INSTANCE, Syntax.Perl);
    }

    @Test
    public void anchoredLargeRepeatDoesNotExhaustBacktrackingStack() {
        byte[] subject = "x".repeat(1_048_576).getBytes(StandardCharsets.UTF_8);
        Matcher matcher = compile("^x+\\z").matcher(subject);
        assertEquals(subject.length, matcher.match(0, subject.length, Option.NONE));
    }

    @Test
    public void compactRepeatStillBacktracksForItsFollowingLiteral() {
        byte[] subject = "x".repeat(100_000).getBytes(StandardCharsets.UTF_8);
        Matcher matcher = compile("^x+x\\z").matcher(subject);
        assertEquals(subject.length, matcher.match(0, subject.length, Option.NONE));

        Matcher failure = compile("^x+z\\z").matcher(subject);
        assertTrue(failure.match(0, subject.length, Option.NONE) < 0);
    }
}
