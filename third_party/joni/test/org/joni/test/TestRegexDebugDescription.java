/*
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
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

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.ASCIIEncoding;
import org.jcodings.specific.UTF8Encoding;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.junit.Test;

public class TestRegexDebugDescription {
    @Test
    public void exposesTheActualCompiledInstructionStream() {
        byte[] pattern = "phase36_debug_native".getBytes(StandardCharsets.US_ASCII);
        Regex regex = new Regex(pattern, 0, pattern.length, Option.CAPTURE_GROUP,
                ASCIIEncoding.INSTANCE, Syntax.PerlNG);

        String description = regex.byteCodeDebugDescription();
        assertTrue(description.startsWith("code length: "));
        assertTrue(description.toLowerCase().contains("[exact"));
        assertTrue(description.toLowerCase().contains("[end]"));
    }

    @Test
    public void describesStructuredAndUtf8OperandsWithoutMetadataGaps() {
        byte[] structured = "(ab|c+)[d-f]".getBytes(StandardCharsets.US_ASCII);
        Regex structuredRegex = new Regex(structured, 0, structured.length,
                Option.CAPTURE_GROUP, ASCIIEncoding.INSTANCE, Syntax.PerlNG);
        String structuredDescription = structuredRegex.byteCodeDebugDescription();
        assertTrue(structuredDescription.contains("[end]"));
        assertTrue(structuredDescription.contains("[push"));

        String unicodeLiteral = "åβ";
        byte[] utf8 = unicodeLiteral.getBytes(StandardCharsets.UTF_8);
        Regex utf8Regex = new Regex(utf8, 0, utf8.length, Option.CAPTURE_GROUP,
                UTF8Encoding.INSTANCE, Syntax.PerlNG);
        assertTrue(utf8Regex.byteCodeDebugDescription().contains(unicodeLiteral));
    }
}
