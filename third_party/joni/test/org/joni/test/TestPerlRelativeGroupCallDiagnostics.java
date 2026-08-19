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

import static org.joni.constants.SyntaxProperties.ALLOW_MULTIPLEX_DEFINITION_NAME_CALL;
import static org.joni.constants.SyntaxProperties.OP2_OPTION_PERL;
import static org.joni.constants.SyntaxProperties.OP2_OPTION_RUBY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.UTF8Encoding;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.joni.exception.SyntaxException;
import org.junit.Test;

public class TestPerlRelativeGroupCallDiagnostics {
    private static final Syntax PERL_SYNTAX = new Syntax(
            "PERL_TEST", Syntax.RUBY.op,
            (Syntax.RUBY.op2 & ~OP2_OPTION_RUBY) | OP2_OPTION_PERL,
            Syntax.RUBY.op3,
            Syntax.RUBY.behavior | ALLOW_MULTIPLEX_DEFINITION_NAME_CALL,
            Syntax.RUBY.options, Syntax.RUBY.metaCharTable);

    private static Regex compile(String pattern) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        return new Regex(bytes, 0, bytes.length, Option.CAPTURE_GROUP,
                UTF8Encoding.INSTANCE, PERL_SYNTAX);
    }

    private static void assertCompileError(String pattern, String message,
                                           int position) {
        SyntaxException error = assertThrows(SyntaxException.class,
                () -> compile(pattern));
        assertEquals(message, error.getMessage());
        assertEquals(position, error.getPatternPosition());
    }

    @Test
    public void relativeCallsCompileWithoutAdapterRewriting() {
        byte[] aa = "aa".getBytes(StandardCharsets.UTF_8);
        assertEquals(0, compile("\\A(a)(?-1)\\z").matcher(aa)
                .search(0, aa.length, Option.NONE));
        assertEquals(0, compile("\\A(?+1)(a)\\z").matcher(aa)
                .search(0, aa.length, Option.NONE));
    }

    @Test
    public void largeRelativeCallsRetainPerlErrorPosition() {
        assertCompileError("((?+2147483647))", "Invalid reference to group", 15);
        assertCompileError("((?-2147483647))", "Reference to nonexistent group", 15);
        assertCompileError("((?+18446744073709551615))",
                "Invalid reference to group", 24);
        assertCompileError("((?-18446744073709551615))",
                "Invalid reference to group", 24);
    }
}
