package org.perlonjava.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code RuntimePosLvalue} class implements a caching mechanism for the Perl `pos` operator.
 * The `pos` operator in Perl returns the position of the last match in a string.
 * This class uses a cache to store and retrieve the position of a given {@code RuntimeScalar} value.
 */
public class RuntimePosLvalue {

    // Maximum size of the cache to prevent excessive memory usage
    private static final int MAX_CACHE_SIZE = 1000;

    /**
     * A cache that stores the position of {@code RuntimeScalar} values and their hashes.
     * It uses a LinkedHashMap to maintain insertion order and automatically remove the eldest entry
     * when the cache exceeds the maximum size. This ensures that the cache does not grow indefinitely.
     */
    private static final Map<RuntimeScalar, CacheEntry> positionCache = new LinkedHashMap<>(MAX_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<RuntimeScalar, CacheEntry> eldest) {
            // Remove the eldest entry if the cache size exceeds the maximum limit
            return size() > MAX_CACHE_SIZE;
        }
    };

    /**
     * Retrieves the position of the given {@code RuntimeScalar} value from the cache.
     * If the position is not already cached or the value has changed, a new {@code RuntimeScalar} is created, cached, and returned.
     *
     * @param perlVariable the {@code RuntimeScalar} value whose position is to be retrieved
     * @return the cached or newly created {@code RuntimeScalar} representing the position
     */
    public static RuntimeScalar pos(RuntimeScalar perlVariable) {
        // Validate input
        if (perlVariable == null) {
            throw new PerlCompilerException("perlVariable cannot be null");
        }

        RuntimeScalar position;

        // Retrieve the cached entry for the given value
        CacheEntry cachedEntry = positionCache.get(perlVariable);

        // Check if the value is missing or it has changed
        int code = perlVariable.value == null ? 0 : perlVariable.value.hashCode();
        if (cachedEntry == null || cachedEntry.valueHash != code) {
            // If the position is not cached or the value has changed,
            // create a new undefined RuntimeScalar to represent the position
            position = new PosLvalueScalar(perlVariable);
            // Cache the new position with the current hash of the value
            positionCache.put(perlVariable, new CacheEntry(code, position));
        } else {
            // Use the cached position if the value has not changed
            position = cachedEntry.regexPosition;
        }
        return position;
    }

    private static void clearZeroLengthMatchTracking(RuntimeScalar perlVariable) {
        CacheEntry cachedEntry = positionCache.get(perlVariable);
        if (cachedEntry != null) {
            cachedEntry.lastMatchWasZeroLength = false;
            cachedEntry.lastMatchPosition = -1;
            cachedEntry.lastMatchPattern = null;
        }
    }

    private static class PosLvalueScalar extends RuntimeScalar {
        private final RuntimeScalar target;

        private PosLvalueScalar(RuntimeScalar target) {
            super();
            this.target = target;
        }

        @Override
        public RuntimeScalar set(RuntimeScalar value) {
            RuntimePosLvalue.clearZeroLengthMatchTracking(target);
            return super.set(value);
        }

        @Override
        public RuntimeScalar set(int value) {
            RuntimePosLvalue.clearZeroLengthMatchTracking(target);
            return super.set(value);
        }

        @Override
        public RuntimeScalar set(long value) {
            RuntimePosLvalue.clearZeroLengthMatchTracking(target);
            return super.set(value);
        }

        @Override
        public RuntimeScalar set(boolean value) {
            RuntimePosLvalue.clearZeroLengthMatchTracking(target);
            return super.set(value);
        }

        @Override
        public RuntimeScalar set(String value) {
            RuntimePosLvalue.clearZeroLengthMatchTracking(target);
            return super.set(value);
        }

        @Override
        public RuntimeScalar set(Object value) {
            RuntimePosLvalue.clearZeroLengthMatchTracking(target);
            return super.set(value);
        }
    }

    /**
     * Check if the last match at this position was zero-length with the given pattern.
     * This is used to prevent infinite loops in global regex matches.
     */
    public static boolean hadZeroLengthMatchAt(RuntimeScalar perlVariable, int position, String patternKey) {
        CacheEntry cachedEntry = positionCache.get(perlVariable);
        if (cachedEntry == null) {
            return false;
        }
        return cachedEntry.lastMatchWasZeroLength &&
                cachedEntry.lastMatchPosition == position &&
                patternKey.equals(cachedEntry.lastMatchPattern);
    }

    /**
     * Record that a zero-length match occurred at the given position with the given pattern.
     */
    public static void recordZeroLengthMatch(RuntimeScalar perlVariable, int position, String patternKey) {
        CacheEntry cachedEntry = positionCache.get(perlVariable);
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
        CacheEntry cachedEntry = positionCache.get(perlVariable);
        if (cachedEntry != null) {
            cachedEntry.lastMatchWasZeroLength = false;
            cachedEntry.lastMatchPattern = null;
        }
    }

    /**
     * A cache entry that stores the hash of a {@code RuntimeScalar} value and its regex position.
     * This helps in determining if the cached position is still valid for the given scalar.
     */
    private static class CacheEntry {
        int valueHash; // Hash of the RuntimeScalar value to detect changes
        RuntimeScalar regexPosition; // Cached position of the regex match
        boolean lastMatchWasZeroLength; // Track if last match was zero-length
        int lastMatchPosition; // Position of last zero-length match
        String lastMatchPattern; // Pattern that had the zero-length match

        CacheEntry(int valueHash, RuntimeScalar regexPosition) {
            this.valueHash = valueHash;
            this.regexPosition = regexPosition;
            this.lastMatchWasZeroLength = false;
            this.lastMatchPosition = -1;
            this.lastMatchPattern = null;
        }
    }
}