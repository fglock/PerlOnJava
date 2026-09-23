package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.runtime.WarningBitsRegistry;
import org.perlonjava.runtime.operators.PerlUtfString;
import org.perlonjava.runtime.perlmodule.Strict;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The {@code RuntimePosLvalue} class implements a caching mechanism for the Perl `pos` operator.
 * The `pos` operator in Perl returns the position of the last match in a string.
 * This class uses a cache to store and retrieve the position of a given {@code RuntimeScalar} value.
 */
public class RuntimePosLvalue {

    private static Map<RuntimeScalar, CacheEntry> positionCache() {
        return PerlRuntime.current().regexState.positionCache;
    }

    /**
     * Retrieves the position of the given {@code RuntimeScalar} value from the cache.
     * If the position is not already cached or the value has changed, a new {@code RuntimeScalar} is created, cached, and returned.
     *
     * @param perlVariable the {@code RuntimeScalar} value whose position is to be retrieved
     * @return the cached or newly created {@code RuntimeScalar} representing the position
     */
    public static RuntimeScalar pos(RuntimeScalar perlVariable) {
        return pos(perlVariable, false);
    }

    /** Whether the current position was produced by a successful regex match. */
    public static boolean wasPublishedByMatcher(RuntimeScalar perlVariable) {
        perlVariable = perlVariable.posStorage();
        CacheEntry cachedEntry = positionCache().get(perlVariable);
        return cachedEntry != null
                && ((PosLvalueScalar) cachedEntry.regexPosition).regexPublished;
    }

    /** Retrieve the position using character or lexical byte units. */
    public static RuntimeScalar pos(RuntimeScalar perlVariable, boolean byteView) {
        // Validate input
        if (perlVariable == null) {
            throw new PerlCompilerException("perlVariable cannot be null");
        }

        perlVariable = perlVariable.posStorage();

        RuntimeScalar position;

        // Retrieve the cached entry for the given value
        CacheEntry cachedEntry = positionCache().get(perlVariable);

        // Check if the value is missing or it has changed
        int code = perlVariable.value == null ? 0 : perlVariable.value.hashCode();
        if (cachedEntry == null || cachedEntry.valueHash != code) {
            // If the position is not cached or the value has changed,
            // create a new undefined RuntimeScalar to represent the position
            position = new PosLvalueScalar(perlVariable);
            // Cache the new position with the current hash of the value
            cachedEntry = new CacheEntry(code, position);
            positionCache().put(perlVariable, cachedEntry);
        } else {
            // Use the cached position if the value has not changed
            position = cachedEntry.regexPosition;
        }
        if (!byteView || perlVariable.type == RuntimeScalarType.BYTE_STRING) {
            return position;
        }
        if (cachedEntry.bytePosition == null) {
            cachedEntry.bytePosition = new BytePosLvalueScalar(perlVariable, position);
        }
        cachedEntry.bytePosition.syncType();
        return cachedEntry.bytePosition;
    }

    /** Convert a public Perl pos() value to the matcher's Java UTF-16 offset. */
    public static int toMatcherOffset(RuntimeScalar perlVariable,
                                      String stringValue, int perlPosition) {
        if (perlVariable.type == RuntimeScalarType.BYTE_STRING) {
            return Math.max(0, Math.min(perlPosition, stringValue.length()));
        }
        return PerlUtfString.offsetByPerlCodePoints(stringValue, 0, perlPosition);
    }

    /** Convert a matcher Java UTF-16 offset to the public Perl pos() value. */
    public static int fromMatcherOffset(RuntimeScalar perlVariable,
                                        String stringValue, int matcherOffset) {
        if (perlVariable.type == RuntimeScalarType.BYTE_STRING) {
            return Math.max(0, Math.min(matcherOffset, stringValue.length()));
        }
        return PerlUtfString.perlOffsetForJavaIndex(stringValue, matcherOffset);
    }

    private static int characterToByteOffset(String string, int characterOffset) {
        int javaOffset = PerlUtfString.offsetByPerlCodePoints(
                string, 0, Math.max(0, characterOffset));
        return string.substring(0, javaOffset).getBytes(StandardCharsets.UTF_8).length;
    }

    private static int byteToCharacterOffset(String string, long byteOffset) {
        long wanted = Math.max(0, byteOffset);
        long bytes = 0;
        int characters = 0;
        for (int offset = 0; offset < string.length();) {
            int codePoint = string.codePointAt(offset);
            int width = new String(Character.toChars(codePoint))
                    .getBytes(StandardCharsets.UTF_8).length;
            if (bytes + width > wanted) break;
            bytes += width;
            characters++;
            offset += Character.charCount(codePoint);
        }
        return characters;
    }

