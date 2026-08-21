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

import java.util.Arrays;

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

    /**
     * Immutable parser fact for one property whose ranges are resolved by a
     * matcher. Raw bytes remain authoritative for resolution and diagnostics;
     * display-name bytes are callback-free host provenance.
     */
    final class DeferredProperty {
        private final byte[] name;
        private final byte[] displayName;
        private final Context context;
        private final int option;
        private final int position;
        private final boolean negated;

        public DeferredProperty(byte[] name, byte[] displayName,
                                Context context, int option, int position,
                                boolean negated) {
            if (name == null || displayName == null || context == null) {
                throw new NullPointerException("deferred property fact");
            }
            this.name = name.clone();
            this.displayName = displayName.clone();
            this.context = context;
            this.option = option;
            this.position = position;
            this.negated = negated;
        }

        public byte[] name() { return name.clone(); }
        public byte[] displayName() { return displayName.clone(); }
        public Context context() { return context; }
        public int option() { return option; }
        public int position() { return position; }
        public boolean negated() { return negated; }
    }

    /** A resolver-owned policy rejection whose position is supplied by Joni. */
    final class ResolutionException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int position;

        public ResolutionException(String message) {
            this(message, -1);
        }

        public ResolutionException(String message, int position) {
            super(message);
            this.position = position;
        }

        public int getPosition() { return position; }
    }

    /** A resolved range set and whether ignore-case folding applies to it. */
    final class Result {
        public final int[] ranges;
        public final long[] wideRanges;
        public final boolean caseFold;
        private final boolean deferred;
        private final byte[] deferredDisplayName;
        private final boolean deferredInExtendedClassAllowed;

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
            this(ranges, wideRanges, caseFold, false, null, false);
        }

        private Result(int[] ranges, long[] wideRanges, boolean caseFold,
                       boolean deferred, byte[] deferredDisplayName,
                       boolean deferredInExtendedClassAllowed) {
            if (!deferred) validateRanges(ranges, wideRanges);
            this.ranges = ranges == null ? null : Arrays.copyOf(ranges, ranges.length);
            this.wideRanges = wideRanges == null
                    ? null : Arrays.copyOf(wideRanges, wideRanges.length);
            this.caseFold = caseFold;
            this.deferred = deferred;
            this.deferredDisplayName = deferredDisplayName == null
                    ? null : deferredDisplayName.clone();
            this.deferredInExtendedClassAllowed =
                    deferredInExtendedClassAllowed;
        }

        /** Returns a parser marker whose ranges must be resolved by a matcher. */
        public static Result deferred() {
            return new Result(null, null, false, true, null, false);
        }

        /** Returns a deferred marker with callback-free display provenance. */
        public static Result deferred(byte[] displayName) {
            return new Result(null, null, false, true, displayName, true);
        }

        public boolean isDeferred() {
            return deferred;
        }

        public byte[] deferredDisplayName() {
            return deferredDisplayName == null ? null : deferredDisplayName.clone();
        }

        public boolean isDeferredInExtendedClassAllowed() {
            return deferredInExtendedClassAllowed;
        }

        private static void validateRanges(int[] ranges, long[] wideRanges) {
            if (ranges == null && wideRanges == null) {
                throw new IllegalArgumentException(
                        "invalid character property ranges");
            }
            if (ranges != null) {
                if (ranges.length == 0 || ranges[0] < 0
                        || ranges.length != (long)ranges[0] * 2 + 1) {
                    throw new IllegalArgumentException(
                            "invalid character property ranges");
                }
                int previousEnd = -1;
                for (int index = 0; index < ranges[0]; index++) {
                    int from = ranges[index * 2 + 1];
                    int to = ranges[index * 2 + 2];
                    if (from < 0 || from > to
                            || to > CodeRangeBuffer.LAST_CODE_POINT
                            || from <= previousEnd) {
                        throw new IllegalArgumentException(
                                "invalid character property ranges");
                    }
                    previousEnd = to;
                }
            }
            if (wideRanges != null) {
                if (wideRanges.length == 0 || wideRanges[0] < 0
                        || wideRanges[0] > Integer.MAX_VALUE
                        || wideRanges.length != wideRanges[0] * 2 + 1) {
                    throw new IllegalArgumentException(
                            "invalid character property ranges");
                }
                long previousEnd = -1;
                for (int index = 0; index < (int)wideRanges[0]; index++) {
                    long from = wideRanges[index * 2 + 1];
                    long to = wideRanges[index * 2 + 2];
                    if (from < 0 || from > to || from <= previousEnd) {
                        throw new IllegalArgumentException(
                                "invalid character property ranges");
                    }
                    previousEnd = to;
                }
            }
        }
    }

    /** Resolves a parser-retained property token on first opcode execution. */
    @FunctionalInterface
    public interface DeferredResolver {
        Result resolve(byte[] property, Context context, int option, int position,
                       Encoding encoding);
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

    /** Whether normalized Perl ctype terms cover the signed scalar domain. */
    default boolean hasAuthoritativePerlClassSemantics() {
        return false;
    }

    /** Validates a Perl script-run span; ordinary resolvers remain neutral. */
    default boolean isScriptRun(byte[] bytes, int p, int end, Encoding encoding,
                                WideScalarCodec wideScalarCodec) {
        return true;
    }
}
