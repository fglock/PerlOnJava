package org.perlonjava.runtime;

import static org.perlonjava.runtime.RuntimeContextType.SCALAR;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;

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
     * Perl's stringification rules. The method implements the following resolution order:
     *
     * <ol>
     *   <li>Check if object is blessed (has a Perl class association)
     *   <li>Look for ("" overloaded method
     *   <li>If fallback is enabled, try (0+ method
     *   <li>If still no match, try (bool method
     *   <li>If no overloading applies, use default string conversion
     * </ol>
     *
     * @param runtimeScalar the {@code RuntimeScalar} object to be stringified
     * @return the string representation based on overloading rules
     * @see RuntimeScalar
     * @see RuntimeBaseEntity
     * @see InheritanceResolver
     */
    public static RuntimeScalar stringify(RuntimeScalar runtimeScalar) {
        // Check if the scalar contains a blessed object
        if (runtimeScalar.value instanceof RuntimeBaseEntity baseEntity) {
            int blessId = baseEntity.blessId;
            // Only proceed if the object is blessed (has a valid blessId)
            if (blessId != 0) {
                // Get the Perl class name from the bless ID
                String perlClassName = NameNormalizer.getBlessStr(blessId);

                // Look for overload markers in the class hierarchy
                // '((' indicates general overloading capability
                RuntimeScalar methodOverloaded = InheritanceResolver.findMethodInHierarchy("((", perlClassName, null, 0);
                // '()' indicates fallback behavior configuration
                RuntimeScalar methodFallback = InheritanceResolver.findMethodInHierarchy("()", perlClassName, null, 0);

                // Proceed if either overloading or fallback is defined
                if (methodOverloaded != null || methodFallback != null) {
                    // Prepare arguments array for method call
                    RuntimeArray perlMethodArgs = new RuntimeArray(runtimeScalar);
                    RuntimeScalar perlMethod;

                    // First try: Look for string overload method
                    perlMethod = InheritanceResolver.findMethodInHierarchy("(\"\"", perlClassName, null, 0);

                    // Handle fallback mechanism if string overload not found
                    if (perlMethod == null && methodFallback != null) {
                        RuntimeScalar fallback = RuntimeCode.apply(methodFallback, new RuntimeArray(), SCALAR).getFirst();

                        // If fallback is undefined or true, try alternative methods
                        if (!fallback.getDefinedBoolean() || fallback.getBoolean()) {
                            // Try numeric conversion method
                            if (perlMethod == null) {
                                perlMethod = InheritanceResolver.findMethodInHierarchy("(0+", perlClassName, null, 0);
                            }
                            // Try boolean conversion method
                            if (perlMethod == null) {
                                perlMethod = InheritanceResolver.findMethodInHierarchy("(bool", perlClassName, null, 0);
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
                            RuntimeArray.push(perlMethodArgs, new RuntimeScalar("(\"\""));
                        }
                    }

                    // Execute the found method if any
                    if (perlMethod != null) {
                        return RuntimeCode.apply(perlMethod, perlMethodArgs, SCALAR).getFirst();
                    }
                }
            }
        }

        // Default string conversion for non-blessed or non-overloaded objects
        return new RuntimeScalar(switch (runtimeScalar.type) {
            case REFERENCE -> ((RuntimeScalarReference) runtimeScalar.value).toStringRef();
            case ARRAYREFERENCE -> ((RuntimeArray) runtimeScalar.value).toStringRef();
            case HASHREFERENCE -> ((RuntimeHash) runtimeScalar.value).toStringRef();
            default -> runtimeScalar.toStringRef();
        });
    }

    public static RuntimeScalar numify(RuntimeScalar runtimeScalar) {
        // Check if the scalar contains a blessed object
        if (runtimeScalar.value instanceof RuntimeBaseEntity baseEntity) {
            int blessId = baseEntity.blessId;
            // Only proceed if the object is blessed (has a valid blessId)
            if (blessId != 0) {
                // Get the Perl class name from the bless ID
                String perlClassName = NameNormalizer.getBlessStr(blessId);

                // Look for overload markers in the class hierarchy
                // '((' indicates general overloading capability
                RuntimeScalar methodOverloaded = InheritanceResolver.findMethodInHierarchy("((", perlClassName, null, 0);
                // '()' indicates fallback behavior configuration
                RuntimeScalar methodFallback = InheritanceResolver.findMethodInHierarchy("()", perlClassName, null, 0);

                // Proceed if either overloading or fallback is defined
                if (methodOverloaded != null || methodFallback != null) {
                    // Prepare arguments array for method call
                    RuntimeArray perlMethodArgs = new RuntimeArray(runtimeScalar);
                    RuntimeScalar perlMethod;

                    // First try: Look for number overload method
                    perlMethod = InheritanceResolver.findMethodInHierarchy("(0+", perlClassName, null, 0);

                    // Handle fallback mechanism if string overload not found
                    if (perlMethod == null && methodFallback != null) {
                        RuntimeScalar fallback = RuntimeCode.apply(methodFallback, new RuntimeArray(), SCALAR).getFirst();

                        // If fallback is undefined or true, try alternative methods
                        if (!fallback.getDefinedBoolean() || fallback.getBoolean()) {
                            // Try string conversion method
                            if (perlMethod == null) {
                                perlMethod = InheritanceResolver.findMethodInHierarchy("(\"\"", perlClassName, null, 0);
                            }
                            // Try boolean conversion method
                            if (perlMethod == null) {
                                perlMethod = InheritanceResolver.findMethodInHierarchy("(bool", perlClassName, null, 0);
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
                            RuntimeArray.push(perlMethodArgs, new RuntimeScalar("(0+"));
                        }
                    }

                    // Execute the found method if any
                    if (perlMethod != null) {
                        return RuntimeCode.apply(perlMethod, perlMethodArgs, SCALAR).getFirst();
                    }
                }
            }
        }

        // Default number conversion for non-blessed or non-overloaded objects
        return new RuntimeScalar(switch (runtimeScalar.type) {
            case REFERENCE -> ((RuntimeScalarReference) runtimeScalar.value).getDoubleRef();
            case ARRAYREFERENCE -> ((RuntimeArray) runtimeScalar.value).getDoubleRef();
            case HASHREFERENCE -> ((RuntimeHash) runtimeScalar.value).getDoubleRef();
            default -> runtimeScalar.getDoubleRef();
        });
    }
}
