package org.perlonjava.runtime;

import static org.perlonjava.runtime.RuntimeContextType.SCALAR;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;
import static org.perlonjava.runtime.RuntimeScalarType.*;

/**
 * The {@code Overload} class implements Perl's operator overloading system in Java.
 * It handles method resolution, fallback mechanisms, and type conversions following
 * Perl's overloading rules.
 *
 * <p>Key features:
 * <ul>
 *   <li>Stringification via ("" operator
 *   <li>Numeric conversion via (0+ operator
 *   <li>Boolean context via (bool operator
 *   <li>Fallback mechanism support
 *   <li>Inheritance-aware method resolution
 * </ul>
 *
 * <p>Method resolution follows this order:
 * <ol>
 *   <li>Direct overloaded method lookup
 *   <li>Inheritance chain traversal
 *   <li>Fallback mechanism
 *   <li>Default behavior
 * </ol>
 */
public class Overload {

    /**
     * Enum defining the different conversion types and their associated overload methods
     */
    private enum ConversionType {
        STRING("(\"\"", "(0+", "(bool"),
        NUMERIC("(0+", "(\"\"", "(bool"),
        BOOLEAN("(bool", "(0+", "(\"\""),
        DEREF_SCALAR("(${}"),      // Scalar dereferencing
        DEREF_ARRAY("(@{}"),       // Array dereferencing
        DEREF_HASH("(%{}"),        // Hash dereferencing
        DEREF_CODE("(&{}"),        // Code/subroutine dereferencing
        DEREF_GLOB("(*{}");        // Typeglob dereferencing

        final String primaryMethod;
        final String fallbackMethod1;
        final String fallbackMethod2;

        ConversionType(String primary, String fallback1, String fallback2) {
            this.primaryMethod = primary;
            this.fallbackMethod1 = fallback1;
            this.fallbackMethod2 = fallback2;
        }
        ConversionType(String primary) {
            this.primaryMethod = primary;
            this.fallbackMethod1 = null;
            this.fallbackMethod2 = null;
        }
    }

    /**
     * Converts a {@link RuntimeScalar} object to its string representation following
     * Perl's stringification rules.
     *
     * @param runtimeScalar the {@code RuntimeScalar} object to be stringified
     * @return the string representation based on overloading rules
     */
    public static RuntimeScalar stringify(RuntimeScalar runtimeScalar) {
        RuntimeScalar result = convertWithOverload(runtimeScalar, ConversionType.STRING);
        if (result != null) {
            return result;
        }

        // Default string conversion for non-blessed or non-overloaded objects
        return new RuntimeScalar(switch (runtimeScalar.type) {
            case REFERENCE -> ((RuntimeScalarReference) runtimeScalar.value).toStringRef();
            case ARRAYREFERENCE -> ((RuntimeArray) runtimeScalar.value).toStringRef();
            case HASHREFERENCE -> ((RuntimeHash) runtimeScalar.value).toStringRef();
            default -> runtimeScalar.toStringRef();
        });
    }

    /**
     * Converts a {@link RuntimeScalar} object to its numeric representation following
     * Perl's numification rules.
     *
     * @param runtimeScalar the {@code RuntimeScalar} object to be numified
     * @return the numeric representation based on overloading rules
     */
    public static RuntimeScalar numify(RuntimeScalar runtimeScalar) {
        RuntimeScalar result = convertWithOverload(runtimeScalar, ConversionType.NUMERIC);
        if (result != null) {
            return result;
        }

        // Default number conversion for non-blessed or non-overloaded objects
        return new RuntimeScalar(switch (runtimeScalar.type) {
            case REFERENCE -> ((RuntimeScalarReference) runtimeScalar.value).getDoubleRef();
            case ARRAYREFERENCE -> ((RuntimeArray) runtimeScalar.value).getDoubleRef();
            case HASHREFERENCE -> ((RuntimeHash) runtimeScalar.value).getDoubleRef();
            default -> runtimeScalar.getDoubleRef();
        });
    }

