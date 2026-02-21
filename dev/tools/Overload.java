package org.perlonjava.runtime.runtimetypes;

import static org.perlonjava.runtime.runtimetypes.RuntimeContextType.SCALAR;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarUndef;

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

    @FunctionalInterface
    private interface ConversionFunction {
        RuntimeScalar apply(RuntimeScalar scalar);
    }

    private static RuntimeScalar handleOverload(
            RuntimeScalar runtimeScalar,
            String primaryMethod,
            String[] fallbackMethods,
            ConversionFunction defaultConversion
    ) {
        if (!(runtimeScalar.value instanceof RuntimeBase baseEntity)) {
            return defaultConversion.apply(runtimeScalar);
        }

        int blessId = baseEntity.blessId;
        if (blessId == 0) {
            return defaultConversion.apply(runtimeScalar);
        }

        String perlClassName = NameNormalizer.getBlessStr(blessId);
        RuntimeScalar methodOverloaded = InheritanceResolver.findMethodInHierarchy("((", perlClassName, null, 0);
        RuntimeScalar methodFallback = InheritanceResolver.findMethodInHierarchy("()", perlClassName, null, 0);

        if (methodOverloaded == null && methodFallback == null) {
            return defaultConversion.apply(runtimeScalar);
        }

        RuntimeArray perlMethodArgs = new RuntimeArray(runtimeScalar);
        RuntimeScalar perlMethod = InheritanceResolver.findMethodInHierarchy(primaryMethod, perlClassName, null, 0);

        if (perlMethod == null && methodFallback != null) {
            RuntimeScalar fallback = RuntimeCode.apply(methodFallback, new RuntimeArray(), SCALAR).getFirst();
            if (!fallback.getDefinedBoolean() || fallback.getBoolean()) {
                for (String fallbackMethod : fallbackMethods) {
                    perlMethod = InheritanceResolver.findMethodInHierarchy(fallbackMethod, perlClassName, null, 0);
                    if (perlMethod != null) break;
                }
            }
        }

        if (perlMethod == null) {
            perlMethod = InheritanceResolver.findMethodInHierarchy("(nomethod", perlClassName, null, 0);
            if (perlMethod != null) {
                RuntimeArray.push(perlMethodArgs, scalarUndef);
                RuntimeArray.push(perlMethodArgs, scalarUndef);
                RuntimeArray.push(perlMethodArgs, new RuntimeScalar(primaryMethod));
            }
        }

        return perlMethod != null 
            ? RuntimeCode.apply(perlMethod, perlMethodArgs, SCALAR).getFirst()
            : defaultConversion.apply(runtimeScalar);
    }

    public static RuntimeScalar stringify(RuntimeScalar runtimeScalar) {
        return handleOverload(
            runtimeScalar,
            "(\"\""
            new String[]{"(0+", "(bool"},
            rs -> new RuntimeScalar(switch (rs.type) {
                case REFERENCE -> ((RuntimeScalarReference) rs.value).toStringRef();
                case ARRAYREFERENCE -> ((RuntimeArray) rs.value).toStringRef();
                case HASHREFERENCE -> ((RuntimeHash) rs.value).toStringRef();
                default -> rs.toStringRef();
            })
        );
    }

    public static RuntimeScalar numify(RuntimeScalar runtimeScalar) {
        return handleOverload(
            runtimeScalar,
            "(0+",
            new String[]{"(\"\"", "(bool"},
            rs -> new RuntimeScalar(switch (rs.type) {
                case REFERENCE -> ((RuntimeScalarReference) rs.value).getDoubleRef();
                case ARRAYREFERENCE -> ((RuntimeArray) rs.value).getDoubleRef();
                case HASHREFERENCE -> ((RuntimeHash) rs.value).getDoubleRef();
                default -> rs.getDoubleRef();
            })
        );
    }

    public static RuntimeScalar boolify(RuntimeScalar runtimeScalar) {
        return handleOverload(
            runtimeScalar,
            "(bool",
            new String[]{"(0+", "(\"\""}, 
            rs -> new RuntimeScalar(switch (rs.type) {
                case REFERENCE -> ((RuntimeScalarReference) rs.value).getBooleanRef();
                case ARRAYREFERENCE -> ((RuntimeArray) rs.value).getBooleanRef();
                case HASHREFERENCE -> ((RuntimeHash) rs.value).getBooleanRef();
                default -> rs.getBooleanRef();
            })
        );
    }
}

