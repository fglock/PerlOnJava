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
package org.joni.test;

import static org.joni.exception.ErrorMessages.PERL_GROUP_NAME_MUST_START_WITH_WORD;
import static org.joni.exception.ErrorMessages.PERL_REFERENCE_TO_NONEXISTENT_NAMED_GROUP;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.UTF8Encoding;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.joni.WarnCallback;
import org.joni.exception.SyntaxException;
import org.junit.Test;

public class TestPerlNamedReferenceDiagnostics {
    private static Regex compile(String pattern) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        return new Regex(bytes, 0, bytes.length, Option.NONE, UTF8Encoding.INSTANCE,
                Syntax.PerlNG, WarnCallback.NONE);
    }

    private static void assertSyntaxError(String pattern, String expected) {
        try {
            compile(pattern);
            fail("expected syntax error for " + pattern);
        } catch (SyntaxException error) {
            assertEquals(expected, error.getMessage());
        }
    }

    private static void assertMatch(String pattern, String input) {
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        assertEquals(0, compile(pattern).matcher(bytes)
                .search(0, bytes.length, Option.NONE));
    }

    @Test
    public void reportsUnknownAndDigitLeadingNamesWithPerlDiagnostics() {
        assertSyntaxError("foo \\k'n'",
                PERL_REFERENCE_TO_NONEXISTENT_NAMED_GROUP);
        assertSyntaxError("foo \\k<_0_>",
                PERL_REFERENCE_TO_NONEXISTENT_NAMED_GROUP);
        assertSyntaxError("foo \\k'0'", PERL_GROUP_NAME_MUST_START_WITH_WORD);
        assertSyntaxError("foo \\k<1a>", PERL_GROUP_NAME_MUST_START_WITH_WORD);
    }

    @Test
    public void trimsOnlyBraceDelimitedNamedReferences() {
        assertMatch("(?<as>as) (\\w+) \\k{ as } (\\w+)", "as easy as pie");
        assertMatch("(?'n'foo) \\g{ n }", "foo foo");
        assertSyntaxError("(?<as>as) \\k< as>",
                PERL_GROUP_NAME_MUST_START_WITH_WORD);
    }
}
