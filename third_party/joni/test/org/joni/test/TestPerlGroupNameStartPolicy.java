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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.UTF8Encoding;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.joni.WarnCallback;
import org.joni.exception.SyntaxException;
import org.junit.Test;

public class TestPerlGroupNameStartPolicy {
    @Test
    public void acceptsPerlLetterAndUnderscoreStarts() {
        compile("(?<_name>x)");
        compile("(?<\u00e9name>x)");
        compile("(?<\u2163name>x)");
    }

    @Test
    public void rejectsWordCharactersThatAreNotPerlNameStarts() {
        assertStartError("(?<\u203fname>x)", 6);
        assertStartError("(?<\u0301name>x)", 5);
    }

    @Test
    public void delegatesCaptureNameContinuationToPerlPolicy() {
        String pattern = "(?<a\u24b7b>x)";
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        Syntax syntax = new Syntax(Syntax.PerlNG.name, Syntax.PerlNG.op,
                Syntax.PerlNG.op2, Syntax.PerlNG.op3, Syntax.PerlNG.behavior,
                Syntax.PerlNG.options, Syntax.PerlNG.metaCharTable,
                null, null, null, codePoint -> codePoint != 0x24b7);
        SyntaxException error = assertThrows(SyntaxException.class,
                () -> new Regex(bytes, 0, bytes.length, Option.CAPTURE_GROUP,
                        UTF8Encoding.INSTANCE, syntax, WarnCallback.NONE));
        assertTrue(error.getMessage().contains(
                "\\x{24B7} is a \\w char that isn't valid in a name"));
    }

    private static Regex compile(String pattern) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        return new Regex(bytes, 0, bytes.length, Option.CAPTURE_GROUP,
                UTF8Encoding.INSTANCE, Syntax.PerlNG, WarnCallback.NONE);
    }

    private static void assertStartError(String pattern, int bytePosition) {
        SyntaxException error = assertThrows(SyntaxException.class,
                () -> compile(pattern));
        assertEquals("Group name must start with a non-digit word character",
                error.getMessage());
        assertEquals(bytePosition, error.getPatternPosition());
    }
}
