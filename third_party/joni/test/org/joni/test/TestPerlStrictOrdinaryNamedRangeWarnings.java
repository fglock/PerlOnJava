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
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.joni.test;

import static org.joni.constants.SyntaxProperties.ALLOW_MULTIPLEX_DEFINITION_NAME_CALL;
import static org.joni.constants.SyntaxProperties.OP2_OPTION_PERL;
import static org.joni.constants.SyntaxProperties.OP2_OPTION_RUBY;
import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.jcodings.specific.UTF8Encoding;
import org.joni.NamedCharacterResolver;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.joni.WarnCallback;
import org.junit.Test;

public class TestPerlStrictOrdinaryNamedRangeWarnings {
    private record Warning(String message, int position) {}

    private static final NamedCharacterResolver NAMED =
            (bytes, p, end, encoding) -> {
                String name = new String(bytes, p, end - p, StandardCharsets.US_ASCII);
                return name.startsWith("U+")
                        ? Integer.parseInt(name.substring(2), 16) : 0;
            };

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
        new Regex(bytes, 0, bytes.length, Option.PERL_RE_STRICT,
                UTF8Encoding.INSTANCE, SYNTAX, callback);
        return warnings;
    }

    @Test
    public void warnsForMixedNamedEndpointsInStrictOrdinaryClasses() {
        for (String pattern : List.of(
                "[\\N{U+00}-\\x01]",
                "[\\x00-\\N{U+01}]",
                "[\\N{U+7F}-\\o{377}]",
                "[\\o{0}-\\N{U+01}]",
                "[\\000-\\N{U+01}]",
                "[\\N{U+7F}-\\377]")) {
            assertEquals(List.of(new Warning(
                    "Both or neither range ends should be Unicode",
                    pattern.length() - 1)), warnings(pattern));
        }

        for (String pattern : List.of(
                "[\\N{U+00}-\\a]",
                "[\\a-\\N{U+FF}]",
                "[\\N{U+100}-\\x{101}]")) {
            assertEquals(List.of(), warnings(pattern));
        }
    }
}
