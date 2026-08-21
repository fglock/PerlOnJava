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
package org.joni;

import org.joni.ast.CClassNode.DebugDomainShape;
import org.joni.constants.internal.OPCode;
import org.joni.constants.internal.OPSize;

final class RegexDebugProgram {
    private RegexDebugProgram() {
    }

    static Regex.DebugProgramFact firstFact(Regex regex) {
        int cursor = skipInitialDynamicOptionWrapper(regex.code, regex.codeLength);
        if (cursor < 0 || cursor >= regex.codeLength
                || regex.code[cursor] != OPCode.WIDE_SCALAR_CLASS
                || cursor + OPSize.WIDE_SCALAR_CLASS > regex.codeLength) {
            return Regex.DebugProgramFact.other();
        }

        int classIndex = regex.code[cursor + 1];
        if (regex.wideScalarClasses == null || classIndex < 0
                || classIndex >= regex.wideScalarClasses.length) {
            return Regex.DebugProgramFact.other();
        }

        DebugDomainShape shape = regex.wideScalarClasses[classIndex]
                .debugDomainShape(regex.enc);
        return switch (shape) {
            case FULL -> new Regex.DebugProgramFact(
                    Regex.DebugProgramKind.FULL_CLASS);
            case EMPTY -> new Regex.DebugProgramFact(
                    Regex.DebugProgramKind.EMPTY_CLASS);
            case ALL_EXCEPT_NEWLINE -> new Regex.DebugProgramFact(
                    Regex.DebugProgramKind.ALL_EXCEPT_NEWLINE_CLASS);
            case OTHER -> Regex.DebugProgramFact.other();
        };
    }

    private static int skipInitialDynamicOptionWrapper(int[] code, int length) {
        if (code == null || length == 0 || code[0] != OPCode.SET_OPTION_PUSH) {
            return 0;
        }
        int cursor = OPSize.SET_OPTION_PUSH;
        if (cursor >= length || code[cursor] != OPCode.SET_OPTION) return -1;
        cursor += OPSize.SET_OPTION;
        if (cursor >= length || code[cursor] != OPCode.FAIL) return -1;
        return cursor + OPSize.FAIL;
    }
}
