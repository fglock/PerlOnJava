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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.jcodings.specific.UTF8Encoding;
import org.joni.constants.internal.AnchorType;
import org.junit.Test;

public class TestLazyAnyCharStarAnchor {
    private static Regex compile(String source, int options) {
        byte[] pattern = source.getBytes(StandardCharsets.UTF_8);
        return new Regex(pattern, 0, pattern.length, options,
                UTF8Encoding.INSTANCE, Syntax.Perl);
    }

    private static Matcher matcher(Regex regex, String subject) {
        byte[] bytes = subject.getBytes(StandardCharsets.UTF_8);
        return regex.matcher(bytes, 0, bytes.length);
    }

    @Test
    public void lazyLeadingDotStarUsesTheSameImplicitAnchorAsGreedy() {
        Regex lazy = compile(".*?x", Option.NONE);

        assertTrue((lazy.anchor & AnchorType.ANYCHAR_STAR) != 0);
    }

    @Test
    public void lazyMatchExtentAndLineStartSemanticsRemainUnchanged() {
        Regex lazy = compile(".*?x", Option.NONE);

        Matcher first = matcher(lazy, "abxx");
        assertEquals(0, first.search(0, 4, Option.NONE));
        assertEquals(0, first.getBegin());
        assertEquals(3, first.getEnd());

        Matcher afterNewline = matcher(lazy, "before\nx");
        assertEquals(7, afterNewline.search(0, 8, Option.NONE));
        assertEquals(7, afterNewline.getBegin());
        assertEquals(8, afterNewline.getEnd());
    }

    @Test
    public void alternativesDoNotAcquireAnUnsafeImplicitAnchor() {
        Regex alternative = compile("a|.*?x", Option.NONE);
        assertFalse((alternative.anchor & AnchorType.ANYCHAR_STAR_MASK) != 0);

        Matcher matcher = matcher(alternative, "ba");
        assertEquals(1, matcher.search(0, 2, Option.NONE));
        assertEquals(1, matcher.getBegin());
        assertEquals(2, matcher.getEnd());
    }

    @Test
    public void compiledLazyAnchorIsReusableAcrossThreads() throws Exception {
        Regex lazy = compile(".*?x", Option.NONE);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int task = 0; task < 2; task++) {
                tasks.add(() -> {
                    for (int iteration = 0; iteration < 250; iteration++) {
                        Matcher matcher = matcher(lazy, "before\nx");
                        if (matcher.search(0, 8, Option.NONE) != 7
                                || matcher.getBegin() != 7
                                || matcher.getEnd() != 8) return false;
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
