/*
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
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
 *
 * PerlOnJava regression test for repeated-whitespace matching behavior.
 */
package org.joni;

import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.jcodings.specific.UTF8Encoding;
import org.junit.Test;

public class TestPerlRepeatedWhitespaceBacktracking {
    @Test
    public void extendedLiteralBraceDoesNotCaptureFollowingPatternWhitespace() {
        String source = " { ";
        byte[] pattern = source.getBytes(StandardCharsets.UTF_8);
        Regex regex = new Regex(pattern, 0, pattern.length,
                Option.EXTEND, UTF8Encoding.INSTANCE, Syntax.Perl);
        byte[] input = "a { b".getBytes(StandardCharsets.UTF_8);
        Matcher matcher = regex.matcher(input, 0, input.length);

        assertEquals(2, matcher.search(0, input.length, Option.NONE));
        assertEquals(3, matcher.getEnd());
    }

    @Test
    public void lazyParagraphMatchFindsEachRepeatedWhitespaceSeparator() {
        String source = "(?:(.*?)((?:\\s*\\n){2,}))|(.+$)";
        byte[] pattern = source.getBytes(StandardCharsets.UTF_8);
        Regex regex = new Regex(pattern, 0, pattern.length,
                Option.MULTILINE | Option.CAPTURE_GROUP,
                UTF8Encoding.INSTANCE, Syntax.Perl);

        String subject = "a\n\nb\n\n=head1 X\n\nhello\n";
        byte[] input = subject.getBytes(StandardCharsets.UTF_8);
        Matcher matcher = regex.matcher(input, 0, input.length);
        List<String> paragraphs = new ArrayList<>();
        int position = 0;
        while (position <= input.length
                && matcher.search(position, input.length, Option.NONE) >= 0) {
            int begin = matcher.getRegion().getBeg(1);
            int end = matcher.getRegion().getEnd(1);
            if (begin < 0) {
                begin = matcher.getRegion().getBeg(3);
                end = matcher.getRegion().getEnd(3);
            }
            paragraphs.add(new String(input, begin, end - begin,
                    StandardCharsets.UTF_8));
            position = matcher.getEnd();
        }

        assertEquals(List.of("a", "b", "=head1 X", "hello\n"), paragraphs);
    }
}
