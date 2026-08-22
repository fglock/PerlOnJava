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
import static org.junit.Assert.assertNotEquals;

import java.nio.charset.StandardCharsets;

import org.jcodings.Encoding;
import org.jcodings.specific.UTF8Encoding;
import org.joni.CharacterPropertyResolver;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.junit.Test;

public class TestRegexPosixProvenance {
    private static final CharacterPropertyResolver RESOLVER =
            new CharacterPropertyResolver() {
                @Override
                public Result resolve(byte[] bytes, int p, int end,
                        Encoding encoding, boolean inCharacterClass) {
                    return null;
                }

                @Override
                public boolean hasAuthoritativePerlClassSemantics() {
                    return true;
                }
            };

    private static final Syntax SYNTAX = new Syntax(
            "RegexPosixProvenance", Syntax.PerlNG.op, Syntax.PerlNG.op2,
            Syntax.PerlNG.op3, Syntax.PerlNG.behavior, Syntax.PerlNG.options,
            Syntax.PerlNG.metaCharTable, null, RESOLVER, null);

    @Test
    public void retainsEqualMembershipWithDifferentNormalizedProvenance() {
        Regex posix = compile("[[:digit:]]");
        Regex escape = compile("[\\d]");
        assertEquals(posix.firstDebugProgramFact().characterClass().ranges(),
                escape.firstDebugProgramFact().characterClass().ranges());
        assertNotEquals(posix.firstDebugProgramFact().characterClass()
                        .expression().terms().get(0).spelling(),
                escape.firstDebugProgramFact().characterClass()
                        .expression().terms().get(0).spelling());
    }

    @Test
    public void rendersCharsetNegationFoldAndComplementFacts() {
        assertDescription("[[:alpha:]]", "POSIXD[:alpha:]");
        assertDescription("[[:^alpha:]]", "NPOSIXD[:alpha:]");
        assertDescription("(?u)[[:alpha:]]", "POSIXU[:alpha:]");
        assertDescription("(?a)[[:alpha:]]", "POSIXA[:alpha:]");
        assertDescription("(?l)[[:alpha:]]", "POSIXL[:alpha:]");
        assertDescription("(?i)[[:lower:]]", "POSIXD[:cased:]");
        assertDescription("(?i)(?a)[[:upper:]]", "POSIXA[:alpha:]");
        assertDescription("[[:^alpha:]\\x{2c2}]", "NPOSIXU[:alpha:]");
        assertDescription("[[:alpha:][:^alpha:]]", "SANY");
        assertDescription("[^[:alpha:][:^alpha:]]", "OPFAIL");
        assertDescription("(?i)[\\d\\w]", "POSIXD[\\w]");
        assertDescription("(?i)[\\D\\w]", "SANY");
        assertDescription("(?i)(?l)[\\D\\w]",
                "ANYOFPOSIXL{i}[\\w\\D][0100-INFTY]");
    }

    private static Regex compile(String pattern) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        return new Regex(bytes, 0, bytes.length, Option.CAPTURE_GROUP,
                UTF8Encoding.INSTANCE, SYNTAX);
    }

    private static void assertDescription(String pattern, String expected) {
        Regex regex = compile(pattern);
        assertEquals(pattern + "\n" + regex.byteCodeDebugDescription(),
                expected, regex.perlFirstProgramDebugDescription());
    }
}
