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

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.UTF8Encoding;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.joni.WarnCallback;
import org.joni.exception.SyntaxException;
import org.junit.Test;

public class TestPerlCharacterClassDiagnostics {
    @Test
    public void descendingRangeUsesPerlDiagnostic() {
        byte[] bytes = "[b-a]".getBytes(StandardCharsets.UTF_8);
        SyntaxException error = assertThrows(SyntaxException.class,
                () -> new Regex(bytes, 0, bytes.length, Option.NONE,
                        UTF8Encoding.INSTANCE, Syntax.PerlNG, WarnCallback.NONE));
        assertEquals("Invalid [] range", error.getMessage());
    }

    @Test
    public void unknownPosixClassNamesUsePerlDiagnostic() {
        assertPosixError("[[:foo:]]", "POSIX class [:foo:] unknown");
        assertPosixError("[[:^foo:]]", "POSIX class [:^foo:] unknown");
    }

    private static void assertPosixError(String pattern, String message) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        SyntaxException error = assertThrows(SyntaxException.class,
                () -> new Regex(bytes, 0, bytes.length, Option.NONE,
                        UTF8Encoding.INSTANCE, Syntax.PerlNG, WarnCallback.NONE));
        assertEquals(message, error.getMessage());
    }
}
