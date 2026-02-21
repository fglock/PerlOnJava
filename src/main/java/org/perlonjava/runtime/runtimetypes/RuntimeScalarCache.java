package org.perlonjava.runtime.runtimetypes;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The RuntimeScalarCache class provides a caching mechanism for frequently used
 * RuntimeScalar objects, such as small integers, common boolean values, and strings.
 * This helps improve performance by reusing immutable scalar instances.
 */
public class RuntimeScalarCache {

    // Dynamic string cache
    private static final int INITIAL_STRING_CACHE_SIZE = 256;
    private static final int MAX_CACHED_STRING_LENGTH = 100;
    private static final AtomicInteger nextStringIndex = new AtomicInteger(0);
    private static final ConcurrentHashMap<String, Integer> stringToIndex = new ConcurrentHashMap<>();
    private static final Object stringCacheLock = new Object();
    // Dynamic byte string cache
    private static final AtomicInteger nextByteStringIndex = new AtomicInteger(0);
    private static final ConcurrentHashMap<String, Integer> byteStringToIndex = new ConcurrentHashMap<>();
    private static final Object byteStringCacheLock = new Object();
    private static volatile RuntimeScalarReadOnly[] scalarByteString = new RuntimeScalarReadOnly[INITIAL_STRING_CACHE_SIZE];
    // Cached RuntimeScalarReadOnly objects for common boolean and undefined values
    public static RuntimeScalarReadOnly scalarTrue;
    public static RuntimeScalarReadOnly scalarFalse;
    public static RuntimeScalarReadOnly scalarUndef;
    public static RuntimeScalarReadOnly scalarEmptyString;
    public static RuntimeScalarReadOnly scalarZero;
    public static RuntimeScalarReadOnly scalarOne;
    // Range of integers to cache
    static int minInt = -100;
    static int maxInt = 100;
    // Array to store cached RuntimeScalarReadOnly objects for integers
    static RuntimeScalarReadOnly[] scalarInt = new RuntimeScalarReadOnly[maxInt - minInt + 1];
    private static volatile RuntimeScalarReadOnly[] scalarString = new RuntimeScalarReadOnly[INITIAL_STRING_CACHE_SIZE];

    // Static block to initialize the cache
    static {
        // Cache integer values within the specified range
        for (int i = minInt; i <= maxInt; i++) {
            scalarInt[i - minInt] = new RuntimeScalarReadOnly(i);
        }
        // Cache common boolean and undefined values
        scalarFalse = new RuntimeScalarReadOnly(false);
        scalarTrue = new RuntimeScalarReadOnly(true);
        scalarUndef = new RuntimeScalarReadOnly();
        scalarEmptyString = new RuntimeScalarReadOnly("");
        scalarZero = new RuntimeScalarReadOnly(0);
        scalarOne = new RuntimeScalarReadOnly(1);

        // Don't pre-register strings - let them be added naturally
    }

    /**
     * Gets or creates a cache index for the specified byte string.
     * Returns -1 if the string should not be cached (too long or null).
     *
     * @param s the string to cache
     * @return the cache index, or -1 if not cacheable
     */
    public static int getOrCreateByteStringIndex(String s) {
        if (s == null || s.length() > MAX_CACHED_STRING_LENGTH) {
            return -1;
        }

        // Check if already cached
        Integer existingIndex = byteStringToIndex.get(s);
        if (existingIndex != null) {
            return existingIndex;
        }

        // Need to add new string
        synchronized (byteStringCacheLock) {
            // Double-check after acquiring lock
            existingIndex = byteStringToIndex.get(s);
            if (existingIndex != null) {
                return existingIndex;
            }

            int index = nextByteStringIndex.getAndIncrement();

            // Grow array if needed
            if (index >= scalarByteString.length) {
                int newSize = scalarByteString.length * 2;
                RuntimeScalarReadOnly[] newArray = Arrays.copyOf(scalarByteString, newSize);
                scalarByteString = newArray;
            }

            RuntimeScalarReadOnly cached = new RuntimeScalarReadOnly(s);
            cached.type = RuntimeScalarType.BYTE_STRING;
            scalarByteString[index] = cached;
            byteStringToIndex.put(s, index);

            return index;
        }
    }

    /**
     * Retrieves a cached RuntimeScalar for the byte string at the specified index.
     *
     * @param index the index of the byte string in the cache
     * @return the cached RuntimeScalar representing the byte string value
     */
    public static RuntimeScalar getScalarByteString(int index) {
        return scalarByteString[index];
    }

    /**
     * Gets or creates a cache index for the specified string.
     * Returns -1 if the string should not be cached (too long or null).
     *
     * @param s the string to cache
     * @return the cache index, or -1 if not cacheable
     */
    public static int getOrCreateStringIndex(String s) {
        if (s == null || s.length() > MAX_CACHED_STRING_LENGTH) {
            return -1;
        }

        // Check if already cached
        Integer existingIndex = stringToIndex.get(s);
        if (existingIndex != null) {
            return existingIndex;
        }

        // Need to add new string
        synchronized (stringCacheLock) {
            // Double-check after acquiring lock
            existingIndex = stringToIndex.get(s);
            if (existingIndex != null) {
                return existingIndex;
            }

            int index = nextStringIndex.getAndIncrement();

            // Grow array if needed
            if (index >= scalarString.length) {
                int newSize = scalarString.length * 2;
                RuntimeScalarReadOnly[] newArray = Arrays.copyOf(scalarString, newSize);
                scalarString = newArray;
            }

            RuntimeScalarReadOnly cached = new RuntimeScalarReadOnly(s);
            scalarString[index] = cached;
            stringToIndex.put(s, index);

            return index;
        }
    }

    /**
     * Retrieves a cached RuntimeScalar for the specified boolean value.
     *
     * @param b the boolean value
     * @return the cached RuntimeScalar representing the boolean value
     */
    public static RuntimeScalar getScalarBoolean(boolean b) {
        return b ? scalarTrue : scalarFalse;
    }

    /**
     * Retrieves a cached RuntimeScalar for the specified integer value.
     * If the integer is within the cached range, a cached instance is returned;
     * otherwise, a new RuntimeScalar is created.
     *
     * @param i the integer value
     * @return the cached or newly created RuntimeScalar representing the integer value
     */
    public static RuntimeScalar getScalarInt(int i) {
        if (i >= minInt && i <= maxInt) {
            return scalarInt[i - minInt];
        }
        return new RuntimeScalar(i);
    }

    /**
     * Retrieves a cached RuntimeScalar for the specified long integer value.
     * If the long integer is within the cached range, a cached instance is returned;
     * otherwise, a new RuntimeScalar is created.
     *
     * @param i the long integer value
     * @return the cached or newly created RuntimeScalar representing the long integer value
     */
    public static RuntimeScalar getScalarInt(long i) {
        if (i >= minInt && i <= maxInt) {
            return scalarInt[(int) i - minInt];
        }
        return new RuntimeScalar(i);
    }

    /**
     * Retrieves a cached RuntimeScalar for the string at the specified index.
     * This method assumes the index is valid and within bounds.
     *
     * @param index the index of the string in the cache
     * @return the cached RuntimeScalar representing the string value
     */
    public static RuntimeScalar getScalarString(int index) {
        return scalarString[index];
    }
}
