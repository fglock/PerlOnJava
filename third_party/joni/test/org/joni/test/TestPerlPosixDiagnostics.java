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

public class TestPerlPosixDiagnostics {
    private record Warning(String message, int position) {}

    private static List<Warning> compile(String pattern) {
        return compile(pattern, Option.NONE);
    }

    private static List<Warning> compile(String pattern, int options) {
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
        new Regex(bytes, 0, bytes.length, options, UTF8Encoding.INSTANCE,
                Syntax.PerlNG, callback);
        return warnings;
    }

    @Test
    public void warnsForPosixSyntaxOutsideACharacterClass() {
        assertEquals(List.of(new Warning(
                "POSIX syntax [: :] belongs inside character classes", 9)),
                compile("[:alpha:]"));
        assertEquals(List.of(new Warning(
                "POSIX syntax [: :] belongs inside character classes "
                        + "(but this one isn't fully valid)", 7)),
                compile("[:zog:]"));
        assertEquals(List.of(new Warning(
                "POSIX syntax [: :] belongs inside character classes "
                        + "(but this one isn't fully valid)", 7)),
                compile("[:blank]"));
        assertEquals(List.of(new Warning(
                "POSIX syntax [. .] belongs inside character classes "
                        + "(but this one isn't implemented)", 7)),
                compile("[.zog.]"));
    }

    @Test
    public void ordersNearMissWarningsAtPerlBytePositions() {
        assertEquals(List.of(new Warning(
                "Assuming NOT a POSIX class since there is no terminating ':'", 8)),
                compile("[[:digit]]"));
        assertEquals(List.of(new Warning(
                "Assuming NOT a POSIX class since there is no terminating ':'", 8)),
                compile("[[:ascii]]"));
        assertEquals(List.of(new Warning(
                "Assuming NOT a POSIX class since there is no terminating ']'", 9)),
                compile("[[:digit:foo]"));
        assertEquals(List.of(
                new Warning("Assuming NOT a POSIX class since the name must be all lowercase letters", 8),
                new Warning("Assuming NOT a POSIX class since there is no terminating ':'", 8)),
                compile("[[:DIGIT]]"));
        assertEquals(List.of(
                new Warning("Assuming NOT a POSIX class since the '^' must come after the colon", 3),
                new Warning("Assuming NOT a POSIX class since there must be a starting ':'", 3),
                new Warning("Assuming NOT a POSIX class since there is no terminating ':'", 7)),
                compile("[[^word]"));
    }

    @Test
    public void recoversMalformedAndWidePosixCandidatesAsLiteralClassText() {
        assertEquals(1, compile("[[:digit:foo]").size());
        assertEquals(1, compile("(?[[:word]])").size());
        assertEquals(List.of(), compile("(?[[:w:]])"));
        assertEquals(List.of(), compile("ネ[[:ネ:]]ネ"));
        assertEquals(List.of(), compile("ネ(?[[:ネ:]])ネ"));
        assertEquals(List.of(), compile("[[:digit:]]"));
        assertEquals(List.of(), compile("(?[[:digit:]])"));
        assertEquals(false, compile("[a-[:digit:]]").stream()
                .anyMatch(warning -> warning.message().startsWith("POSIX syntax")));
    }

    @Test
    public void diagnosesMissingDelimitersInSourceOrder() {
        assertEquals(List.of(new Warning(
                "Assuming NOT a POSIX class since it doesn't start with a '['", 4)),
                compile("[foo:lower:]]"));
        assertEquals(List.of(
                new Warning("Assuming NOT a POSIX class since a semi-colon was found instead of a colon", 3),
                new Warning("Assuming NOT a POSIX class since a semi-colon was found instead of a colon", 10)),
                compile("[[;upper;]]"));
        String spaced = "[[   ^   :   x d i g i t   :   ]   ]";
        assertEquals(List.of(
                new Warning("Assuming NOT a POSIX class since no blanks are allowed in one", 5),
                new Warning("Assuming NOT a POSIX class since the '^' must come after the colon", 6),
                new Warning("Assuming NOT a POSIX class since no blanks are allowed in one", 9),
                new Warning("Assuming NOT a POSIX class since no blanks are allowed in one", 13),
                new Warning("Assuming NOT a POSIX class since no blanks are allowed in one", 32)),
                compile(spaced));
    }
}
