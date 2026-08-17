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
package org.joni.ast;

/** Zero-width matcher-control verb that cannot be represented as literal text. */
public final class ControlVerbNode extends StringNode {
    public enum Kind { ACCEPT, FAIL, PRUNE, SKIP, THEN, COMMIT, MARK }

    public final Kind kind;
    public final String name;

    public ControlVerbNode(Kind kind) {
        this(kind, null);
    }

    public ControlVerbNode(Kind kind, String name) {
        super(0);
        this.kind = kind;
        this.name = name;
        setRaw();
        setDontGetOptInfo();
    }

    @Override
    public String getName() {
        return "ControlVerb";
    }

    @Override
    public String toString(int level) {
        return "\n  kind: " + kind + (name == null ? "" : "\n  name: " + name);
    }
}
