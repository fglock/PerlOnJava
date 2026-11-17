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
package org.joni;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.UTF8Encoding;
import org.junit.Test;

public class TestWideScalarClassRepeat {
    private static final CharacterPropertyResolver RESOLVER =
            (bytes, p, end, encoding, inCharacterClass) -> {
                String name = new String(bytes, p, end - p, StandardCharsets.UTF_8);
                return name.equals("FullRange")
                        ? new CharacterPropertyResolver.Result(
                                new int[] {1, 0, 0x10ffff},
                                new long[] {1, 0x110000L, Long.MAX_VALUE}, false)
                        : null;
            };
    private static final Syntax SYNTAX = new Syntax(
            "WideScalarClassRepeat", Syntax.PerlNG.op, Syntax.PerlNG.op2,
            Syntax.PerlNG.op3, Syntax.PerlNG.behavior, Syntax.PerlNG.options,
            Syntax.PerlNG.metaCharTable, null, RESOLVER, null);

    private static Regex compile(String source) {
        return compile(source, Syntax.Perl);
    }

    private static Regex compile(String source, Syntax syntax) {
        byte[] pattern = source.getBytes(StandardCharsets.UTF_8);
        return new Regex(pattern, 0, pattern.length, Option.NONE,
                UTF8Encoding.INSTANCE, syntax);
    }

    @Test
    public void repeatedExplicitFullRangeClassIncludesNewline() {
        byte[] subject = "a\nb".getBytes(StandardCharsets.UTF_8);
        Matcher matcher = compile("\\A\\p{FullRange}*\\z", SYNTAX)
                .matcher(subject);
        assertEquals(subject.length,
                matcher.match(0, subject.length, Option.NONE));
    }

    @Test
    public void repeatedUnicodeRangeClassRemainsAControl() {
        byte[] subject = "a\nb".getBytes(StandardCharsets.UTF_8);
        Matcher matcher = compile("\\A[\\x{0}-\\x{10ffff}]*\\z")
                .matcher(subject);
        assertEquals(subject.length,
                matcher.match(0, subject.length, Option.NONE));
    }

    @Test
    public void dotStarStillExcludesNewlineWithoutMultilineMode() {
        byte[] subject = "a\nb".getBytes(StandardCharsets.UTF_8);
        Matcher matcher = compile("\\Aa.*b\\z").matcher(subject);
        assertTrue(matcher.match(0, subject.length, Option.NONE) < 0);
    }
}
