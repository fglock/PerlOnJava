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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.UTF8Encoding;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Regex.ParsedProgramFeature;
import org.joni.Syntax;
import org.joni.exception.SyntaxException;
import org.junit.Test;

public class TestPerlQuotePreserveMetadata {
    @Test
    public void rawQuoteRegionsMatchLiteralMetacharacters() {
        assertMatch("\\Qa.b[0]$|(){}*+?^-\\E", "a.b[0]$|(){}*+?^-");
        assertMatch("before\\Q.*[x]", "before.*[x]");
        assertMatch("\\Q\\E", "");
        assertMatch("\\Qa.b\\Ec\\Q[d]\\E", "a.bc[d]");
    }

    @Test
    public void publishesOnlyAcceptedPositivePreserveModifiers() {
        assertPreserve("(?p)abc");
        assertPreserve("(?p:abc)");
        assertPreserve("(?ip:abc)");
        assertPreserve("(?i-p:abc)(?p)def");
        assertPreserve("(?i:(?p:abc))");

        assertNoPreserve("(?-p:abc)");
        assertNoPreserve("\\(\\?p\\)");
        assertNoPreserve("[(?p)]");
        assertNoPreserve("\\Q(?p)\\E");
        assertNoPreserve("# (?p)\nabc", Option.EXTEND);
    }

    @Test
    public void malformedModifierGroupDoesNotPublishPreserveFact() {
        SyntaxException error = assertThrows(SyntaxException.class,
                () -> compile("(?p:abc", Option.NONE));
        assertFalse(error.getParsedProgramMetadata().has(
                ParsedProgramFeature.INLINE_PRESERVE));
    }

    private static void assertPreserve(String source) {
        assertTrue(source, compile(source, Option.NONE)
                .getParsedProgramMetadata().has(
                        ParsedProgramFeature.INLINE_PRESERVE));
    }

    private static void assertNoPreserve(String source) {
        assertNoPreserve(source, Option.NONE);
    }

    private static void assertNoPreserve(String source, int option) {
        assertFalse(source, compile(source, option)
                .getParsedProgramMetadata().has(
                        ParsedProgramFeature.INLINE_PRESERVE));
    }

    private static void assertMatch(String pattern, String target) {
        Regex regex = compile(pattern, Option.NONE);
        byte[] bytes = target.getBytes(StandardCharsets.UTF_8);
        assertEquals(pattern, 0,
                regex.matcher(bytes).search(0, bytes.length, Option.NONE));
    }

    private static Regex compile(String source, int option) {
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        return new Regex(bytes, 0, bytes.length, option,
                UTF8Encoding.INSTANCE, Syntax.PerlNG);
    }
}
