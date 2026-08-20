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

import org.jcodings.specific.UTF8Encoding;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.joni.WarnCallback;
import org.junit.Test;

public class TestPerlWarningPositions {
    private record Warning(String message, int position) {}

    private static List<Warning> warnings(String pattern) {
        List<Warning> warnings = new ArrayList<>();
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        WarnCallback callback = new WarnCallback() {
            @Override
            public void warn(String message) {
                throw new AssertionError("warning missing position: " + message);
            }

            @Override
            public void warn(String message, int position) {
                warnings.add(new Warning(message, position));
            }

            @Override
            public boolean supportsPositions() {
                return true;
            }
        };
        new Regex(bytes, 0, bytes.length, Option.NONE, UTF8Encoding.INSTANCE,
                Syntax.PerlNG, callback);
        return warnings;
    }

    @Test
    public void locatesEachUselessInlineModifier() {
        assertEquals(List.of(
                new Warning("Useless (?o)", 3),
                new Warning("Useless (?g)", 4),
                new Warning("Useless (?c)", 5)),
                warnings("(?ogc)"));
        assertEquals(List.of(
                new Warning("Useless (?-c)", 4),
                new Warning("Useless (?-o)", 6)),
                warnings("(?-cgo)"));
    }

    @Test
    public void locatesUnboundedZeroWidthRepeats() {
        assertEquals(List.of(new Warning(
                "\\b* matches null string many times", 3)), warnings("\\b*"));
        assertEquals(List.of(new Warning(
                "(?=a)+ matches null string many times", 6)), warnings("(?=a)+"));
        assertEquals(List.of(), warnings("\\b?"));
    }

    @Test
    public void locatesUnknownAlphabeticEscapesInCharacterClasses() {
        assertEquals(List.of(new Warning(
                "Unrecognized escape \\y in character class passed through", 3)),
                warnings("[\\y]"));
        assertEquals(List.of(new Warning(
                "Unrecognized escape \\z in character class passed through", 4)),
                warnings("[a\\zb]"));
    }

    @Test
    public void locatesUnknownAlphabeticEscapesOutsideCharacterClasses() {
        assertEquals(List.of(new Warning(
                "Unrecognized escape \\y passed through", 2)), warnings("\\y"));
        assertEquals(List.of(new Warning(
                "Unrecognized escape \\q passed through", 3)), warnings("a\\q"));
    }
}
