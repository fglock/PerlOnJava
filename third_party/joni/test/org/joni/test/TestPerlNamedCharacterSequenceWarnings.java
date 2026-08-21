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
package org.joni.test;

import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.jcodings.Encoding;
import org.jcodings.specific.UTF8Encoding;
import org.joni.NamedCharacterResolver;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.joni.WarnCallback;
import org.junit.Test;

public class TestPerlNamedCharacterSequenceWarnings {
    private static final String MESSAGE =
            "Using just the first character returned by \\N{} in character class";

    private static final NamedCharacterResolver RESOLVER =
            new NamedCharacterResolver() {
                @Override
                public int resolve(byte[] bytes, int p, int end, Encoding encoding) {
                    throw new AssertionError("sequence resolver expected");
                }

                @Override
                public int[] resolveSequence(
                        byte[] bytes, int p, int end, Encoding encoding) {
                    return new int[] {0x100, 0x300};
                }
            };

    private static final Syntax SYNTAX = new Syntax(
            "PerlNamedCharacterSequenceWarnings", Syntax.PerlNG.op,
            Syntax.PerlNG.op2, Syntax.PerlNG.op3, Syntax.PerlNG.behavior,
            Syntax.PerlNG.options, Syntax.PerlNG.metaCharTable, RESOLVER);

    private static List<String> warnings(String pattern) {
        List<String> warnings = new ArrayList<>();
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        WarnCallback callback = new WarnCallback() {
            @Override
            public void warn(String message) {
                warnings.add(message);
            }

            @Override
            public void warn(String message, int position) {
                warnings.add(message);
            }

            @Override
            public boolean supportsPositions() {
                return true;
            }
        };
        new Regex(bytes, 0, bytes.length, Option.NONE,
                UTF8Encoding.INSTANCE, SYNTAX, callback);
        return warnings;
    }

    @Test
    public void warnsWhenNamedSequenceIsSingleCharacterContext() {
        assertEquals(List.of(MESSAGE), warnings("[^\\N{PAIR}]"));
        assertEquals(List.of(MESSAGE), warnings("[\\x03-\\N{PAIR}]"));
        assertEquals(List.of(MESSAGE), warnings("[\\N{PAIR}-\\x{10ffff}]"));
    }

    @Test
    public void keepsOrdinaryPositiveSequenceClassWarningFree() {
        assertEquals(List.of(), warnings("[\\N{PAIR}]"));
    }
}
