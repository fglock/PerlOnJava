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
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.joni.test;

import static org.joni.constants.SyntaxProperties.OP2_OPTION_PERL;
import static org.joni.constants.SyntaxProperties.OP2_OPTION_RUBY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.UTF8Encoding;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.joni.WarnCallback;
import org.joni.exception.SyntaxException;
import org.junit.Test;

public class TestPerlNativePolicyDiagnostics {
    private static final Syntax PERL_SYNTAX = new Syntax(
            "PerlNativePolicyDiagnostics", Syntax.RUBY.op,
            (Syntax.RUBY.op2 & ~OP2_OPTION_RUBY) | OP2_OPTION_PERL,
            Syntax.RUBY.op3, Syntax.RUBY.behavior, Syntax.RUBY.options,
            Syntax.RUBY.metaCharTable);

    private static void assertDiagnostic(String pattern, String message,
                                         int bytePosition) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        SyntaxException error = assertThrows(SyntaxException.class,
                () -> new Regex(bytes, 0, bytes.length, Option.CAPTURE_GROUP,
                        UTF8Encoding.INSTANCE, PERL_SYNTAX, WarnCallback.NONE));
        assertEquals(message, error.getMessage());
        assertEquals(bytePosition, error.getPatternPosition());
    }

    @Test
    public void rejectsCommentSeparatedGroupIntroducers() {
        String comment = "(?# comment)";
        assertDiagnostic("(" + comment + "?:foo)",
                "In '(?...)', the '(' and '?' must be adjacent",
                comment.getBytes(StandardCharsets.UTF_8).length + 2);
        assertDiagnostic("(" + comment + "*FAIL)",
                "In '(*VERB...)', the '(' and '*' must be adjacent",
                comment.getBytes(StandardCharsets.UTF_8).length + 2);
        assertDiagnostic("(" + comment + "*script_run:foo)",
                "In '(*...)', the '(' and '*' must be adjacent",
                comment.getBytes(StandardCharsets.UTF_8).length + 2);
    }

    @Test
    public void reportsUnrecognizedGroupSequences() {
        String nonAscii = "(?é)";
        assertDiagnostic(nonAscii, "Sequence (?é...) not recognized",
                "(?é".getBytes(StandardCharsets.UTF_8).length);

        String caret = "(?[ \\p{Digit} & (?^(?[ \\p{Thai} | \\p{Lao} ]))])";
        assertDiagnostic(caret, "Sequence (?^(...) not recognized",
                caret.indexOf("(?^(") + 4);
    }

    @Test
    public void enforcesExtendedClassHexPolicy() {
        String longHex = "(?[ \\xabcdef ])";
        assertDiagnostic(longHex,
                "Use \\x{...} for more than two hex characters",
                longHex.indexOf("\\xabc") + 5);

        String emptyHex = "(?[ \\x{} ])";
        assertDiagnostic(emptyHex, "Empty \\x{}",
                emptyHex.indexOf("\\x{}") + 4);
    }

}