    /**
     * Converts a {@link RuntimeScalar} object to its boolean representation following
     * Perl's boolification rules.
     *
     * @param runtimeScalar the {@code RuntimeScalar} object to be boolified
     * @return the boolean representation based on overloading rules
     */
    public static RuntimeScalar boolify(RuntimeScalar runtimeScalar) {
        RuntimeScalar result = convertWithOverload(runtimeScalar, ConversionType.BOOLEAN);
        if (result != null) {
            return result;
        }

        // Default bool conversion for non-blessed or non-overloaded objects
        return new RuntimeScalar(switch (runtimeScalar.type) {
            case REFERENCE -> ((RuntimeScalarReference) runtimeScalar.value).getBooleanRef();
            case ARRAYREFERENCE -> ((RuntimeArray) runtimeScalar.value).getBooleanRef();
            case HASHREFERENCE -> ((RuntimeHash) runtimeScalar.value).getBooleanRef();
            default -> runtimeScalar.getBooleanRef();
        });
    }

    /**
     * Core method that handles overload resolution for different conversion types.
     * Returns null if no overload method is found, allowing the caller to apply default conversion.
     *
     * @param runtimeScalar the scalar to convert
     * @param conversionType the type of conversion (STRING, NUMERIC, or BOOLEAN)
     * @return the result of the overload method, or null if no overload applies
     */
    private static RuntimeScalar convertWithOverload(RuntimeScalar runtimeScalar, ConversionType conversionType) {
        // Check if the scalar contains a blessed object
        if (!(runtimeScalar.value instanceof RuntimeBaseEntity baseEntity)) {
            return null;
        }

        int blessId = baseEntity.blessId;
        // Only proceed if the object is blessed (has a valid blessId)
        if (blessId == 0) {
            return null;
        }

        // Get the Perl class name from the bless ID
        String perlClassName = NameNormalizer.getBlessStr(blessId);

        // Look for overload markers in the class hierarchy
        // '((' indicates general overloading capability
        RuntimeScalar methodOverloaded = InheritanceResolver.findMethodInHierarchy("((", perlClassName, null, 0);
        // '()' indicates fallback behavior configuration
        RuntimeScalar methodFallback = InheritanceResolver.findMethodInHierarchy("()", perlClassName, null, 0);

        // Proceed only if either overloading or fallback is defined
        if (methodOverloaded == null && methodFallback == null) {
            return null;
        }

        // Prepare arguments array for method call
        RuntimeArray perlMethodArgs = new RuntimeArray(runtimeScalar);
        RuntimeScalar perlMethod;

        // First try: Look for primary overload method
        perlMethod = InheritanceResolver.findMethodInHierarchy(conversionType.primaryMethod, perlClassName, null, 0);

        // Handle fallback mechanism if primary overload not found
        if (perlMethod == null && methodFallback != null) {
            RuntimeScalar fallback = RuntimeCode.apply(methodFallback, new RuntimeArray(), SCALAR).getFirst();

            // If fallback is undefined or true, try alternative methods
            if (!fallback.getDefinedBoolean() || fallback.getBoolean()) {
                if (conversionType.fallbackMethod1 != null) {
                    // Try first fallback method
                    perlMethod = InheritanceResolver.findMethodInHierarchy(conversionType.fallbackMethod1, perlClassName, null, 0);
                    // Try second fallback method
                    if (perlMethod == null) {
                        perlMethod = InheritanceResolver.findMethodInHierarchy(conversionType.fallbackMethod2, perlClassName, null, 0);
                    }
                }
            }
        }

        // Last resort: try nomethod handler
        if (perlMethod == null) {
            perlMethod = InheritanceResolver.findMethodInHierarchy("(nomethod", perlClassName, null, 0);
            if (perlMethod != null) {
                // Setup arguments for nomethod handler
                RuntimeArray.push(perlMethodArgs, scalarUndef);
                RuntimeArray.push(perlMethodArgs, scalarUndef);
                RuntimeArray.push(perlMethodArgs, new RuntimeScalar(conversionType.primaryMethod));
            }
        }

        // Execute the found method if any
        if (perlMethod != null) {
            return RuntimeCode.apply(perlMethod, perlMethodArgs, SCALAR).getFirst();
        }

        return null;
    }
}