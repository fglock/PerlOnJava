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
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.ASCIIEncoding;
import org.joni.CalloutHandler;
import org.joni.CalloutResult;
import org.joni.MatchView;
import org.joni.Matcher;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.junit.Test;

public class TestPerlCalloutReentrantMatcher {
    @Test
    public void nestedMatcherDoesNotOverwriteCallerCaptures() {
        Regex outer = regex("()([A-Za-z][A-Za-z ]*)(?{=CALL:1}), "
                + "(\\d+)(?{=CALL:2}) years old, secret number (\\d+)"
                + "(?{=CALL:3})");
        Regex nested = regex("(x)");
        byte[] input = ("Jim Jones, 35 years old, secret wombat 007. "
                + "John Smith, 42 years old, secret number 36")
                .getBytes(StandardCharsets.US_ASCII);
        Matcher matcher = outer.matcher(input);
        matcher.setCalloutHandler(new CalloutHandler() {
            @Override
            public CalloutResult execute(int id, MatchView match) {
                if (id == 3) {
                    byte[] nestedInput = "x".getBytes(StandardCharsets.US_ASCII);
                    assertEquals(0, nested.matcher(nestedInput)
                            .search(0, nestedInput.length, Option.NONE));
                }
                return CalloutResult.CONTINUE;
            }

            @Override
            public void unwind(Object token) {
            }
        });

        int result = matcher.search(0, input.length, Option.NONE);
        assertTrue(result > 0);
        assertEquals(result, matcher.captureBegin(1));
        assertEquals(result, matcher.captureEnd(1));
        assertEquals("John Smith", capture(input, matcher, 2));
        assertEquals("42", capture(input, matcher, 3));
    }

    private static Regex regex(String source) {
        byte[] pattern = source.getBytes(StandardCharsets.US_ASCII);
        return new Regex(pattern, 0, pattern.length, Option.NONE,
                ASCIIEncoding.INSTANCE, Syntax.PerlNG);
    }

    private static String capture(byte[] input, Matcher matcher, int group) {
        int begin = matcher.captureBegin(group);
        int end = matcher.captureEnd(group);
        return new String(input, begin, end - begin, StandardCharsets.US_ASCII);
    }
}
