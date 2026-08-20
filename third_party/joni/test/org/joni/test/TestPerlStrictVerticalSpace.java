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

public class TestPerlStrictVerticalSpace {
    private static void compile(String pattern, int option) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        new Regex(bytes, 0, bytes.length, option, UTF8Encoding.INSTANCE,
                Syntax.PerlNG, WarnCallback.NONE);
    }

    @Test
    public void rejectsLiteralVerticalSpaceOutsideExtendedMoreClasses() {
        SyntaxException error = assertThrows(SyntaxException.class,
                () -> compile("[a\fb]", Option.PERL_RE_STRICT));
        assertEquals("Literal vertical space in [] is illegal except under /x",
                error.getMessage());
        assertEquals(3, error.getPatternPosition());
    }

    @Test
    public void permitsLiteralVerticalSpaceUnderExtendedMore() {
        compile("[a\fb]", Option.PERL_RE_STRICT
                | Option.EXTEND | Option.PERL_EXTEND_MORE);
    }
}
