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
     * Converts a {@link RuntimeScalar} object to its string representation following
     * Perl's stringification rules.
     *
     * @param runtimeScalar the {@code RuntimeScalar} object to be stringified
     * @return the string representation based on overloading rules
     */
    public static RuntimeScalar stringify(RuntimeScalar runtimeScalar) {
        // First try overloaded string conversion
        RuntimeScalar result = convertWithOverload(runtimeScalar, "(\"\"", "(0+", "(bool");
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
        // First try overloaded numeric conversion
        RuntimeScalar result = convertWithOverload(runtimeScalar, "(0+", "(\"\"", "(bool");
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
        // First try overloaded boolean conversion
        RuntimeScalar result = convertWithOverload(runtimeScalar, "(bool", "(0+", "(\"\"");
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
     * @param runtimeScalar   the scalar to convert
     * @param primaryMethod   the primary overload method to try first (e.g. "("")
     * @param fallbackMethod1 the first fallback method to try if primary fails
     * @param fallbackMethod2 the second fallback method to try if first fallback fails
     * @return the result of the overload method, or null if no overload applies
     */
    private static RuntimeScalar convertWithOverload(RuntimeScalar runtimeScalar, String primaryMethod, String fallbackMethod1, String fallbackMethod2) {
        // Prepare overload context and check if object is eligible for overloading
        OverloadContext ctx = OverloadContext.prepare(runtimeScalar);
        if (ctx == null) return null;

        // Try primary overload method first (e.g., ("" for string conversion)
        RuntimeScalar result = tryOverload(primaryMethod, ctx.perlClassName, new RuntimeArray(runtimeScalar));
        if (result != null) return result;

        // Handle fallback mechanism if defined
        result = tryOverloadFallback(runtimeScalar, fallbackMethod1, fallbackMethod2, ctx);
        if (result != null) return result;

        // Last resort: try nomethod handler with additional context information
        return tryOverload("(nomethod", ctx.perlClassName, new RuntimeArray(runtimeScalar, scalarUndef, scalarUndef, new RuntimeScalar(primaryMethod)));
    }

    private static RuntimeScalar tryOverloadFallback(RuntimeScalar runtimeScalar, String fallbackMethod1, String fallbackMethod2, OverloadContext ctx) {
        if (ctx.methodFallback == null) {
            return null;
        }

        RuntimeScalar result;
        // Execute fallback method to determine if alternative methods should be tried
        RuntimeScalar fallback = RuntimeCode.apply(ctx.methodFallback, new RuntimeArray(), SCALAR).getFirst();

        // If fallback returns undefined or true, try alternative conversion methods
        if (!fallback.getDefinedBoolean() || fallback.getBoolean()) {
            // Try first alternative method
            result = tryOverload(fallbackMethod1, ctx.perlClassName, new RuntimeArray(runtimeScalar));
            if (result != null) {
                return result;
            }

            // Try second alternative method
            result = tryOverload(fallbackMethod2, ctx.perlClassName, new RuntimeArray(runtimeScalar));
            return result;
        }
        return null;
    }

    // Helper method to attempt overload method execution
    private static RuntimeScalar tryOverload(String methodName, String perlClassName, RuntimeArray perlMethodArgs) {
        // Look for method in class hierarchy
        RuntimeScalar perlMethod = InheritanceResolver.findMethodInHierarchy(methodName, perlClassName, null, 0);
        if (perlMethod == null) {
            return null;
        }
        // Execute found method with provided arguments
        return RuntimeCode.apply(perlMethod, perlMethodArgs, SCALAR).getFirst();
    }

    // Helper class to manage overloading context for a given scalar
    private static class OverloadContext {
        final String perlClassName;
        final RuntimeScalar methodOverloaded;
        final RuntimeScalar methodFallback;

        private OverloadContext(String perlClassName, RuntimeScalar methodOverloaded, RuntimeScalar methodFallback) {
            this.perlClassName = perlClassName;
            this.methodOverloaded = methodOverloaded;
            this.methodFallback = methodFallback;
        }

        // Factory method to create overload context if applicable
        static OverloadContext prepare(RuntimeScalar runtimeScalar) {
            // Check if the scalar contains a blessed object
            if (!(runtimeScalar.value instanceof RuntimeBaseEntity baseEntity)) {
                return null;
            }

            // Get blessing ID and verify object is blessed
            int blessId = baseEntity.blessId;
            if (blessId == 0) {
                return null;
            }

            // Resolve Perl class name from blessing ID
            String perlClassName = NameNormalizer.getBlessStr(blessId);

            // Look for overload markers in the class hierarchy
            RuntimeScalar methodOverloaded = InheritanceResolver.findMethodInHierarchy("((", perlClassName, null, 0);
            RuntimeScalar methodFallback = InheritanceResolver.findMethodInHierarchy("()", perlClassName, null, 0);

            // Return context only if overloading is enabled
            if (methodOverloaded == null && methodFallback == null) {
                return null;
            }

            return new OverloadContext(perlClassName, methodOverloaded, methodFallback);
        }
    }
}
