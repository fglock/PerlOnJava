/*
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to
 * deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or
 * sell copies of the Software, and to permit persons to whom the Software is
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

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.UTF8Encoding;
import org.junit.Test;

public class TestPerlPositiveLookaheadAnyCharStarSearch {
    private static final String PATTERN =
            "(?msix)^(?=[\\*\\#]+\\s)(.*?)(?:^(?![\\*\\#\\s])|\\z)";

    private static Regex compile() {
        byte[] pattern = PATTERN.getBytes(StandardCharsets.UTF_8);
        return new Regex(pattern, 0, pattern.length, Option.NONE,
                UTF8Encoding.INSTANCE, Syntax.Perl);
    }

    private static Matcher matcher(Regex regex, String input) {
        byte[] subject = input.getBytes(StandardCharsets.UTF_8);
        return regex.matcher(subject, 0, subject.length);
    }

    @Test
    public void findsLookaheadConstrainedMultilineCaptureAfterPrefix() {
        String input = "prefix\n\n----\n\n* list 1\n** list 2\n\n----\n";
        Regex regex = compile();
        assertFalse("lookahead-constrained lazy wildcard must not use the anychar-star shortcut; anchor="
                        + regex.getOptimizationInfo().anchor() + ", beginLine="
                        + regex.getOptimizationInfo().beginLineAnchored(),
                regex.getOptimizationInfo().implicitMultiLineAnchor());
        Matcher matcher = matcher(regex, input);

        assertEquals(input.indexOf("* list"),
                matcher.search(0, input.length(), Option.NONE));
        assertEquals(input.indexOf("* list"), matcher.getBegin());
    }

    @Test
    public void doesNotInventMatchWhenConstrainedLineIsAbsent() {
        String input = "prefix\nplain text\nend\n";
        assertEquals(Matcher.FAILED,
                matcher(compile(), input).search(0, input.length(), Option.NONE));
    }
}
