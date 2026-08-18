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

import org.joni.Option;
import org.joni.Syntax;
import org.joni.exception.ErrorMessages;
import org.jcodings.Encoding;
import org.jcodings.specific.ASCIIEncoding;

public class TestPerl extends Test {
	@Override
    public int option() {
        return Option.DEFAULT;
    }
	@Override
    public Encoding encoding() {
        return ASCIIEncoding.INSTANCE;
    }
	@Override
    public String testEncoding() {
        return "iso-8859-2";
    }
	@Override
    public Syntax syntax() {
        return Syntax.PerlNG;
    }
	@Override
    public void test() throws Exception {
        xerrs("(?<;name>match)", ErrorMessages.PERL_GROUP_NAME_MUST_START_WITH_WORD);
        xerrs("(?^-i:foo)", ErrorMessages.PERL_CARET_MINUS_OPTION_NOT_RECOGNIZED);
        xerrs("(?^d:foo)", ErrorMessages.PERL_CARET_D_OPTION_NOT_RECOGNIZED);
        xerrs("(?^lu:foo)", ErrorMessages.PERL_MODIFIERS_L_AND_U_MUTUALLY_EXCLUSIVE);
        xerrs("(?da:foo)", ErrorMessages.PERL_MODIFIERS_D_AND_A_MUTUALLY_EXCLUSIVE);
        xerrs("(?lil:foo)", ErrorMessages.PERL_MODIFIER_L_MAY_NOT_APPEAR_TWICE);
        xerrs("(?aaia:foo)", ErrorMessages.PERL_MODIFIER_A_MAXIMUM_TWICE);
        xerrs("\\o{7", ErrorMessages.PERL_MISSING_RIGHT_BRACE_ON_OCTAL_ESCAPE);
        xerrs("[\\o{7]", ErrorMessages.PERL_MISSING_RIGHT_BRACE_ON_OCTAL_ESCAPE);
        xerrs("\\o{}", ErrorMessages.PERL_EMPTY_OCTAL_ESCAPE);
        xerrs("[\\o{}]", ErrorMessages.PERL_EMPTY_OCTAL_ESCAPE);
        x2s("\\o{141}", "a", 0, 1);
        x2s("\\o{ 141 }", "a", 0, 1);
        ns("\\o{789}", "");
        xerrs("\\x{X", ErrorMessages.PERL_MISSING_RIGHT_BRACE_ON_HEX_ESCAPE);
        xerrs("[\\x{X]", ErrorMessages.PERL_MISSING_RIGHT_BRACE_ON_HEX_ESCAPE);
        x2s("\\x{61}", "a", 0, 1);
        ns("\\x{}", "");
        ns("\\x{X}", "");
        x2s("(?P<word>a)(?P=word)", "aa", 0, 2);
        ns("(?P<word>a)(?P=word)", "ab");
        x2s("(?:(?P<x>a)|(?P<x>b))(?P=x)", "aa", 0, 2);
        x2s("(?:(?P<x>a)|(?P<x>b))(?P=x)", "bb", 0, 2);
        ns("(?:(?P<x>a)|(?P<x>b))(?P=x)", "ab");
        xerrs("(?P=missing)", ErrorMessages.PERL_REFERENCE_TO_NONEXISTENT_NAMED_GROUP);
        xerrs("(?P<)", ErrorMessages.PERL_GROUP_NAME_MUST_START_WITH_WORD);
        xerrs("(?P=)", ErrorMessages.PERL_GROUP_NAME_MUST_START_WITH_WORD);
        xerrs("(?PX<n>foo)", "Sequence (?PX...) not recognized");
        xerrs("(?P<name", ErrorMessages.PERL_PYTHON_NAMED_CAPTURE_NOT_TERMINATED);
        xerrs("(?P=name", ErrorMessages.PERL_PYTHON_NAMED_BACKREF_NOT_TERMINATED);
        ns("[(?P<x>)]", "z");
        x2s("\\(\\?P<x>", "(?P<x>", 0, 6);
        x2s("a(*pla:b)b", "ab", 0, 2);
        x2s("a(*positive_lookahead:b)b", "ab", 0, 2);
        ns("a(*pla:c)b", "ab");
        x2s("a(*plb:a)b", "ab", 0, 2);
        x2s("a(*positive_lookbehind:a)b", "ab", 0, 2);
        ns("a(*plb:c)b", "ab");
        x2s("a(*nla:c)b", "ab", 0, 2);
        x2s("a(*negative_lookahead:c)b", "ab", 0, 2);
        ns("a(*nla:b)b", "ab");
        x2s("a(*nlb:c)b", "ab", 0, 2);
        x2s("a(*negative_lookbehind:c)b", "ab", 0, 2);
        ns("a(*nlb:a)b", "ab");
        ns("(*atomic:a|ab)c", "abc");
        x2s("a(*pla:(*nla:c)b)b", "ab", 0, 2);
        x2s("(*pla:)", "", 0, 0);
        xerrs("(*PLA:a)", "Unknown verb pattern 'PLA'");
        xerrs("(*pla)", "'(*pla' requires a terminating ':'");
        xerrs("(*positive_lookahead)",
                "'(*positive_lookahead' requires a terminating ':'");
        xerrs("(*plx:a)", "Unknown '(*...)' construct 'plx'");
        xerrs("(*pla:a", ErrorMessages.PERL_UNTERMINATED_CONTROL_ARGUMENT);
        xerrs("(*positive_lookahead:a", ErrorMessages.PERL_UNTERMINATED_CONTROL_ARGUMENT);
        ns("[(?*pla:)]", "z");
        x2s("\\(\\*pla:a\\)", "(*pla:a)", 0, 8);
    }

    @org.junit.Test(timeout = 5000)
    public void testNestedQuantifierCombinationExplosion() throws Exception {
        x2s(".X(.+)+X", "bXcXaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 0, 4);
        org.junit.Assert.assertEquals(0, nfail + nerror);
    }
}