    /** Publish a position produced by the regex engine rather than Perl lvalue assignment. */
    public static void publishMatchPosition(RuntimeScalar perlVariable, RuntimeScalar position) {
        RuntimeScalar stored = pos(perlVariable);
        ((PosLvalueScalar) stored).setFromMatcher(position);
        CacheEntry entry = positionCache().get(perlVariable.posStorage());
        entry.matcherBytePosition = perlVariable.type == RuntimeScalarType.BYTE_STRING
                && position.getDefinedBoolean() ? position.getInt() : null;
    }

    /** Publish an integer position produced by the regex engine. */
    public static void publishMatchPosition(RuntimeScalar perlVariable, int position) {
        RuntimeScalar stored = pos(perlVariable);
        ((PosLvalueScalar) stored).setFromMatcher(position);
        CacheEntry entry = positionCache().get(perlVariable.posStorage());
        entry.matcherBytePosition = perlVariable.type == RuntimeScalarType.BYTE_STRING
                ? position : null;
    }

    /** Copy pos() and zero-length /g bookkeeping between equivalent scalar views. */
    public static void copyPositionState(RuntimeScalar source, RuntimeScalar target) {
        source = source.posStorage();
        target = target.posStorage();
        RuntimeScalar targetPosition = pos(target);
        CacheEntry sourceEntry = positionCache().get(source);
        CacheEntry targetEntry = positionCache().get(target);
        if (sourceEntry == null || sourceEntry.regexPosition.type == RuntimeScalarType.UNDEF) {
            targetPosition.type = RuntimeScalarType.UNDEF;
            targetPosition.value = null;
            ((PosLvalueScalar) targetPosition).regexPublished = false;
            targetEntry.lastMatchWasZeroLength = false;
            targetEntry.lastMatchPosition = -1;
            targetEntry.lastMatchPattern = null;
            targetEntry.matcherBytePosition = null;
            return;
        }
        targetPosition.type = sourceEntry.regexPosition.type;
        PosLvalueScalar sourcePosition = (PosLvalueScalar) sourceEntry.regexPosition;
        PosLvalueScalar destinationPosition = (PosLvalueScalar) targetPosition;
        int sourceValue = sourcePosition.getInt();
        if (sourcePosition.regexPublished
                && source.type != RuntimeScalarType.BYTE_STRING
                && target.type == RuntimeScalarType.BYTE_STRING) {
            int bytePosition = sourceEntry.matcherBytePosition != null
                    ? sourceEntry.matcherBytePosition
                    : characterToByteOffset(source.toString(), sourceValue);
            destinationPosition.value = (long) bytePosition;
            targetEntry.matcherBytePosition = bytePosition;
        } else if (sourcePosition.regexPublished
                && source.type == RuntimeScalarType.BYTE_STRING
                && target.type != RuntimeScalarType.BYTE_STRING) {
            destinationPosition.value = (long) byteToCharacterOffset(target.toString(), sourceValue);
            targetEntry.matcherBytePosition = sourceEntry.matcherBytePosition != null
                    ? sourceEntry.matcherBytePosition : sourceValue;
        } else {
            destinationPosition.value = sourceEntry.regexPosition.value;
            targetEntry.matcherBytePosition = sourceEntry.matcherBytePosition;
        }
        destinationPosition.regexPublished = sourcePosition.regexPublished;
        targetEntry.lastMatchWasZeroLength = sourceEntry.lastMatchWasZeroLength;
        targetEntry.lastMatchPosition = sourceEntry.lastMatchPosition;
        targetEntry.lastMatchPattern = sourceEntry.lastMatchPattern;
    }

    /**
     * Invalidate the pos() for a scalar when its string value is modified.
     * This should be called on any string modification operation (.=, substr assignment, etc.)
     * to ensure pos() returns undef after the modification.
     *
     * @param perlVariable the scalar whose pos should be invalidated
     */
    public static void invalidatePos(RuntimeScalar perlVariable) {
        if (perlVariable == null || PerlRuntime.currentOrNull() == null) {
            return;
        }
        perlVariable = perlVariable.posStorage();
        // Reset the canonical pos lvalue in place. Removing the cache entry orphans the
        // PosLvalueScalar that matchRegexDirect may already hold (local posScalar), breaking
        // /g and \\G after (?{ }) or other mid-match assignments to the target scalar.
        CacheEntry cachedEntry = positionCache().get(perlVariable);
        if (cachedEntry != null) {
            int code = perlVariable.value == null ? 0 : perlVariable.value.hashCode();
            cachedEntry.valueHash = code;
            RuntimeScalar pos = cachedEntry.regexPosition;
            pos.type = RuntimeScalarType.UNDEF;
            pos.value = null;
            ((PosLvalueScalar) pos).regexPublished = false;
            cachedEntry.lastMatchWasZeroLength = false;
            cachedEntry.lastMatchPosition = -1;
            cachedEntry.lastMatchPattern = null;
            cachedEntry.hasUnicodeChars = null;
            cachedEntry.matcherBytePosition = null;
        }
    }

