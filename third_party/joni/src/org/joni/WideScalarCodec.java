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

import org.jcodings.Encoding;

/**
 * Optional host contract for scalar values outside JCodings' Unicode domain.
 *
 * <p>Joni stores the scalar value in its syntax tree and bytecode. The host owns
 * the reversible byte representation used in matcher input. Returning
 * {@code null} from {@link #decode} means that the input at {@code p} is an
 * ordinary encoded character.</p>
 */
public interface WideScalarCodec {
    byte[] encode(long value, Encoding encoding);

    Decoded decode(byte[] bytes, int p, int end, Encoding encoding);

    default void parsedNumericEscape(NumericEscape escape) {
    }

    record Decoded(long value, int end) {
        public Decoded {
            if (value < 0) throw new IllegalArgumentException("wide scalar must be nonnegative");
            if (end < 0) throw new IllegalArgumentException("decoded end must be nonnegative");
        }
    }

    /** Structured lexer evidence for frontend warning and strict-mode policy. */
    record NumericEscape(char escape, long value, int invalidCodePoint,
                         int sourceStart, int sourceEnd,
                         WideScalarDomainEnd domainEnd) {
        public NumericEscape(char escape, long value, int invalidCodePoint,
                int sourceStart, int sourceEnd) {
            this(escape, value, invalidCodePoint, sourceStart, sourceEnd,
                    WideScalarDomainEnd.HIGHEST_SCALAR);
        }

        public boolean truncated() {
            return invalidCodePoint >= 0;
        }
    }
}
