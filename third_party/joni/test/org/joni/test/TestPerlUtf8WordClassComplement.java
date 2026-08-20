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
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.junit.Test;

public class TestPerlUtf8WordClassComplement {
    private static int search(String pattern, String input) {
        byte[] patternBytes = pattern.getBytes(StandardCharsets.UTF_8);
        byte[] inputBytes = input.getBytes(StandardCharsets.UTF_8);
        Regex regex = new Regex(patternBytes, 0, patternBytes.length,
                Option.NONE, UTF8Encoding.INSTANCE, Syntax.TEST);
        return regex.matcher(inputBytes).search(0, inputBytes.length, Option.NONE);
    }

    private static int searchByte(String pattern, int input) {
        byte[] patternBytes = pattern.getBytes(StandardCharsets.ISO_8859_1);
        byte[] inputBytes = {(byte) input};
        Regex regex = new Regex(patternBytes, 0, patternBytes.length,
                Option.ASCII_RANGE, ISO8859_1Encoding.INSTANCE, Syntax.TEST);
        return regex.matcher(inputBytes).search(0, inputBytes.length, Option.NONE);
    }

    @Test
    public void latin1SupplementBelongsToExactlyOneWordClass() {
        for (int codePoint = 0x80; codePoint <= 0xff; codePoint++) {
            String character = new String(Character.toChars(codePoint));
            int word = search("[\\w]", character);
            int notWord = search("[\\W]", character);
            assertEquals("U+" + Integer.toHexString(codePoint),
                    word < 0 ? 0 : 1, notWord < 0 ? 1 : 0);
        }
    }

    @Test
    public void wordClassesRemainComplementaryAcrossEncodingBoundaries() {
        for (int codePoint : new int[] {0x7f, 0x80, 0xaa, 0xff, 0x100, 0x200c}) {
            String character = new String(Character.toChars(codePoint));
            int word = search("[\\w]", character);
            int notWord = search("[\\W]", character);
            assertEquals("U+" + Integer.toHexString(codePoint),
                    word < 0 ? 0 : 1, notWord < 0 ? 1 : 0);
        }
    }

    @Test
    public void asciiRestrictedByteClassComplementIncludesHighBytes() {
        for (int value = 0x80; value <= 0xff; value++) {
            assertEquals("byte " + Integer.toHexString(value),
                    0, searchByte("[\\W]", value));
        }
    }
}
