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

public class TestPerlGrammarDiagnosticPositions {
    private static final Syntax PERL_SYNTAX = new Syntax(
            "PerlGrammarDiagnostics", Syntax.RUBY.op,
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
    public void positionsConditionalGrammarFailures() {
        assertDiagnostic("(?(1x))", "Switch condition not recognized", 5);

        String branches = "(?(1)x|y|z)";
        assertDiagnostic(branches,
                "Switch (?(condition)... contains too many branches",
                branches.lastIndexOf('|') + 1);

        String missingCondition = "\\x{100}(?(";
        assertDiagnostic(missingCondition, "Unknown switch condition (?(...))",
                missingCondition.length());

        assertDiagnostic("(?(?{=DYNAMIC:0}))",
                "Unknown switch condition (?(...))", 4);
    }

    @Test
    public void positionsExtendedClassGrammarFailures() {
        assertDiagnostic("(?[a])", "Unexpected character", 4);

        String operand = "(?[ \\cK \\t ])";
        assertDiagnostic(operand, "Operand with no preceding operator",
                operand.indexOf("\\t") + 2);

        String nestedCondition = "(?[ \\p{Digit} & (?(?[ \\p{Thai} | \\p{Lao} ]))])";
        assertDiagnostic(nestedCondition, "Unexpected character",
                nestedCondition.indexOf("(?(") + 2);
    }

    @Test
    public void positionsUnterminatedNumericEscapeContent() {
        assertDiagnostic("\\x{ 1 ", "Missing right brace on \\x{}", 5);
        assertDiagnostic("[\\x{ A ]", "Missing right brace on \\x{}", 6);
        assertDiagnostic("\\o{ 1 ", "Missing right brace on \\o{}", 5);
        assertDiagnostic("[\\o{ 7 ]", "Missing right brace on \\o{}", 6);
    }
}