    private static void clearZeroLengthMatchTracking(RuntimeScalar perlVariable) {
        perlVariable = perlVariable.posStorage();
        CacheEntry cachedEntry = positionCache().get(perlVariable);
        if (cachedEntry != null) {
            cachedEntry.lastMatchWasZeroLength = false;
            cachedEntry.lastMatchPosition = -1;
            cachedEntry.lastMatchPattern = null;
            cachedEntry.matcherBytePosition = null;
        }
    }

    /**
     * Check if the last global match at this position was zero-length. Perl's
     * retry guard belongs to pos(), not to a particular compiled pattern, so a
     * different pattern must also retry with NOTEMPTY at the same position.
     */
    public static boolean hadZeroLengthMatchAt(RuntimeScalar perlVariable, int position, String patternKey) {
        perlVariable = perlVariable.posStorage();
        CacheEntry cachedEntry = positionCache().get(perlVariable);
        if (cachedEntry == null) {
            return false;
        }
        return cachedEntry.lastMatchWasZeroLength &&
                cachedEntry.lastMatchPosition == position;
    }

    /**
     * Record that a zero-length match occurred at the given position with the given pattern.
     */
    public static void recordZeroLengthMatch(RuntimeScalar perlVariable, int position, String patternKey) {
        perlVariable = perlVariable.posStorage();
        CacheEntry cachedEntry = positionCache().get(perlVariable);
        if (cachedEntry != null) {
            cachedEntry.lastMatchWasZeroLength = true;
            cachedEntry.lastMatchPosition = position;
            cachedEntry.lastMatchPattern = patternKey;
        }
    }

    /**
     * Clear the zero-length match tracking (called after successful non-zero-length match).
     */
    public static void recordNonZeroLengthMatch(RuntimeScalar perlVariable) {
        perlVariable = perlVariable.posStorage();
        CacheEntry cachedEntry = positionCache().get(perlVariable);
        if (cachedEntry != null) {
            cachedEntry.lastMatchWasZeroLength = false;
            cachedEntry.lastMatchPattern = null;
        }
    }

    private static class PosLvalueScalar extends RuntimeScalar {
        private final RuntimeScalar target;
        private boolean regexPublished;

        private PosLvalueScalar(RuntimeScalar target) {
            super();
            this.target = target;
        }

        private void setFromMatcher(RuntimeScalar newValue) {
            super.set(newValue);
            regexPublished = newValue.getDefinedBoolean();
        }

        private void setFromMatcher(int newValue) {
            super.set(newValue);
            regexPublished = true;
        }

        @Override
        public RuntimeScalar set(RuntimeScalar value) {
            RuntimePosLvalue.clearZeroLengthMatchTracking(target);
            RuntimeScalar result = super.set(value);
            regexPublished = false;
            return result;
        }

        @Override
        public RuntimeScalar set(int value) {
            RuntimePosLvalue.clearZeroLengthMatchTracking(target);
            RuntimeScalar result = super.set(value);
            regexPublished = false;
            return result;
        }

        @Override
        public RuntimeScalar set(long value) {
            RuntimePosLvalue.clearZeroLengthMatchTracking(target);
            RuntimeScalar result = super.set(value);
            regexPublished = false;
            return result;
        }

        @Override
        public RuntimeScalar set(boolean value) {
            RuntimePosLvalue.clearZeroLengthMatchTracking(target);
            RuntimeScalar result = super.set(value);
            regexPublished = false;
            return result;
        }

        @Override
        public RuntimeScalar set(String value) {
            RuntimePosLvalue.clearZeroLengthMatchTracking(target);
            RuntimeScalar result = super.set(value);
            regexPublished = false;
            return result;
        }

        @Override
        public RuntimeScalar set(Object value) {
            RuntimePosLvalue.clearZeroLengthMatchTracking(target);
            RuntimeScalar result = super.set(value);
            regexPublished = false;
            return result;
        }
    }

    private static final class BytePosLvalueScalar extends RuntimeScalar {
        private final RuntimeScalar target;
        private final RuntimeScalar characterPosition;

        private BytePosLvalueScalar(RuntimeScalar target, RuntimeScalar characterPosition) {
            this.target = target;
            this.characterPosition = characterPosition;
            syncType();
        }

        private void syncType() {
            if (characterPosition.getDefinedBoolean()) {
                type = RuntimeScalarType.INTEGER;
                value = bytePosition();
            } else {
                type = RuntimeScalarType.UNDEF;
                value = null;
            }
        }

        private long bytePosition() {
            if (!characterPosition.getDefinedBoolean()) return 0;
            if (!((PosLvalueScalar) characterPosition).regexPublished) {
                return characterPosition.getLong();
            }
            CacheEntry entry = positionCache().get(target.posStorage());
            if (entry != null && entry.matcherBytePosition != null) {
                return entry.matcherBytePosition;
            }
            return characterToByteOffset(target.toString(), characterPosition.getInt());
        }

