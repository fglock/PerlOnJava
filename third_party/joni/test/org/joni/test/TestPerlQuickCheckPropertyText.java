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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.jcodings.Encoding;
import org.jcodings.specific.UTF8Encoding;
import org.joni.CharacterPropertyResolver;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.junit.Test;

public class TestPerlQuickCheckPropertyText {
    @Test
    public void preservesBareAndAssignedPropertyTextForTheResolver() {
        List<String> properties = new ArrayList<>();
        CharacterPropertyResolver resolver = new CharacterPropertyResolver() {
            @Override
            public Result resolve(byte[] bytes, int p, int end, Encoding encoding,
                                  boolean inCharacterClass) {
                return null;
            }

            @Override
            public Result resolve(byte[] bytes, int p, int end, Encoding encoding,
                                  Context context, int option) {
                properties.add(new String(bytes, p, end - p, StandardCharsets.UTF_8));
                return new Result(new int[] {1, 'A', 'A'}, false);
            }
        };

        compile("\\p{NFD_Quick_Check}", resolver);
        compile("\\p{NFD_Quick_Check=No}", resolver);

        assertEquals(List.of("NFD_Quick_Check", "NFD_Quick_Check=No"), properties);
    }

    private static void compile(String pattern, CharacterPropertyResolver resolver) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        Syntax syntax = new Syntax("PerlQuickCheckPropertyText", Syntax.PerlNG.op,
                Syntax.PerlNG.op2, Syntax.PerlNG.op3, Syntax.PerlNG.behavior,
                Syntax.PerlNG.options, Syntax.PerlNG.metaCharTable, null, resolver);
        new Regex(bytes, 0, bytes.length, Option.NONE, UTF8Encoding.INSTANCE, syntax);
    }
}
