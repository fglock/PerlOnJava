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

import static org.joni.constants.SyntaxProperties.OP2_OPTION_PERL;
import static org.joni.constants.SyntaxProperties.OP2_OPTION_RUBY;
import static org.joni.exception.ErrorMessages.PERL_KEEP_NOT_PERMITTED_IN_LOOKAROUND;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.UTF8Encoding;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.joni.exception.SyntaxException;
import org.junit.Test;

public class TestPerlKeepLookaround {
    private static final Syntax PERL_SYNTAX = new Syntax(
            "PerlKeepLookaround", Syntax.RUBY.op,
            (Syntax.RUBY.op2 & ~OP2_OPTION_RUBY) | OP2_OPTION_PERL,
            Syntax.RUBY.op3, Syntax.RUBY.behavior, Syntax.RUBY.options,
            Syntax.RUBY.metaCharTable);

    private static Regex compile(String pattern, Syntax syntax) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        return new Regex(bytes, 0, bytes.length, Option.NONE,
                UTF8Encoding.INSTANCE, syntax);
    }

    private static void assertPerlRejected(String pattern) {
        try {
            compile(pattern, PERL_SYNTAX);
            fail("expected syntax error for " + pattern);
        } catch (SyntaxException error) {
            assertEquals(PERL_KEEP_NOT_PERMITTED_IN_LOOKAROUND, error.getMessage());
        }
    }

    @Test
    public void rejectsKeepInAllFourLookaroundForms() {
        assertPerlRejected("(?=a\\K)");
        assertPerlRejected("(?!a\\K)");
        assertPerlRejected("(?<=a\\K)");
        assertPerlRejected("(?<!a\\K)");
    }

    @Test
    public void rejectsKeepNestedInsideLookaround() {
        assertPerlRejected("(?=(?:(?:a\\K)))");
        assertPerlRejected("(?(?=a\\K)b|c)");
    }

    @Test
    public void doesNotRetraverseCalledGroupsInsideLookaround() {
        compile("(?<x>a\\K)(?=\\g<x>)", PERL_SYNTAX);
    }

    @Test
    public void preservesNonPerlSyntaxBehavior() {
        compile("(?=a\\K)", Syntax.RUBY);
    }
}
