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

import static org.joni.constants.SyntaxProperties.ALLOW_MULTIPLEX_DEFINITION_NAME_CALL;
import static org.joni.constants.SyntaxProperties.OP2_OPTION_PERL;
import static org.joni.constants.SyntaxProperties.OP2_OPTION_RUBY;
import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.jcodings.specific.UTF8Encoding;
import org.junit.Test;

public class TestPerlCalledKeepLookaround {
    private static final Syntax PERLONJAVA = new Syntax(
            "PERLONJAVA", Syntax.RUBY.op,
            (Syntax.RUBY.op2 & ~OP2_OPTION_RUBY) | OP2_OPTION_PERL,
            Syntax.RUBY.op3,
            Syntax.RUBY.behavior | ALLOW_MULTIPLEX_DEFINITION_NAME_CALL,
            Syntax.RUBY.options, Syntax.RUBY.metaCharTable);

    private static Regex compile(String pattern) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        return new Regex(bytes, 0, bytes.length, Option.NONE,
                UTF8Encoding.INSTANCE, PERLONJAVA);
    }

    private static void assertInvertedKeep(Regex regex, boolean reverse) {
        byte[] input = "aa".getBytes(StandardCharsets.UTF_8);
        Matcher matcher = regex.matcher(input);
        assertEquals(0, reverse
                ? matcher.search(input.length, 0, Option.NONE)
                : matcher.search(0, input.length, Option.NONE));
        assertEquals(2, matcher.getBegin());
        assertEquals(1, matcher.getEnd());
    }

    @Test
    public void calledKeepInsidePositiveLookaheadPublishesPerlRegion() {
        Regex regex = compile("(?<x>a\\K)(?=\\g<x>)");
        assertInvertedKeep(regex, false);
        assertInvertedKeep(regex, true);
    }

    @Test
    public void definedCalledKeepCanMovePastZeroWidthEnd() {
        byte[] input = "a".getBytes(StandardCharsets.UTF_8);
        Matcher matcher = compile(
                "(?(DEFINE)(?<x>a\\K))(?=\\g<x>)").matcher(input);
        assertEquals(0, matcher.search(0, input.length, Option.NONE));
        assertEquals(1, matcher.getBegin());
        assertEquals(0, matcher.getEnd());
    }

    @Test
    public void matcherInstancesKeepCalledAssertionStateIsolated() throws Exception {
        Regex regex = compile("(?<x>a\\K)(?=\\g<x>)");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int worker = 0; worker < 2; worker++) {
                tasks.add(() -> {
                    for (int iteration = 0; iteration < 250; iteration++) {
                        byte[] input = "aa".getBytes(StandardCharsets.UTF_8);
                        Matcher matcher = regex.matcher(input);
                        if (matcher.search(0, input.length, Option.NONE) != 0
                                || matcher.getBegin() != 2 || matcher.getEnd() != 1) {
                            return false;
                        }
                    }
                    return true;
                });
            }
            for (Future<Boolean> result : pool.invokeAll(tasks)) {
                assertEquals(Boolean.TRUE, result.get());
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
