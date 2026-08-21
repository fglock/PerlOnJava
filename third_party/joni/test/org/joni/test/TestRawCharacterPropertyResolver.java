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
import static org.junit.Assert.assertTrue;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.jcodings.Encoding;
import org.jcodings.specific.ISO8859_1Encoding;
import org.jcodings.specific.UTF8Encoding;
import org.joni.CharacterPropertyResolver;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.junit.Test;

public class TestRawCharacterPropertyResolver {
    private static Syntax syntax(CharacterPropertyResolver resolver) {
        return new Syntax("RawCharacterPropertyResolver", Syntax.PerlNG.op,
                Syntax.PerlNG.op2, Syntax.PerlNG.op3, Syntax.PerlNG.behavior,
                Syntax.PerlNG.options, Syntax.PerlNG.metaCharTable, null, resolver);
    }

    private static int search(String pattern, String input, Encoding encoding,
                              Charset charset, int option,
                              CharacterPropertyResolver resolver) {
        byte[] patternBytes = pattern.getBytes(charset);
        byte[] inputBytes = input.getBytes(charset);
        Regex regex = new Regex(patternBytes, 0, patternBytes.length, option,
                encoding, syntax(resolver));
        return regex.matcher(inputBytes).search(0, inputBytes.length, Option.NONE);
    }

    @Test
    public void exposesRawTokensContextsOptionsAndEncodings() {
        List<String> calls = new ArrayList<>();
        CharacterPropertyResolver resolver = new CharacterPropertyResolver() {
            @Override
            public Result resolve(byte[] bytes, int p, int end, Encoding encoding,
                                  boolean inCharacterClass) {
                return null;
            }

            @Override
            public Result resolve(byte[] bytes, int p, int end, Encoding encoding,
                                  boolean inCharacterClass, int option) {
                Charset charset = encoding == ISO8859_1Encoding.INSTANCE
                        ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8;
                String token = new String(bytes, p, end - p, charset);
                calls.add(token + "|" + encoding.getClass().getSimpleName()
                        + "|" + inCharacterClass + "|" + Option.isIgnoreCase(option));
                int member = switch (token) {
                    case "Script = Greek" -> 'A';
                    case "Script_Extensions=Hira" -> 'B';
                    case "Block:Greek" -> 'C';
                    case "Age=1.1" -> 'D';
                    case "Present_In=2.0" -> 'E';
                    case "PerlWord" -> 'F';
                    default -> -1;
                };
                return member < 0 ? null
                        : new Result(new int[] {1, member, member}, true);
            }
        };

        assertEquals(0, search("\\p{Script = Greek}", "A", UTF8Encoding.INSTANCE,
                StandardCharsets.UTF_8, Option.NONE, resolver));
        assertEquals(0, search("[\\p{Script_Extensions=Hira}]", "B",
                UTF8Encoding.INSTANCE, StandardCharsets.UTF_8, Option.NONE, resolver));
        assertEquals(0, search("(?[ \\p{Block:Greek} | \\p{Age=1.1} ])", "D",
                UTF8Encoding.INSTANCE, StandardCharsets.UTF_8, Option.NONE, resolver));
        assertEquals(0, search("(?i)\\p{Present_In=2.0}", "e",
                UTF8Encoding.INSTANCE, StandardCharsets.UTF_8, Option.NONE, resolver));
        assertEquals(0, search("\\p{PerlWord}", "F", ISO8859_1Encoding.INSTANCE,
                StandardCharsets.ISO_8859_1, Option.PERL_BYTE_PATTERN, resolver));

        assertTrue(calls.contains("Script = Greek|UTF8Encoding|false|false"));
        assertTrue(calls.contains("Script_Extensions=Hira|UTF8Encoding|true|false"));
        assertTrue(calls.contains("Block:Greek|UTF8Encoding|false|false"));
        assertTrue(calls.contains("Age=1.1|UTF8Encoding|false|false"));
        assertTrue(calls.contains("Present_In=2.0|UTF8Encoding|false|true"));
        assertTrue(calls.contains("PerlWord|ISO8859_1Encoding|false|false"));
    }
}
