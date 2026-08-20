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
package org.joni;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.jcodings.specific.UTF8Encoding;
import org.joni.constants.internal.AnchorType;
import org.junit.Test;

public class TestBeginPositionSearchStart {
    private static Regex compile(String pattern) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        return new Regex(bytes, 0, bytes.length, Option.NONE,
                UTF8Encoding.INSTANCE, Syntax.Perl);
    }

    private static final class CountingMatcher extends Matcher {
        private final List<Integer> attemptedStarts = new ArrayList<>();

        CountingMatcher(Regex regex, byte[] bytes) {
            super(regex, null, bytes, 0, bytes.length);
        }

        @Override
        protected int matchAt(int range, int start, int previous, boolean interrupt) {
            attemptedStarts.add(start);
            return FAILED;
        }

        @Override protected void stateCheckBuffInit(int length, int offset, int states) { }
        @Override protected void stateCheckBuffClear() { }
        @Override public void interrupt() { }
    }

    private static void assertAttempts(int gpos, int start, int range,
                                       Integer... expectedStarts) {
        Regex regex = compile("\\G.");
        byte[] subject = "abcdef".getBytes(StandardCharsets.UTF_8);
        CountingMatcher matcher = new CountingMatcher(regex, subject);
        assertEquals(Matcher.FAILED, matcher.search(gpos, start, range, Option.NONE));
        assertEquals(List.of(expectedStarts), matcher.attemptedStarts);
    }

    @Test
    public void mandatoryBeginPositionTriesOnlyGposAcrossForwardRange() {
        Regex regex = compile("\\G.");
        assertTrue((regex.anchor & AnchorType.BEGIN_POSITION) != 0);

        assertAttempts(2, 2, 6, 2);
        assertAttempts(4, 2, 6, 4);
        assertAttempts(6, 2, 6, 6);
        assertAttempts(1, 2, 6);
        assertAttempts(7, 2, 6);
    }

    @Test
    public void mandatoryBeginPositionTriesOnlyGposAcrossReverseRange() {
        assertAttempts(6, 6, 2, 6);
        assertAttempts(4, 6, 2, 4);
        assertAttempts(2, 6, 2, 2);
        assertAttempts(1, 6, 2);
        assertAttempts(7, 6, 2);
    }
}
