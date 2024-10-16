package org.perlonjava.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code RuntimePosLvalue} class implements a caching mechanism for the Perl `pos` operator.
 * The `pos` operator in Perl returns the position of the last match in a string.
 * This class uses a cache to store and retrieve the position of a given {@code RuntimeScalar} value.
 */
public class RuntimePosLvalue {

    // Maximum size of the cache
    private static final int MAX_CACHE_SIZE = 1000;

    /**
     * A cache that stores the position of {@code RuntimeScalar} values.
     * It uses a LinkedHashMap to maintain insertion order and automatically remove the eldest entry
     * when the cache exceeds the maximum size.
     */
    private static final Map<RuntimeScalar, RuntimeScalar> positionCache = new LinkedHashMap<>(MAX_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<RuntimeScalar, RuntimeScalar> eldest) {
            // Remove the eldest entry if the cache size exceeds the maximum limit
            return size() > MAX_CACHE_SIZE;
        }
    };

    /**
     * Retrieves the position of the given {@code RuntimeScalar} value from the cache.
     * If the position is not already cached, a new {@code RuntimeScalar} is created, cached, and returned.
     *
     * @param value the {@code RuntimeScalar} value whose position is to be retrieved
     * @return the cached or newly created {@code RuntimeScalar} representing the position
     */
    public static RuntimeScalar pos(RuntimeScalar value) {
        // Attempt to retrieve the cached position for the given value
        RuntimeScalar scalar = positionCache.getOrDefault(value, null);

        // If the position is not cached, create a new RuntimeScalar and cache it
        if (scalar == null) {
            scalar = new RuntimeScalar();
            positionCache.put(value, scalar);
        }

        // Return the cached or newly created position
        return scalar;
    }
}