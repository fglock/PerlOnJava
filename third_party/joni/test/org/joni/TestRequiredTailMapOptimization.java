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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.jcodings.specific.UTF8Encoding;
import org.junit.Test;

public class TestRequiredTailMapOptimization {
    private static Regex compile(String source) {
        byte[] pattern = source.getBytes(StandardCharsets.UTF_8);
        return new Regex(pattern, 0, pattern.length, Option.NONE,
                UTF8Encoding.INSTANCE, Syntax.Perl);
    }

    private static Matcher matcher(Regex regex, String subject) {
        byte[] bytes = subject.getBytes(StandardCharsets.UTF_8);
        return regex.matcher(bytes, 0, bytes.length);
    }

    @Test
    public void retainsDisjointMandatoryTailMapBesideFloatingExact() {
        Regex regex = compile(".*a.*b.*c.*[de]");

        assertEquals("a", regex.getOptimizationInfo().exact());
        assertNotNull(regex.requiredTailMap);
        assertEquals(0, regex.requiredTailMap['a']);
        assertEquals(1, regex.requiredTailMap['d']);
        assertEquals(1, regex.requiredTailMap['e']);
    }

    @Test
    public void absentTailRejectsAndFalsePositiveFallsThroughToMatcher() {
        Regex regex = compile(".*a.*b.*c.*[de]");

        assertEquals(Matcher.FAILED,
                matcher(regex, "aaabbbccc").search(0, 9, Option.NONE));
        assertEquals(Matcher.FAILED,
                matcher(regex, "daaabbbccc").search(0, 10, Option.NONE));

        Matcher present = matcher(regex, "xaaabbbcccdy");
        assertEquals(0, present.search(0, 12, Option.NONE));
        assertEquals(0, present.getBegin());
        assertEquals(11, present.getEnd());
    }

    @Test
    public void alternativeTailMapRemainsARequiredUnion() {
        Regex regex = compile(".*a.*(?:[de]|[fg])");

        assertNotNull(regex.requiredTailMap);
        for (byte value : new byte[] {'d', 'e', 'f', 'g'}) {
            assertEquals(1, regex.requiredTailMap[value]);
        }
        assertEquals(Matcher.FAILED,
                matcher(regex, "aaaa").search(0, 4, Option.NONE));
        assertEquals(0,
                matcher(regex, "aaaf").search(0, 4, Option.NONE));
    }

    @Test
    public void optionalOrDuplicateTailDoesNotAddASecondScan() {
        assertNull(compile(".*a.*[de]?").requiredTailMap);
        assertNull(compile(".*a.*a").requiredTailMap);
    }

    @Test
    public void nonWildcardAndBeginPositionSearchesStayOutsideThePrecheck() {
        assertNull(compile("[de]").requiredTailMap);
        assertNull(compile("\\d\\d\\G").requiredTailMap);
    }

    @Test
    public void multibyteTailPrecheckUsesCharacterHeads() {
        Regex regex = compile(".*a.*[é]");

        assertNotNull(regex.requiredTailMap);
        assertEquals(Matcher.FAILED,
                matcher(regex, "aaaa").search(0, 4, Option.NONE));
        assertEquals(0,
                matcher(regex, "aaé").search(0, 4, Option.NONE));
    }

    @Test
    public void compiledPrecheckIsReusableAcrossThreads() throws Exception {
        Regex regex = compile(".*a.*b.*c.*[de]");
        String absent = "a".repeat(1000) + "b".repeat(1000) + "c".repeat(1000);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int task = 0; task < 2; task++) {
                tasks.add(() -> {
                    for (int iteration = 0; iteration < 250; iteration++) {
                        if (matcher(regex, absent).search(0, absent.length(), Option.NONE)
                                != Matcher.FAILED) return false;
                    }
                    return true;
                });
            }
            for (Future<Boolean> result : executor.invokeAll(tasks)) {
                assertTrue(result.get());
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
