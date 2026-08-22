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
import org.jcodings.specific.UTF8Encoding;
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

    @Test
    public void nestedPatternRetainsAsciiStrictCaseFoldOption() {
        byte[] outerBytes = "\\A(?{=DYNAMIC:1})\\z".getBytes(StandardCharsets.UTF_8);
        Regex outer = new Regex(outerBytes, 0, outerBytes.length, Option.NONE,
                UTF8Encoding.INSTANCE, Syntax.RUBY);
        byte[] nestedBytes = "s".getBytes(StandardCharsets.UTF_8);
        Regex nested = new Regex(nestedBytes, 0, nestedBytes.length,
                Option.IGNORECASE | Option.PERL_ASCII_STRICT,
                UTF8Encoding.INSTANCE, Syntax.PerlNG);
        CalloutHandler handler = new CalloutHandler() {
            @Override
            public CalloutResult execute(int id, MatchView match) {
                throw new AssertionError("plain callout not expected");
            }

            @Override
            public DynamicPatternResult executeDynamic(int id, MatchView match) {
                return new DynamicPatternResult(nested, null, null);
            }

            @Override
            public void unwind(Object token) {
            }
        };

        byte[] input = "\u017f".getBytes(StandardCharsets.UTF_8);
        Matcher matcher = outer.matcher(input);
        matcher.setCalloutHandler(handler);
        assertEquals(-1, matcher.search(0, input.length, Option.NONE));
    }

    @Test
    public void incompatibleDynamicInputEncodingFailsAfterRegisteringUnwind() {
        Regex outer = regex("\\A(?{=DYNAMIC:1})\\z");
        Regex nested = regex("a");
        List<String> events = new ArrayList<>();
        CalloutHandler handler = new CalloutHandler() {
            @Override
            public CalloutResult execute(int id, MatchView match) {
                throw new AssertionError("plain callout not expected");
            }

            @Override
            public DynamicPatternResult executeDynamic(int id, MatchView match) {
                events.add("dynamic:" + id);
                return new DynamicPatternResult(nested, null, "dynamic-token", null, false);
            }

            @Override
            public void unwind(Object token) {
                events.add("unwind:" + token);
            }
        };

        byte[] input = "a".getBytes(StandardCharsets.UTF_8);
        Matcher matcher = outer.matcher(input);
        matcher.setCalloutHandler(handler);
        assertEquals(-1, matcher.search(0, input.length, Option.NONE));
        assertEquals(Arrays.asList("dynamic:1", "unwind:dynamic-token"), events);
    }

    @Test
    public void destructiveControlsCommitCalloutsButMarkDoesNot() {
        String[] committing = {"FAIL", "PRUNE)(*FAIL", "SKIP)(*FAIL",
                "THEN)(*FAIL", "COMMIT)(*FAIL"};
        for (String control : committing) {
            List<String> events = new ArrayList<>();
            Regex pattern = regex("a(?{=CALL:1})(*" + control + ")");
            assertEquals(-1, search(pattern, "a", recordingHandler(events)));
            assertEquals(control, Arrays.asList("execute:1", "complete:1"), events);
        }

        List<String> markEvents = new ArrayList<>();
        Regex mark = regex("a(?{=CALL:1})(*MARK:seen)b");
        assertEquals(-1, search(mark, "a", recordingHandler(markEvents)));
        assertEquals(Arrays.asList("execute:1", "unwind:1"), markEvents);
    }

    private static int search(Regex regex, String input, CalloutHandler handler) {
        byte[] bytes = input.getBytes(StandardCharsets.US_ASCII);
        Matcher matcher = regex.matcher(bytes);
        matcher.setCalloutHandler(handler);
        return matcher.search(0, bytes.length, Option.NONE);
    }

    private static CalloutHandler recordingHandler(List<String> events) {
        return new CalloutHandler() {
            @Override
            public CalloutResult execute(int id, MatchView match) {
                events.add("execute:" + id);
                return CalloutResult.continueWith(id);
            }

            @Override
            public void unwind(Object token) {
                events.add("unwind:" + token);
            }

            @Override
            public void complete(Object token) {
                events.add("complete:" + token);
            }
        };
    }
}
