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

import org.jcodings.specific.ISO8859_1Encoding;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.junit.Test;

public class TestPerlBytePosixClass {
    private static int search(String pattern, int input) {
        byte[] patternBytes = pattern.getBytes(StandardCharsets.ISO_8859_1);
        byte[] inputBytes = {(byte) input};
        Regex regex = new Regex(patternBytes, 0, patternBytes.length,
                Option.PERL_BYTE_PATTERN, ISO8859_1Encoding.INSTANCE,
                Syntax.PerlNG);
        return regex.matcher(inputBytes).search(
                0, inputBytes.length, Option.NONE);
    }

    @Test
    public void negatedAsciiPosixClassIncludesHighBytes() {
        assertEquals(-1, search("[[:^ascii:]]", 0x7f));
        assertEquals(0, search("[[:^ascii:]]", 0x80));
        assertEquals(0, search("[[:^ascii:]]", 0xff));
    }

    @Test
    public void positiveAsciiPosixClassExcludesHighBytes() {
        assertEquals(0, search("[[:ascii:]]", 0x7f));
        assertEquals(-1, search("[[:ascii:]]", 0x80));
        assertEquals(-1, search("[[:ascii:]]", 0xff));
    }
}
