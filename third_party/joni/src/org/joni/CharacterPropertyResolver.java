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

/** Resolves syntax-specific character properties to inclusive code-point ranges. */
@FunctionalInterface
public interface CharacterPropertyResolver {
    /** Parser context of a property escape. */
    enum Context {
        OUTSIDE_CHARACTER_CLASS(false),
        STANDARD_CHARACTER_CLASS(true),
        PERL_EXTENDED_CHARACTER_CLASS(false);

        private final boolean legacyInCharacterClass;

        Context(boolean legacyInCharacterClass) {
            this.legacyInCharacterClass = legacyInCharacterClass;
        }

        boolean legacyInCharacterClass() {
            return legacyInCharacterClass;
        }
    }

    /** A resolver-owned policy rejection whose position is supplied by Joni. */
    final class ResolutionException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ResolutionException(String message) {
            super(message);
        }
    }

    /** A resolved range set and whether ignore-case folding applies to it. */
    final class Result {
        public final int[] ranges;
        public final long[] wideRanges;
        public final boolean caseFold;

        public Result(int[] ranges, boolean caseFold) {
            this(ranges, null, caseFold);
        }

        /**
         * Creates a result from the existing encoding-domain ranges and optional
         * signed-IV-domain ranges. Both arrays use {@code [count, from, to, ...]}
         * inclusive pairs; {@code wideRanges} may extend through
         * {@link Long#MAX_VALUE}.
         */
        public Result(int[] ranges, long[] wideRanges, boolean caseFold) {
            this.ranges = ranges;
            this.wideRanges = wideRanges;
            this.caseFold = caseFold;
        }
    }

    /**
     * Returns resolved ranges and their ignore-case policy, or {@code null} to
     * use the encoding's built-in property lookup. The class context allows a
     * resolver to defer properties whose composition semantics require host
     * handling. The overload below additionally exposes lexical options.
     */
    Result resolve(byte[] bytes, int p, int end, Encoding encoding,
                   boolean inCharacterClass);

    /**
     * Resolves a property with the lexical option state active at its token.
     * Existing resolvers remain source-compatible and may ignore that state.
     */
    default Result resolve(byte[] bytes, int p, int end, Encoding encoding,
                           boolean inCharacterClass, int option) {
        return resolve(bytes, p, end, encoding, inCharacterClass);
    }

    /**
     * Resolves a property with its exact parser context and lexical options.
     * The default preserves the historical boolean contract, including the
     * former extended-class behavior, for existing resolver implementations.
     */
    default Result resolve(byte[] bytes, int p, int end, Encoding encoding,
                           Context context, int option) {
        return resolve(bytes, p, end, encoding,
                context.legacyInCharacterClass(), option);
    }

    /** Validates a Perl script-run span; ordinary resolvers remain neutral. */
    default boolean isScriptRun(byte[] bytes, int p, int end, Encoding encoding,
                                WideScalarCodec wideScalarCodec) {
        return true;
    }
}
