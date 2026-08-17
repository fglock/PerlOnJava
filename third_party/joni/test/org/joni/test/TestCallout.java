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
import org.joni.exception.TimeoutException;
import org.junit.Test;

public class TestCallout {
    private static Regex regex(String pattern) {
        byte[] bytes = pattern.getBytes(StandardCharsets.US_ASCII);
        return new Regex(bytes, 0, bytes.length, Option.NONE, ASCIIEncoding.INSTANCE, Syntax.RUBY);
    }

    private static int search(Regex regex, String value) {
        return search(regex, value, null);
    }

    private static int search(Regex regex, String value, CalloutHandler handler) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        Matcher matcher = regex.matcher(bytes);
        matcher.setCalloutHandler(handler);
        return matcher.search(0, bytes.length, Option.NONE);
    }

    @Test
    public void exposesCurrentPositionAndProvisionalCaptures() {
        List<String> events = new ArrayList<>();
        Regex regex = regex("(a)(?{=CALL:7})b");
        CalloutHandler handler = new CalloutHandler() {
            @Override
            public CalloutResult execute(int id, MatchView match) {
                events.add(id + ":" + match.currentBytePosition() + ":"
                        + match.captureBegin(0) + "-" + match.captureEnd(0) + ":"
                        + match.captureBegin(1) + "-" + match.captureEnd(1));
                return CalloutResult.CONTINUE;
            }

            @Override
            public void unwind(Object token) {
            }
        };

        assertEquals(0, search(regex, "ab", handler));
        assertEquals(Arrays.asList("7:1:0-1:0-1"), events);
    }

    @Test
    public void exposesMostRecentlyClosedCaptureIndependentlyOfHighestNumber() {
        List<String> events = new ArrayList<>();
        Regex regex = regex("((a)b)(?{=CALL:3})");
        CalloutHandler handler = new CalloutHandler() {
            @Override
            public CalloutResult execute(int id, MatchView match) {
                events.add(id + ":" + match.lastClosedCapture() + ":"
                        + match.captureBegin(1) + "-" + match.captureEnd(1) + ":"
                        + match.captureBegin(2) + "-" + match.captureEnd(2));
                return CalloutResult.CONTINUE;
            }

            @Override
            public void unwind(Object token) {
            }
        };

        assertEquals(0, search(regex, "ab", handler));
        assertEquals(Arrays.asList("3:1:0-2:0-1"), events);
    }

    @Test
    public void recursiveSubpatternReturnRestoresCallerCaptures() {
        List<String> events = new ArrayList<>();
        Regex regex = regex("(?<r>(?<letter>a)(?:\\g<r>)?(?{=CALL:4}))");
        CalloutHandler handler = new CalloutHandler() {
            @Override
            public CalloutResult execute(int id, MatchView match) {
                events.add(id + ":" + match.lastClosedCapture() + ":"
                        + match.captureBegin(2) + "-" + match.captureEnd(2));
                return CalloutResult.CONTINUE;
            }

            @Override
            public void unwind(Object token) {
            }
        };

        assertEquals(0, search(regex, "aa", handler));
        assertEquals(Arrays.asList("4:2:1-2", "4:2:0-1"), events);
    }

    @Test
    public void unwindsBacktrackedAndSuccessfulPathsExactlyOnce() {
        List<String> events = new ArrayList<>();
        Regex regex = regex("(a(?{=CALL:1})b|a(?{=CALL:2})c)");
        CalloutHandler handler = recordingHandler(events, false);

        assertEquals(0, search(regex, "ac", handler));
        assertEquals(Arrays.asList("execute:1", "unwind:1", "execute:2", "unwind:2"), events);
    }

    @Test
    public void failResultBacktracksAfterUnwindingItsToken() {
        List<String> events = new ArrayList<>();
        Regex regex = regex("(a(?{=CALL:1})b|ac)");
        CalloutHandler handler = recordingHandler(events, true);

        assertEquals(0, search(regex, "ac", handler));
        assertEquals(Arrays.asList("execute:1", "unwind:1"), events);
    }

    @Test
    public void unwindsInReverseOrderOnSuccess() {
        List<String> events = new ArrayList<>();
        Regex regex = regex("a(?{=CALL:1})(?{=CALL:2})b");
        CalloutHandler handler = recordingHandler(events, false);

        assertEquals(0, search(regex, "ab", handler));
        assertEquals(Arrays.asList("execute:1", "execute:2", "unwind:2", "unwind:1"), events);
    }

    @Test
    public void preservesCalloutsInsideFixedQuantifiers() {
        List<String> events = new ArrayList<>();
        int[] execution = {0};
        Regex regex = regex("(?{=CALL:1}){2}");
        CalloutHandler handler = new CalloutHandler() {
            @Override
            public CalloutResult execute(int id, MatchView match) {
                int token = ++execution[0];
                events.add("execute:" + token);
                return CalloutResult.continueWith(token);
            }

            @Override
            public void unwind(Object token) {
                events.add("unwind:" + token);
            }
        };

        assertEquals(0, search(regex, "", handler));
        assertEquals(Arrays.asList("execute:1", "execute:2", "unwind:2", "unwind:1"), events);
    }

    @Test
    public void unwindsPriorTokensWhenHandlerThrows() {
        List<String> events = new ArrayList<>();
        Regex regex = regex("a(?{=CALL:1})(?{=CALL:2})b");
        CalloutHandler handler = new CalloutHandler() {
            @Override
            public CalloutResult execute(int id, MatchView match) {
                events.add("execute:" + id);
                if (id == 2) throw new IllegalStateException("boom");
                return CalloutResult.continueWith(id);
            }

            @Override
            public void unwind(Object token) {
                events.add("unwind:" + token);
            }
        };

        assertThrows(IllegalStateException.class, () -> search(regex, "ab", handler));
        assertEquals(Arrays.asList("execute:1", "execute:2", "unwind:1"), events);
    }

    @Test
    public void requiresAHandlerForCalloutPatterns() {
        Regex regex = regex("(?{=CALL:1})");
        assertThrows(IllegalStateException.class, () -> search(regex, ""));
    }

    @Test
    public void keepsHandlersLocalToEachMatcher() {
        Regex regex = regex("(?{=CALL:1})");
        byte[] input = new byte[0];
        List<String> firstEvents = new ArrayList<>();
        List<String> secondEvents = new ArrayList<>();
        Matcher first = regex.matcher(input);
        Matcher second = regex.matcher(input);
        first.setCalloutHandler(recordingHandler(firstEvents, false));
        second.setCalloutHandler(recordingHandler(secondEvents, false));

        assertEquals(0, first.search(0, 0, Option.NONE));
        assertEquals(0, second.search(0, 0, Option.NONE));
        assertEquals(Arrays.asList("execute:1", "unwind:1"), firstEvents);
        assertEquals(Arrays.asList("execute:1", "unwind:1"), secondEvents);
    }

    @Test
    public void unwindsActiveTokensWhenInterrupted() {
        List<String> events = new ArrayList<>();
        Regex regex = regex("(?{=CALL:1})a");
        byte[] input = "a".getBytes(StandardCharsets.US_ASCII);
        Matcher matcher = regex.matcher(input);
        matcher.setCalloutHandler(new CalloutHandler() {
            @Override
            public CalloutResult execute(int id, MatchView match) {
                events.add("execute:" + id);
                matcher.interrupt();
                return CalloutResult.continueWith(id);
            }

            @Override
            public void unwind(Object token) {
                events.add("unwind:" + token);
            }
        });

        assertThrows(InterruptedException.class,
                () -> matcher.searchInterruptible(0, input.length, Option.NONE));
        assertEquals(Arrays.asList("execute:1", "unwind:1"), events);
    }

    @Test
    public void unwindsActiveTokensOnTimeout() {
        List<String> events = new ArrayList<>();
        StringBuilder pattern = new StringBuilder("(?{=CALL:1})");
        for (int i = 0; i < 140; i++) pattern.append("()");
        Regex regex = regex(pattern.toString());
        byte[] input = new byte[0];
        Matcher matcher = regex.matcher(input);
        matcher.setTimeout(1_000_000_000L);
        matcher.setCalloutHandler(new CalloutHandler() {
            @Override
            public CalloutResult execute(int id, MatchView match) {
                events.add("execute:" + id);
                matcher.setTimeout(0);
                return CalloutResult.continueWith(id);
            }

            @Override
            public void unwind(Object token) {
                events.add("unwind:" + token);
            }
        });

        assertThrows(TimeoutException.class,
                () -> matcher.searchInterruptible(0, input.length, Option.NONE));
        assertEquals(Arrays.asList("execute:1", "unwind:1"), events);
    }

    @Test
    public void dynamicProgramAlternativesBacktrackIntoTheOuterSuffix() {
        List<String> events = new ArrayList<>();
        Regex outer = regex("\\A(?{=DYNAMIC:1})b\\z");
        Regex nested = regex("ab(?{=CALL:2})|a");
        CalloutHandler nestedHandler = recordingHandler(events, false);
        CalloutHandler outerHandler = new CalloutHandler() {
            @Override
            public CalloutResult execute(int id, MatchView match) {
                throw new AssertionError("plain callback not expected");
            }

            @Override
            public DynamicPatternResult executeDynamic(int id, MatchView match) {
                events.add("dynamic:" + id + ":" + match.currentBytePosition());
                return new DynamicPatternResult(nested, nestedHandler, "dynamic-token");
            }

            @Override
            public void unwind(Object token) {
                events.add("outer-unwind:" + token);
            }
        };

        assertEquals(events.toString(), 0, search(outer, "ab", outerHandler));
        assertEquals(Arrays.asList("dynamic:1:0", "execute:2", "unwind:2",
                "outer-unwind:dynamic-token"), events);
    }

    @Test
    public void enclosingCaptureClosesAfterDynamicProgram() {
        Regex outer = regex("(a)((?{=DYNAMIC:1}))");
        Regex nested = regex("b");
        byte[] input = "aab".getBytes(StandardCharsets.US_ASCII);
        Matcher matcher = outer.matcher(input);
        matcher.setCalloutHandler(new CalloutHandler() {
            @Override
            public CalloutResult execute(int id, MatchView match) {
                throw new AssertionError("plain callback not expected");
            }

            @Override
            public DynamicPatternResult executeDynamic(int id, MatchView match) {
                return new DynamicPatternResult(nested, null, null);
            }

            @Override
            public void unwind(Object token) {
            }
        });

        assertEquals(1, matcher.search(0, input.length, Option.NONE));
        assertEquals(1, matcher.captureBegin(1));
        assertEquals(2, matcher.captureEnd(1));
        assertEquals(2, matcher.captureBegin(2));
        assertEquals(3, matcher.captureEnd(2));
    }

    private static CalloutHandler recordingHandler(List<String> events, boolean fail) {
        return new CalloutHandler() {
            @Override
            public CalloutResult execute(int id, MatchView match) {
                events.add("execute:" + id);
                return fail ? CalloutResult.failWith(id) : CalloutResult.continueWith(id);
            }

            @Override
            public void unwind(Object token) {
                events.add("unwind:" + token);
            }
        };
    }
}
