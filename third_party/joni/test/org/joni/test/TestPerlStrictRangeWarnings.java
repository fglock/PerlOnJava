/*
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to
 * deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or
 * sell copies of the Software, and to permit persons to whom the Software is
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
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.joni.test;

import static org.joni.constants.SyntaxProperties.ALLOW_MULTIPLEX_DEFINITION_NAME_CALL;
import static org.joni.constants.SyntaxProperties.OP2_OPTION_PERL;
import static org.joni.constants.SyntaxProperties.OP2_OPTION_RUBY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.jcodings.specific.UTF8Encoding;
import org.joni.NamedCharacterResolver;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.joni.WarnCallback;
import org.joni.exception.SyntaxException;
import org.junit.Test;

public class TestPerlStrictRangeWarnings {
    private record Warning(String message, int position) {}

    private static final NamedCharacterResolver NAMED =
            (bytes, p, end, encoding) -> 0;

    private static final Syntax SYNTAX = new Syntax(
            "PERLONJAVA", Syntax.RUBY.op,
            (Syntax.RUBY.op2 & ~OP2_OPTION_RUBY) | OP2_OPTION_PERL,
            Syntax.RUBY.op3,
            Syntax.RUBY.behavior | ALLOW_MULTIPLEX_DEFINITION_NAME_CALL,
            Syntax.RUBY.options & ~(Option.ASCII_RANGE
                    | Option.POSIX_BRACKET_ALL_RANGE | Option.WORD_BOUND_ALL_RANGE),
            Syntax.RUBY.metaCharTable, NAMED);

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
                SYNTAX, callback);
        return warnings;
    }

    private static int endpointPosition(String pattern) {
        return pattern.substring(0, pattern.indexOf(" ]"))
                .getBytes(StandardCharsets.UTF_8).length + 1;
    }

    private static void assertFalseRange(String pattern, String diagnostic,
                                         int position) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        try {
            new Regex(bytes, 0, bytes.length, Option.NONE, UTF8Encoding.INSTANCE,
                    SYNTAX, WarnCallback.NONE);
            fail("expected false range error for " + pattern);
        } catch (SyntaxException error) {
            assertEquals("False [] range", error.getMessage());
            assertEquals(diagnostic, error.getDiagnosticMessage());
            assertEquals(position, error.getPatternPosition());
        }
    }

    @Test
    public void warnsForStrictAsciiAndDigitRanges() {
        String ascii = "(?[ [ A - a ] ])";
        assertEquals(List.of(new Warning(
                "Ranges of ASCII printables should be some subset of \"0-9\", "
                        + "\"A-Z\", or \"a-z\"", endpointPosition(ascii))),
                warnings(ascii));

        String digits = "(?[ [ \u1a89 - \u1a90 ] ])";
        assertEquals(List.of(new Warning(
                "Ranges of digits should be from the same group of 10",
                endpointPosition(digits))), warnings(digits));
    }

    @Test
    public void warnsForEquivalentDifferentlySpelledEndpoints() {
        String colon = "(?[ [ : - \\x3A ] ])";
        assertEquals(List.of(new Warning(
                "\": - \\x3A \" is more clearly written simply as \":\"",
                endpointPosition(colon))), warnings(colon));

        String tab = "(?[ [ \\t - \\x09 ] ])";
        assertEquals(List.of(new Warning(
                "\"\\t - \\x09 \" is more clearly written simply as \"\\t\"",
                endpointPosition(tab))), warnings(tab));
    }

    @Test
    public void preservesNamedEndpointProvenance() {
        String mixed = "(?[ [ \\N{ZERO} - \\x01 ] ])";
        assertEquals(List.of(new Warning(
                "Both or neither range ends should be Unicode",
                endpointPosition(mixed))), warnings(mixed));
    }

    @Test
    public void rejectsCharacterClassRangeEndpointsInExtendedClasses() {
        String rightType = "(?[[a-\\d]])";
        assertFalseRange(rightType, "False [] range \"a-\\d\"",
                rightType.indexOf("\\d") + 2);

        String leftType = "(?[[\\w-x]])";
        assertFalseRange(leftType, "False [] range \"\\w-\"",
                leftType.indexOf('-') + 1);

        String rightProperty = "(?[[a-\\pM]])";
        assertFalseRange(rightProperty, "False [] range \"a-\\pM\"",
                rightProperty.indexOf("\\pM") + 3);

        String leftProperty = "(?[[\\pM-x]])";
        assertFalseRange(leftProperty, "False [] range \"\\pM-\"",
                leftProperty.indexOf('-') + 1);
    }

    @Test
    public void acceptsCanonicalRangesWithoutWarnings() {
        assertEquals(List.of(), warnings("(?[ [ A-Z ] ])"));
        assertEquals(List.of(), warnings("(?[ [ % - % ] ])"));
        assertEquals(List.of(), warnings("(?[ [ \u1a80-\u1a89 ] ])"));
    }
}
