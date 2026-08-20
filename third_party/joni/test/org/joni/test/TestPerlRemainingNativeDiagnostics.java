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

public class TestPerlRemainingNativeDiagnostics {
    private static SyntaxException error(String pattern) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        return assertThrows(SyntaxException.class,
                () -> new Regex(bytes, 0, bytes.length, Option.NONE,
                        UTF8Encoding.INSTANCE, Syntax.PerlNG, WarnCallback.NONE));
    }

    private static void assertDiagnostic(String pattern, String message,
                                         int bytePosition) {
        SyntaxException error = error(pattern);
        assertEquals(message, error.getMessage());
        assertEquals(bytePosition, error.getPatternPosition());
    }

    @Test
    public void positionsUnterminatedProperties() {
        assertDiagnostic("\\p{x", "Missing right brace on \\p{}", 3);
        assertDiagnostic("[\\P{x]", "Missing right brace on \\P{}", 4);
    }

    @Test
    public void rejectsNonAsciiControlArgumentsAtTheArgumentEnd() {
        assertDiagnostic("\\cé",
                "Character following \"\\c\" must be printable ASCII", 4);
        assertDiagnostic("[\\cé]",
                "Character following \"\\c\" must be printable ASCII", 5);
    }

    @Test
    public void reportsExtendedClassExpressionState() {
        String missingOperand = "(?[ \\cK + ])";
        assertDiagnostic(missingOperand, "Incomplete expression within '(?[ ])'",
                missingOperand.indexOf(']'));

        String emptyParentheses = "(?[ ( ) ])";
        assertDiagnostic(emptyParentheses, "Incomplete expression within '(?[ ])'",
                emptyParentheses.indexOf(')') + 1);

        String missingOuterParenthesis = "(?[ \\t ]";
        assertDiagnostic(missingOuterParenthesis,
                "Unexpected ']' with no following ')' in (?[...",
                missingOuterParenthesis.length());

        String invalidPosix = "(?[[[:w:]]])";
        assertDiagnostic(invalidPosix,
                "Unexpected ']' with no following ')' in (?[...",
                invalidPosix.lastIndexOf("]]" ) + 1);

        String standardLeafWithoutOuterClose = "(?[ [ \\t ]";
        assertDiagnostic(standardLeafWithoutOuterClose,
                "Syntax error in (?[...])", standardLeafWithoutOuterClose.length());

        String commentConsumesClose = "(?[ \\t + \\e # comment ])";
        assertDiagnostic(commentConsumesClose, "Syntax error in (?[...])",
                commentConsumesClose.length());
    }

    @Test
    public void positionsExtendedClassOctalWidthAfterTheEscape() {
        String tooShort = "(?[ \\05 ])";
        assertDiagnostic(tooShort, "Need exactly 3 octal digits",
                tooShort.indexOf(']'));
        String tooLong = "(?[ \\0004 ])";
        assertDiagnostic(tooLong, "Need exactly 3 octal digits",
                tooLong.indexOf(']'));
    }
}
