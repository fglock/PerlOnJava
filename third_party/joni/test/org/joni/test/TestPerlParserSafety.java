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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.jcodings.specific.UTF8Encoding;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.joni.WarnCallback;
import org.joni.exception.SyntaxException;
import org.junit.Test;

public class TestPerlParserSafety {
    private static final class Warnings implements WarnCallback {
        final List<String> messages = new ArrayList<>();

        @Override
        public void warn(String message) {
            messages.add(message);
        }
    }

    private static Regex compile(String pattern, WarnCallback warnings) {
        return compile(pattern, Syntax.PerlNG, warnings);
    }

    private static Regex compile(String pattern, Syntax syntax,
                                 WarnCallback warnings) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        return new Regex(bytes, 0, bytes.length, Option.NONE,
                UTF8Encoding.INSTANCE, syntax, warnings);
    }

    private static int search(String pattern, String input) {
        byte[] inputBytes = input.getBytes(StandardCharsets.UTF_8);
        return compile("\\A(?:" + pattern + ")\\z", WarnCallback.NONE)
                .matcher(inputBytes).search(0, inputBytes.length, Option.NONE);
    }

    private static void assertInvalid(String pattern, String message, int position) {
        try {
            compile(pattern, WarnCallback.NONE);
            fail("expected invalid Perl pattern " + pattern);
        } catch (SyntaxException error) {
            assertEquals(message, error.getMessage());
            assertEquals(position, error.getPatternPosition());
        }
    }

    @Test
    public void rejectsMalformedNamedBackreferenceEscapes() {
        assertInvalid("\\k", "Sequence \\k... not terminated", 2);
        assertInvalid("\\kx", "Sequence \\k... not terminated", 2);
        assertInvalid("\\k<", "Sequence \\k<... not terminated", 3);
        assertInvalid("\\k'", "Sequence \\k'... not terminated", 3);
        assertInvalid("\\k{", "Sequence \\k{... not terminated", 3);
        assertInvalid("\\k<>",
                "Group name must start with a non-digit word character", 4);
    }

    @Test
    public void parsesNativeNamedCallsAndBraceBackreferences() {
        assertEquals(0, search("(?&word)(?<word>x)", "xx"));
        assertEquals(0, search("(?P>word)(?<word>x)", "xx"));
        assertEquals(0, search("(?<word>x)\\k{word}", "xx"));
    }

    @Test
    public void reportsMalformedNativeNamedCalls() {
        assertInvalid("(?&word", "Sequence (?&... not terminated", 7);
        assertInvalid("(?P>word", "Sequence (?&... not terminated", 8);
        assertInvalid("(?&)",
                "Group name must start with a non-digit word character", 4);
        assertInvalid("(?&1)",
                "Group name must start with a non-digit word character", 4);
        assertInvalid("(?&word!)", "Sequence (?&... not terminated", 7);
    }

    @Test
    public void warnsForIntervalsOnZeroLengthTargets() {
        String pattern = "(?&empty){0}abc(?<empty>)";
        Warnings warnings = new Warnings();
        compile(pattern, warnings);
        assertEquals(1, warnings.messages.size());
        assertEquals("Quantifier unexpected on zero-length expression in regex m/"
                + pattern + "/", warnings.messages.get(0));

        warnings = new Warnings();
        compile("(?&word){0}abc(?<word>x)", warnings);
        assertTrue(warnings.messages.isEmpty());
    }

    @Test
    public void retainsStockRubyEscapeBehavior() {
        compile("\\k", Syntax.RUBY, WarnCallback.NONE);
    }
}
