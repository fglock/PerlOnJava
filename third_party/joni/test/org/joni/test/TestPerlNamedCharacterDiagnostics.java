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

import static org.joni.exception.ErrorMessages.PERL_MISSING_BRACES_ON_NAMED_CHARACTER_ESCAPE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.UTF8Encoding;
import org.joni.NamedCharacterResolver;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.joni.WarnCallback;
import org.joni.exception.SyntaxException;
import org.junit.Test;

public class TestPerlNamedCharacterDiagnostics {
    private static final NamedCharacterResolver RESOLVER =
            (bytes, p, end, encoding) -> 'A';
    private static final Syntax SYNTAX = new Syntax(
            "PerlNamedCharacterDiagnostics", Syntax.PerlNG.op, Syntax.PerlNG.op2,
            Syntax.PerlNG.op3, Syntax.PerlNG.behavior, Syntax.PerlNG.options,
            Syntax.PerlNG.metaCharTable, RESOLVER);

    private static void compile(String pattern) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        new Regex(bytes, 0, bytes.length, Option.NONE, UTF8Encoding.INSTANCE,
                SYNTAX, WarnCallback.NONE);
    }

    private static void assertMissingBraces(String pattern) {
        try {
            compile(pattern);
            fail("expected missing named-character braces for " + pattern);
        } catch (SyntaxException error) {
            assertEquals(PERL_MISSING_BRACES_ON_NAMED_CHARACTER_ESCAPE,
                    error.getMessage());
        }
    }

    @Test
    public void rejectsBracesSeparatedByExtendedWhitespace() {
        assertMissingBraces("(?x)\\N {U+41}");
        assertMissingBraces("(?x)\\N {SPACE}");
    }

    @Test
    public void rejectsBracesSeparatedByACommentGroup() {
        assertMissingBraces("\\N(?#comment){SPACE}");
    }
}
