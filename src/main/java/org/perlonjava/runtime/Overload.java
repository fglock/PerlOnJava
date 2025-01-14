package org.perlonjava.runtime;

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
        if (runtimeScalar.value instanceof RuntimeBaseEntity baseEntity) {
            int blessId = baseEntity.blessId;

            if (blessId != 0) {
                // TODO: handle blessed & overloaded objects
                // Blessed objects are associated with a specific class or type
                // and may require special handling to convert to a string.
                //
                // If UNIVERSAL::can($v, "()") || UNIVERSAL::can($v, "((") {
                //    $sub = UNIVERSAL::can($v, "(\"");
                //    if ($sub) {
                //       $sub->($v);
                //    } else {
                //       // Handle overload fallback
                //    }
                // }
            }
        }

        // Not blessed & overloaded - Convert the scalar reference to its string representation
        return switch (runtimeScalar.type) {
            case REFERENCE -> ((RuntimeScalarReference) runtimeScalar.value).toStringRef();
            case ARRAYREFERENCE -> ((RuntimeArray) runtimeScalar.value).toStringRef();
            case HASHREFERENCE -> ((RuntimeHash) runtimeScalar.value).toStringRef();
            default -> runtimeScalar.toStringRef();
        };
    }
}
