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

public class TestPerlScopedPropertyPolicy {
    private static final CharacterPropertyResolver FOLD_POLICY_RESOLVER =
            (bytes, p, end, encoding, inCharacterClass) -> {
                String name = new String(bytes, p, end - p, StandardCharsets.UTF_8);
                return switch (name) {
                    case "Fold" -> new CharacterPropertyResolver.Result(
                            new int[] {1, 'B', 'B'}, true);
                    case "NoFold" -> new CharacterPropertyResolver.Result(
                            new int[] {1, 'A', 'A'}, false);
                    default -> null;
                };
            };

    private static Syntax syntax(CharacterPropertyResolver resolver) {
        return new Syntax("PerlScopedPropertyPolicy", Syntax.PerlNG.op,
                Syntax.PerlNG.op2, Syntax.PerlNG.op3, Syntax.PerlNG.behavior,
                Syntax.PerlNG.options, Syntax.PerlNG.metaCharTable, null, resolver);
    }

    private static Regex compile(String pattern, CharacterPropertyResolver resolver) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        return new Regex(bytes, 0, bytes.length, Option.NONE,
                UTF8Encoding.INSTANCE, syntax(resolver));
    }

    private static int search(String pattern, String input) {
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        return compile(pattern, FOLD_POLICY_RESOLVER).matcher(bytes)
                .search(0, bytes.length, Option.NONE);
    }

    @Test
    public void exposesLexicalOptionsToThePropertyResolver() {
        List<Boolean> modes = new ArrayList<>();
        CharacterPropertyResolver resolver = new CharacterPropertyResolver() {
            @Override
            public Result resolve(byte[] bytes, int p, int end, Encoding encoding,
                                  boolean inCharacterClass) {
                return null;
            }

            @Override
            public Result resolve(byte[] bytes, int p, int end, Encoding encoding,
                                  boolean inCharacterClass, int option) {
                boolean ignoreCase = Option.isIgnoreCase(option);
                modes.add(ignoreCase);
                int member = ignoreCase ? 'B' : 'A';
                return new Result(new int[] {1, member, member}, false);
            }
        };
        Regex regex = compile("(?i:\\p{Mode})(?-i:\\p{Mode})", resolver);
        byte[] input = "BA".getBytes(StandardCharsets.UTF_8);
        assertEquals(0, regex.matcher(input).search(0, input.length, Option.NONE));
        assertEquals(List.of(true, false), modes);
    }

    @Test
    public void preservesFoldPolicyInAStandardLeafOfAnExtendedClass() {
        String pattern = "(?i)(?[[\\p{NoFold}B]])";
        assertEquals(0, search(pattern, "A"));
        assertEquals(-1, search(pattern, "a"));
        assertEquals(0, search(pattern, "B"));
        assertEquals(0, search(pattern, "b"));
    }
}
