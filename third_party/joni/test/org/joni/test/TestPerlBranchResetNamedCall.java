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

import static org.joni.constants.SyntaxProperties.ALLOW_MULTIPLEX_DEFINITION_NAME_CALL;
import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.UTF8Encoding;
import org.joni.Matcher;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.junit.Test;

public class TestPerlBranchResetNamedCall {
    private static final Syntax PERL_SYNTAX = new Syntax(
            "PERL_TEST", Syntax.RUBY.op, Syntax.RUBY.op2, Syntax.RUBY.op3,
            Syntax.RUBY.behavior | ALLOW_MULTIPLEX_DEFINITION_NAME_CALL,
            Syntax.RUBY.options, Syntax.RUBY.metaCharTable);

    private static Matcher matcher(String pattern, String input) {
        byte[] patternBytes = pattern.getBytes(StandardCharsets.UTF_8);
        byte[] inputBytes = input.getBytes(StandardCharsets.UTF_8);
        Regex regex = new Regex(patternBytes, 0, patternBytes.length,
                Option.CAPTURE_GROUP, UTF8Encoding.INSTANCE, PERL_SYNTAX);
        return regex.matcher(inputBytes);
    }

    private static Matcher assertMatches(String pattern, String input) {
        Matcher matcher = matcher(pattern, input);
        assertEquals(0, matcher.search(0, input.length(), Option.NONE));
        return matcher;
    }

    private static void assertDoesNotMatch(String pattern, String input) {
        assertEquals(-1, matcher(pattern, input).search(0, input.length(), Option.NONE));
    }

    @Test
    public void namedBranchResetCallTargetsLeftmostPhysicalGroup() {
        String pattern = "(?|(?<digit>1)|(?<digit>2))\\g<digit>";
        Matcher first = assertMatches(pattern, "11");
        assertEquals(0, first.getRegion().getBeg(1));
        assertEquals(1, first.getRegion().getEnd(1));

        Matcher second = assertMatches(pattern, "21");
        assertEquals(0, second.getRegion().getBeg(1));
        assertEquals(1, second.getRegion().getEnd(1));

        assertDoesNotMatch(pattern, "12");
        assertDoesNotMatch(pattern, "22");
    }

    @Test
    public void distinctNamedBranchResetCallsUseTheirOwnPhysicalDefinitions() {
        String pattern = "(?|(?<a>a)|(?<b>b))\\1\\g<a>\\g<b>";
        assertMatches(pattern, "bbab");
    }

    @Test
    public void distinctNamedBranchResetConditionsUsePhysicalDefinitions() {
        String pattern = "(?|(?<a>a)|(?<b>b))(?(<a>)x|y)\\1";
        assertMatches(pattern, "byb");
        assertDoesNotMatch(pattern, "bxb");
        assertMatches(pattern, "axa");
    }

    @Test
    public void numericBranchResetCallTargetsLeftmostPhysicalGroup() {
        String pattern = "(?|(1)|(2))\\g<1>";
        assertMatches(pattern, "11");
        assertMatches(pattern, "21");
        assertDoesNotMatch(pattern, "12");
        assertDoesNotMatch(pattern, "22");
    }

    @Test
    public void ordinaryMultiplexNameCallTargetsFirstDefinition() {
        String pattern = "(?<x>1)(?<x>2)\\g<x>";
        Matcher matcher = assertMatches(pattern, "121");
        assertEquals(0, matcher.getRegion().getBeg(1));
        assertEquals(1, matcher.getRegion().getEnd(1));
        assertDoesNotMatch(pattern, "122");
    }

    @Test
    public void ordinaryCapturePublicationIsUnchanged() {
        Matcher matcher = assertMatches("(a)(b)", "ab");
        assertEquals(0, matcher.getRegion().getBeg(1));
        assertEquals(1, matcher.getRegion().getEnd(1));
        assertEquals(1, matcher.getRegion().getBeg(2));
        assertEquals(2, matcher.getRegion().getEnd(2));
    }

    @Test
    public void duplicateBranchResetNamesRetainPhysicalDefinitionSpans() {
        String pattern = "(?|(?<x>1)|(?<x>2))";
        Matcher first = assertMatches(pattern, "1");
        assertEquals(0, first.physicalNamedCaptureBegin(1));
        assertEquals(1, first.physicalNamedCaptureEnd(1));
        assertEquals(-1, first.physicalNamedCaptureBegin(2));
        assertEquals(-1, first.physicalNamedCaptureEnd(2));

        Matcher second = assertMatches(pattern, "2");
        assertEquals(-1, second.physicalNamedCaptureBegin(1));
        assertEquals(-1, second.physicalNamedCaptureEnd(1));
        assertEquals(0, second.physicalNamedCaptureBegin(2));
        assertEquals(1, second.physicalNamedCaptureEnd(2));
    }
}
