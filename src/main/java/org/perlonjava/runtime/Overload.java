package org.perlonjava.runtime;

import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;

/**
 * The {@code Overload} class provides functionality to handle Perl-like
 * overloading of objects. This is particularly useful in scenarios where
 * objects need to be converted to strings or other types based on their
 * internal state or metadata.
 */
public class Overload {

    /**
     * Converts a {@link RuntimeScalar} object to its string representation.
     * This method checks if the object is "blessed" (i.e., associated with
     * a class or type in Perl) and handles it accordingly.
     *
     * @param runtimeScalar the {@code RuntimeScalar} object to be converted
     *                      to a string.
     * @return the string representation of the {@code RuntimeScalar}.
     */
    public static String stringify(RuntimeScalar runtimeScalar) {

        // Retrieve the blessId to determine if the object is blessed
        int blessId = ((RuntimeBaseEntity) runtimeScalar.value).blessId;

        if (blessId != 0) {
            // TODO: handle blessed objects
            // Blessed objects are associated with a specific class or type
            // and may require special handling to convert to a string.
        }

        // Convert the scalar reference to its string representation
        return ((RuntimeScalarReference) runtimeScalar.value).toStringRef();
    }
}