        private boolean bytesHintActive() {
            return (WarningBitsRegistry.getCallSiteHints() & Strict.HINT_BYTES) != 0;
        }

        @Override
        public boolean getDefinedBoolean() {
            return characterPosition.getDefinedBoolean();
        }

        @Override
        public int getInt() {
            return bytesHintActive() ? (int) bytePosition() : characterPosition.getInt();
        }

        @Override
        public long getLong() {
            return bytesHintActive() ? bytePosition() : characterPosition.getLong();
        }

        @Override
        public double getDouble() {
            return bytesHintActive() ? bytePosition() : characterPosition.getDouble();
        }

        @Override
        public boolean getBoolean() {
            return getDefinedBoolean() && getLong() != 0;
        }

        @Override
        public String toString() {
            return getDefinedBoolean() ? Long.toString(getLong()) : "";
        }

        @Override
        public RuntimeScalar set(RuntimeScalar newValue) {
            RuntimePosLvalue.clearZeroLengthMatchTracking(target);
            return characterPosition.set(newValue);
        }

        @Override
        public RuntimeScalar set(int newValue) {
            return set(new RuntimeScalar(newValue));
        }

        @Override
        public RuntimeScalar set(long newValue) {
            return set(new RuntimeScalar(newValue));
        }

        @Override
        public RuntimeScalar set(boolean newValue) {
            return set(new RuntimeScalar(newValue));
        }

        @Override
        public RuntimeScalar set(String newValue) {
            return set(new RuntimeScalar(newValue));
        }

        @Override
        public RuntimeScalar set(Object newValue) {
            return set(new RuntimeScalar(newValue));
        }
    }

    /**
     * A cache entry that stores the hash of a {@code RuntimeScalar} value and its regex position.
     * This helps in determining if the cached position is still valid for the given scalar.
     */
    static final class CacheEntry {
        int valueHash; // Hash of the RuntimeScalar value to detect changes
        RuntimeScalar regexPosition; // Cached position of the regex match
        boolean lastMatchWasZeroLength; // Track if last match was zero-length
        int lastMatchPosition; // Position of last zero-length match
        String lastMatchPattern; // Pattern that had the zero-length match
        Boolean hasUnicodeChars; // Cached result of Unicode character check (null = not computed)
        BytePosLvalueScalar bytePosition; // lexical byte view over regexPosition
        Integer matcherBytePosition; // exact progress for byte regexes inside multibyte scalars

        CacheEntry(int valueHash, RuntimeScalar regexPosition) {
            this.valueHash = valueHash;
            this.regexPosition = regexPosition;
            this.lastMatchWasZeroLength = false;
            this.lastMatchPosition = -1;
            this.lastMatchPattern = null;
            this.hasUnicodeChars = null;
        }
    }
    
    /**
     * Check if a string contains Unicode characters (code points > 255).
     * Results are cached per-scalar to avoid re-scanning on every regex match.
     *
     * @param perlVariable the scalar to check
     * @param stringValue the string value (already extracted from the scalar)
     * @return true if the string contains characters > 255
     */
    public static boolean hasUnicodeChars(RuntimeScalar perlVariable, String stringValue) {
        if (perlVariable == null || stringValue == null) {
            return false;
        }
        
        perlVariable = perlVariable.posStorage();
        CacheEntry cachedEntry = positionCache().get(perlVariable);
        // Use the same hash calculation as pos() for consistency
        int code = perlVariable.value == null ? 0 : perlVariable.value.hashCode();
        
        // If cache entry exists and value hasn't changed, use cached result
        if (cachedEntry != null && cachedEntry.valueHash == code && cachedEntry.hasUnicodeChars != null) {
            return cachedEntry.hasUnicodeChars;
        }
        
        // Compute hasUnicodeChars
        boolean result = false;
        for (int i = 0; i < stringValue.length(); i++) {
            if (stringValue.charAt(i) > 255) {
                result = true;
                break;
            }
        }
        
        // Cache the result - but only update hasUnicodeChars, don't replace the whole entry
        // if only hasUnicodeChars was missing (to preserve pos)
        if (cachedEntry != null && cachedEntry.valueHash == code) {
            // Entry exists with same hash, just update hasUnicodeChars
            cachedEntry.hasUnicodeChars = result;
        } else {
            // Need to create new cache entry (value changed or no entry)
            RuntimeScalar position = new PosLvalueScalar(perlVariable);
            cachedEntry = new CacheEntry(code, position);
            cachedEntry.hasUnicodeChars = result;
            positionCache().put(perlVariable, cachedEntry);
        }
        
        return result;
    }
}
