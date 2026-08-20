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
package org.joni;

import static org.joni.constants.SyntaxProperties.ALLOW_MULTIPLEX_DEFINITION_NAME_CALL;
import static org.joni.constants.SyntaxProperties.OP2_OPTION_PERL;
import static org.joni.constants.SyntaxProperties.OP2_OPTION_RUBY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.UTF8Encoding;
import org.joni.exception.SyntaxException;
import org.junit.Test;

public class TestPerlCalledGroupLookBehind {
    private static final Syntax PERLONJAVA = new Syntax(
            "PERLONJAVA", Syntax.RUBY.op,
            (Syntax.RUBY.op2 & ~OP2_OPTION_RUBY) | OP2_OPTION_PERL,
            Syntax.RUBY.op3,
            Syntax.RUBY.behavior | ALLOW_MULTIPLEX_DEFINITION_NAME_CALL,
            Syntax.RUBY.options, Syntax.RUBY.metaCharTable);

    private static Matcher matcher(String pattern, String input) {
        byte[] source = pattern.getBytes(StandardCharsets.UTF_8);
        Regex regex = new Regex(source, 0, source.length, Option.NONE,
                UTF8Encoding.INSTANCE, PERLONJAVA);
        return regex.matcher(input.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void finiteCalledGroupContributesItsLookbehindWidth() {
        Matcher repeated = matcher(
                "(?<W>a)(?<BB>(?=\\g<W>)(?<=\\g<W>))\\g<BB>", "aa");
        assertEquals(0, repeated.search(0, 2, Option.NONE));
        assertEquals(0, repeated.getBegin());
        assertEquals(1, repeated.getEnd());

        Matcher defined = matcher(
                "(?(DEFINE)(?<x>a\\K))(?<=\\g<x>)", "a");
        assertEquals(1, defined.search(0, 1, Option.NONE));
        assertEquals(1, defined.getBegin());
        assertEquals(1, defined.getEnd());
    }

    @Test
    public void calledGroupWorksInsideNegativeLookbehind() {
        assertEquals(0, matcher(
                "(?(DEFINE)(?<x>a\\K))(?<!\\g<x>)", "a")
                .search(0, 1, Option.NONE));
        assertEquals(0, matcher(
                "(?(DEFINE)(?<x>a\\K))(?<!\\g<x>)", "b")
                .search(0, 1, Option.NONE));
    }

    @Test
    public void trulyRecursiveWidthRemainsRejected() {
        assertThrows(SyntaxException.class, () -> matcher(
                "(?(DEFINE)(?<x>a\\g<x>?))(?<=\\g<x>)", "a"));
    }
}
