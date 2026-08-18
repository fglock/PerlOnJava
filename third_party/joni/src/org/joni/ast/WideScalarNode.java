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

import org.jcodings.Encoding;

/** One Perl scalar atom whose numeric value is outside Unicode. */
public final class WideScalarNode extends StringNode {
    public final long value;

    public WideScalarNode(long value, byte[] encoded) {
        super(encoded, 0, encoded.length);
        this.value = value;
        // Treat this host-defined atom as opaque to Analyser.  CANY has the
        // same one-character width but disables StringNode byte-prefix and
        // automatic-possessification reasoning that cannot understand the
        // codec representation.  Compiler dispatches on the concrete node.
        type = CANY;
        setRaw();
        setDontGetOptInfo();
    }

    @Override
    public int length(Encoding enc) {
        return 1;
    }

    @Override
    public boolean canBeSplit(Encoding enc) {
        return false;
    }

    @Override
    public StringNode splitLastChar(Encoding enc) {
        return null;
    }

    @Override
    public String getName() {
        return "Wide Scalar";
    }
}
