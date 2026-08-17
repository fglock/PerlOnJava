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
    }

    @org.junit.Test(timeout = 5000)
    public void testNestedQuantifierCombinationExplosion() throws Exception {
        x2s(".X(.+)+X", "bXcXaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 0, 4);
        org.junit.Assert.assertEquals(0, nfail + nerror);
    }
}
