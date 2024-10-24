package org.perlonjava.runtime;

/**
 * The RuntimeScalarCache class provides a caching mechanism for frequently used
 * RuntimeScalar objects, such as small integers and common boolean values.
 * This helps improve performance by reusing immutable scalar instances.
 */
public class RuntimeScalarCache {

    // Range of integers to cache
    static int minInt = -100;
    static int maxInt = 100;
    // Array to store cached RuntimeScalarReadOnly objects for integers
    static RuntimeScalarReadOnly[] scalarInt = new RuntimeScalarReadOnly[maxInt - minInt + 1];
    // Cached RuntimeScalarReadOnly objects for common boolean and undefined values
    public static RuntimeScalarReadOnly scalarTrue;
    public static RuntimeScalarReadOnly scalarFalse;
    public static RuntimeScalarReadOnly scalarUndef;
    public static RuntimeScalarReadOnly scalarEmptyString;

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
}
