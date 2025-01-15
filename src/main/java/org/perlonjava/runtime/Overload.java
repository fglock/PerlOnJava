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
     *
     * @see RuntimeScalar
     * @see RuntimeBaseEntity
     * @see InheritanceResolver
     */
    public static String stringify(RuntimeScalar runtimeScalar) {
        if (runtimeScalar.value instanceof RuntimeBaseEntity baseEntity) {
            int blessId = baseEntity.blessId;
            if (blessId != 0) {
                String perlClassName = NameNormalizer.getBlessStr(blessId);
                RuntimeScalar methodOverloaded = InheritanceResolver.findMethodInHierarchy("((", perlClassName, null, 0);
                RuntimeScalar methodFallback = InheritanceResolver.findMethodInHierarchy("()", perlClassName, null, 0);
                if (methodOverloaded != null || methodFallback != null) {
                    RuntimeArray perlMethodArgs = new RuntimeArray(runtimeScalar);
                    // handle blessed & overloaded objects
                    RuntimeScalar perlMethod;
                    // handle stringification with '(""'
                    perlMethod = InheritanceResolver.findMethodInHierarchy("(\"\"", perlClassName, null, 0);
                    if (methodFallback != null) {
                        // handle overload `fallback`
                        // TODO check if fallback is undef/true/false
                        if (perlMethod == null) {
                            perlMethod = InheritanceResolver.findMethodInHierarchy("(0+", perlClassName, null, 0);
                        }
                        if (perlMethod == null) {
                            perlMethod = InheritanceResolver.findMethodInHierarchy("(bool", perlClassName, null, 0);
                        }
                    }
                    // handle overload `nomethod`
                    if (perlMethod == null) {
                        perlMethod = InheritanceResolver.findMethodInHierarchy("(nomethod", perlClassName, null, 0);
                        if (perlMethod != null) {
                            RuntimeArray.push(perlMethodArgs, scalarUndef);
                            RuntimeArray.push(perlMethodArgs, scalarUndef);
                            RuntimeArray.push(perlMethodArgs, new RuntimeScalar("(\"\""));
                        }
                    }
                    if (perlMethod != null) {
                        return RuntimeCode.apply(perlMethod, perlMethodArgs, SCALAR).toString();
                    }
                }
            }
        }

        // Not blessed & not overloaded - Convert the scalar reference to its string representation
        return switch (runtimeScalar.type) {
            case REFERENCE -> ((RuntimeScalarReference) runtimeScalar.value).toStringRef();
            case ARRAYREFERENCE -> ((RuntimeArray) runtimeScalar.value).toStringRef();
            case HASHREFERENCE -> ((RuntimeHash) runtimeScalar.value).toStringRef();
            default -> runtimeScalar.toStringRef();
        };
    }
}
