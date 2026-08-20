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

import org.jcodings.specific.ISO8859_1Encoding;
import org.jcodings.specific.UTF8Encoding;
import org.joni.CharacterPropertyResolver;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.junit.Test;

public class TestPerlByteClassFoldPolicy {
    private static final int[] LOWERCASE_RANGES = {
        3, 'a', 'z', 0xdf, 0xdf, 0xe0, 0xe0
    };
    private static final int[] UPPERCASE_RANGES = {
        2, 'A', 'Z', 0xc0, 0xc0
    };
    private static final int[] HEX_RANGES = {
        3, '0', '9', 'A', 'F', 'a', 'f'
    };

    private static final CharacterPropertyResolver PROPERTY_RESOLVER =
            (bytes, p, end, encoding, inCharacterClass) -> {
                String name = new String(bytes, p, end - p,
                        StandardCharsets.ISO_8859_1);
                if ("Lowercase".equals(name)) {
                    return new CharacterPropertyResolver.Result(
                            LOWERCASE_RANGES, true);
                }
                if ("Uppercase".equals(name)) {
                    return new CharacterPropertyResolver.Result(
                            UPPERCASE_RANGES, true);
                }
                if ("Hex_Digit".equals(name)) {
                    return new CharacterPropertyResolver.Result(
                            HEX_RANGES, true);
                }
                return null;
            };

    private static final Syntax PERL_SYNTAX = new Syntax(
            "PerlByteClassFoldPolicy", Syntax.PerlNG.op, Syntax.PerlNG.op2,
            Syntax.PerlNG.op3, Syntax.PerlNG.behavior, Syntax.PerlNG.options,
            Syntax.PerlNG.metaCharTable, null, PROPERTY_RESOLVER);

    private static int searchByte(String pattern, String input) {
        byte[] patternBytes = pattern.getBytes(StandardCharsets.ISO_8859_1);
        byte[] inputBytes = input.getBytes(StandardCharsets.ISO_8859_1);
        Regex regex = new Regex(patternBytes, 0, patternBytes.length,
                Option.IGNORECASE | Option.PERL_BYTE_PATTERN,
                ISO8859_1Encoding.INSTANCE, PERL_SYNTAX);
        return regex.matcher(inputBytes).search(0, inputBytes.length, Option.NONE);
    }

    private static int searchUnicode(String pattern, String input) {
        byte[] patternBytes = pattern.getBytes(StandardCharsets.UTF_8);
        byte[] inputBytes = input.getBytes(StandardCharsets.UTF_8);
        Regex regex = new Regex(patternBytes, 0, patternBytes.length,
                Option.IGNORECASE, UTF8Encoding.INSTANCE, PERL_SYNTAX);
        return regex.matcher(inputBytes).search(0, inputBytes.length, Option.NONE);
    }

    @Test
    public void byteClassesFoldAsciiButNotLatinOne() {
        assertEquals(-1, searchByte("^[\u00e0_]$", "\u00c0"));
        assertEquals(0, searchByte("^[^\u00e0]$", "\u00c0"));
        assertEquals(0, searchByte("^[a_]$", "A"));
        assertEquals(-1, searchByte("^[\u00df_]$", "ss"));
        assertEquals(0, searchByte("^\\p{Lowercase}$", "\u00c0"));
        assertEquals(0, searchByte("^[\\p{Lowercase}_]$", "\u00c0"));
        assertEquals(-1, searchByte("^[^\\p{Lowercase}]$", "\u00c0"));
        assertEquals(-1, searchByte("^\\P{Lowercase}$", "\u00c0"));
        assertEquals(-1, searchByte("^[\\P{Lowercase}_]$", "\u00c0"));
        assertEquals(0, searchByte("^\\p{Lowercase}$", "ss"));
        assertEquals(0, searchByte(
                "^(?[ \\p{Lowercase} & [\u00e0] ])$", "\u00c0"));
        assertEquals(0, searchByte(
                "^(?[ \\p{Lowercase} - [a] ])$", "\u00c0"));
        assertEquals(-1, searchByte(
                "^(?[ [\u00e0] - \\p{Uppercase} ])$", "\u00c0"));
        assertEquals(0, searchByte(
                "^(?[ ([\u00e0] + \\p{Hex_Digit})"
                        + " - \\p{Hex_Digit} ])$", "\u00e0"));
        assertEquals(0, searchByte(
                "^(?[ ([\u00e0] + \\p{Hex_Digit})"
                        + " - \\p{Hex_Digit} ])$", "\u00c0"));
    }

    @Test
    public void unicodeClassesRetainLatinOneAndAboveByteFolds() {
        assertEquals(0, searchUnicode("^[\u00e0_]$", "\u00c0"));
        assertEquals(-1, searchUnicode("^[^\u00e0]$", "\u00c0"));
        assertEquals(0, searchUnicode("^[\u00df_]$", "ss"));
        assertEquals(0, searchUnicode("^[\u0178_]$", "\u00ff"));
        assertEquals(0, searchUnicode("^[\u212a_]$", "K"));
    }
}
