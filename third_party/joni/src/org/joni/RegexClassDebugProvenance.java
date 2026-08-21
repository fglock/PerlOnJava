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

import java.util.List;

import org.jcodings.Encoding;
import org.joni.ast.CClassNode;

/** Immutable, matcher-neutral character-class provenance for debug rendering. */
final class RegexClassDebugProvenance {
    private final CClassNode.DebugMembership membership;
    private final CClassNode.DebugClassExpression expression;
    private final List<CClassNode.DebugRange> preFoldRanges;
    private final int lexicalOption;
    private final boolean highUnbounded;
    private final boolean propertyAny;
    private final boolean hasProperty;
    private final boolean valid;

    private RegexClassDebugProvenance(CClassNode.DebugMembership membership,
            CClassNode.DebugClassExpression expression,
            List<CClassNode.DebugRange> preFoldRanges, int lexicalOption,
            boolean highUnbounded, boolean propertyAny, boolean hasProperty,
            boolean valid) {
        this.membership = membership;
        this.expression = expression;
        this.preFoldRanges = List.copyOf(preFoldRanges);
        this.lexicalOption = lexicalOption;
        this.highUnbounded = highUnbounded;
        this.propertyAny = propertyAny;
        this.hasProperty = hasProperty;
        this.valid = valid;
    }

    static RegexClassDebugProvenance snapshot(CClassNode node, Encoding enc) {
        return new RegexClassDebugProvenance(node.debugMembership(enc),
                node.debugClassExpression(), node.debugPreFoldRanges(),
                node.debugLiteralLexicalOption(), node.debugHighUnbounded(),
                node.debugPropertyAny(), node.debugHasProperty(),
                node.debugProvenanceValid());
    }

    CClassNode.DebugMembership membership() { return membership; }
    CClassNode.DebugClassExpression expression() { return expression; }
    List<CClassNode.DebugRange> preFoldRanges() { return preFoldRanges; }
    int lexicalOption() { return lexicalOption; }
    boolean highUnbounded() { return highUnbounded; }
    boolean propertyAny() { return propertyAny; }
    boolean hasProperty() { return hasProperty; }
    boolean valid() { return valid; }
}
