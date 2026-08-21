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
import java.util.Arrays;
import java.util.List;

import org.jcodings.specific.UTF8Encoding;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.joni.WarnCallback;
import org.junit.Test;

public class TestPerlBoundaryPolicy {
    private record Warning(String message, int position) {}

    private static List<Warning> warnings(String pattern, int option) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        List<Warning> warnings = new ArrayList<>();
        new Regex(bytes, 0, bytes.length, option, UTF8Encoding.INSTANCE,
                Syntax.PerlNG, new WarnCallback() {
                    @Override
                    public void warn(String message) {
                        warnings.add(new Warning(message, -1));
                    }

                    @Override
                    public void warn(String message, int bytePosition) {
                        warnings.add(new Warning(message, bytePosition));
                    }

                    @Override
                    public boolean supportsPositions() {
                        return true;
                    }
                });
        return warnings;
    }

    @Test
    public void acceptsTheShortGraphemeBoundaryAlias() {
        assertEquals(List.of(), warnings("\\b{g}", Option.NONE));
    }

    @Test
    public void warnsWhenUnicodeBoundariesOverrideAsciiMode() {
        assertEquals(Arrays.asList(new Warning(
                "Using /u for '\\b{g}' instead of /a", 5)),
                warnings("\\b{g}", Option.ASCII_RANGE | Option.PERL_EXPLICIT_ASCII));
        assertEquals(Arrays.asList(new Warning(
                "Using /u for '\\B{gcb}' instead of /a", 7)),
                warnings("\\B{gcb}", Option.ASCII_RANGE | Option.PERL_EXPLICIT_ASCII));
    }
}
