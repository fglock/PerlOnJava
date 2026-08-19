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

import static org.joni.exception.ErrorMessages.PERL_NON_NEWLINE_IN_CHARACTER_CLASS;
import static org.joni.constants.SyntaxProperties.ALLOW_INTERVAL_LOW_ABBREV;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.UTF8Encoding;
import org.joni.NamedCharacterResolver;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.joni.WarnCallback;
import org.joni.exception.JOniException;
import org.junit.Test;

public class TestPerlNonNewline {
    private static final NamedCharacterResolver RESOLVER =
            (bytes, p, end, encoding) -> 'A';
    private static final Syntax PERLONJAVA = new Syntax(
            "PERLONJAVA", Syntax.PerlNG.op, Syntax.PerlNG.op2,
            Syntax.PerlNG.op3,
            Syntax.PerlNG.behavior | ALLOW_INTERVAL_LOW_ABBREV,
            Syntax.PerlNG.options,
            Syntax.PerlNG.metaCharTable, RESOLVER);

    private static int search(String pattern, String input) {
        byte[] patternBytes = pattern.getBytes(StandardCharsets.UTF_8);
        byte[] inputBytes = input.getBytes(StandardCharsets.UTF_8);
        Regex regex = new Regex(patternBytes, 0, patternBytes.length, Option.NONE,
                UTF8Encoding.INSTANCE, PERLONJAVA, WarnCallback.NONE);
        return regex.matcher(inputBytes).search(0, inputBytes.length, Option.NONE);
    }

    private static void assertMatch(String pattern, String input) {
        assertEquals(0, search("\\A(?:" + pattern + ")\\z", input));
    }

    private static void assertNoMatch(String pattern, String input) {
        assertEquals(-1, search("\\A(?:" + pattern + ")\\z", input));
    }

    private static void assertSyntaxError(String pattern) {
        try {
            search(pattern, "");
            fail("expected syntax error for " + pattern);
        } catch (JOniException error) {
            assertEquals(PERL_NON_NEWLINE_IN_CHARACTER_CLASS, error.getMessage());
        }
    }

    @Test
    public void matchesEverythingExceptLineFeed() {
        assertMatch("\\N", "a");
        assertMatch("\\N", "\r");
        assertMatch("\\N", "\u2028");
        assertNoMatch("\\N", "\n");
        assertNoMatch("(?s)\\N", "\n");
    }

    @Test
    public void acceptsPerlIntervalsAfterTheAtom() {
        assertMatch("\\N{2}", "ab");
        assertMatch("\\N{2,3}", "abc");
        assertMatch("\\N{2,}", "abcd");
        assertMatch("\\N{,2}", "a");
        assertNoMatch("\\N{2}", "a");
    }

    @Test
    public void rejectsPlainNonNewlineInsideCharacterClasses() {
        assertSyntaxError("[\\N]");
        assertSyntaxError("[\\N{2}]");
    }
}
