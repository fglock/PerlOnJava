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

public class TestOptimizerAnchorMetadata {
    private static Regex compile(String source, int options) {
        byte[] pattern = source.getBytes(StandardCharsets.UTF_8);
        return new Regex(pattern, 0, pattern.length, options,
                UTF8Encoding.INSTANCE, Syntax.Perl);
    }

    @Test
    public void exposesImplicitLineAnchorKinds() {
        assertEquals(AnchorType.ANYCHAR_STAR,
                compile(".*?x", Option.NONE).getAnchor() & AnchorType.ANYCHAR_STAR_MASK);
        assertEquals(AnchorType.ANYCHAR_STAR_ML,
                compile(".*?x", Option.MULTILINE).getAnchor()
                        & AnchorType.ANYCHAR_STAR_MASK);
        assertEquals(0, compile("a|.*?x", Option.NONE).getAnchor()
                & AnchorType.ANYCHAR_STAR_MASK);
    }

    @Test
    public void compiledMetadataIsStableAcrossThreads() throws Exception {
        Regex regex = compile(".*?x", Option.NONE);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int task = 0; task < 2; task++) {
                tasks.add(() -> {
                    for (int iteration = 0; iteration < 1_000; iteration++) {
                        if ((regex.getAnchor() & AnchorType.ANYCHAR_STAR_MASK)
                                != AnchorType.ANYCHAR_STAR) return false;
                    }
                    return true;
                });
            }
            for (Future<Boolean> result : executor.invokeAll(tasks)) {
                assertEquals(true, result.get());
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
