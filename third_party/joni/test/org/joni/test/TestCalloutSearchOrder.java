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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.jcodings.specific.ASCIIEncoding;
import org.joni.CalloutHandler;
import org.joni.CalloutResult;
import org.joni.MatchView;
import org.joni.Matcher;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.junit.Test;

public class TestCalloutSearchOrder {
    @Test
    public void searchVisitsCalloutCandidatesInSourceOrder() {
        String pattern = "([ace]).(?{=CALL:1})([ce])(?{=CALL:2})";
        byte[] patternBytes = pattern.getBytes(StandardCharsets.US_ASCII);
        Regex regex = new Regex(patternBytes, 0, patternBytes.length, Option.NONE,
                ASCIIEncoding.INSTANCE, Syntax.RUBY);
        byte[] input = "abcde|abcde".getBytes(StandardCharsets.US_ASCII);
        List<Integer> positions = new ArrayList<>();
        Matcher matcher = regex.matcher(input);
        matcher.setCalloutHandler(new CalloutHandler() {
            @Override
            public CalloutResult execute(int id, MatchView match) {
                positions.add(match.currentBytePosition());
                return CalloutResult.CONTINUE;
            }

            @Override
            public void unwind(Object token) {
            }
        });

        assertEquals(0, matcher.search(0, input.length, Option.NONE));
        assertEquals(6, matcher.search(matcher.getEnd(), input.length, Option.NONE));
        assertEquals(Arrays.asList(2, 3, 6, 8, 9), positions);
    }
}
