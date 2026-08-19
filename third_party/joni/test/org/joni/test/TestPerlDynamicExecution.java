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
import org.joni.DynamicPatternResult;
import org.joni.MatchView;
import org.joni.Matcher;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.junit.Test;

public class TestPerlDynamicExecution {
    private static Regex regex(String pattern) {
        byte[] bytes = pattern.getBytes(StandardCharsets.US_ASCII);
        return new Regex(bytes, 0, bytes.length, Option.NONE,
                ASCIIEncoding.INSTANCE, Syntax.RUBY);
    }

    @Test
    public void nestedAlternativesResolveTokensOnlyWhenTheOuterPathCommits() {
        List<String> events = new ArrayList<>();
        Regex outer = regex("\\A(?{=DYNAMIC:1})b\\z");
        Regex nested = regex("ab(?{=CALL:2})|a(?{=CALL:3})");
        CalloutHandler nestedHandler = new CalloutHandler() {
            @Override
            public CalloutResult execute(int id, MatchView match) {
                events.add("nested-execute:" + id);
                return CalloutResult.continueWith("nested-" + id);
            }

            @Override
            public void unwind(Object token) {
                events.add("nested-unwind:" + token);
            }

            @Override
            public void complete(Object token) {
                events.add("nested-complete:" + token);
            }

            @Override
            public void finish(boolean matched) {
                events.add("nested-finish:" + matched);
            }
        };
        CalloutHandler outerHandler = new CalloutHandler() {
            @Override
            public CalloutResult execute(int id, MatchView match) {
                throw new AssertionError("plain outer callout not expected");
            }

            @Override
            public DynamicPatternResult executeDynamic(int id, MatchView match) {
                events.add("dynamic:" + id);
                return new DynamicPatternResult(nested, nestedHandler, "outer-dynamic");
            }

            @Override
            public void unwind(Object token) {
                events.add("outer-unwind:" + token);
            }

            @Override
            public void complete(Object token) {
                events.add("outer-complete:" + token);
            }

        };

        byte[] input = "ab".getBytes(StandardCharsets.US_ASCII);
        Matcher matcher = outer.matcher(input);
        matcher.setCalloutHandler(outerHandler);

        assertEquals(0, matcher.search(0, input.length, Option.NONE));
        assertEquals(Arrays.asList(
                "dynamic:1",
                "nested-execute:2",
                "nested-unwind:nested-2",
                "nested-execute:3",
                "nested-complete:nested-3",
                "nested-finish:true",
                "outer-complete:outer-dynamic"), events);
    }
}
