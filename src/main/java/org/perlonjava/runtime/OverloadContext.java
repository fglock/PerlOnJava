package org.perlonjava.runtime;

import java.util.function.Function;

import static org.perlonjava.runtime.RuntimeContextType.SCALAR;
import static org.perlonjava.runtime.RuntimeScalarCache.*;

/**
 * Helper class to manage overloading context for a given scalar in Perl-style object system.
 * Handles method overloading and fallback mechanisms for blessed objects.
 */
public class OverloadContext {
    /**
     * The Perl class name of the blessed object
     */
    final String perlClassName;
    /**
     * The overloaded method handler
     */
    final RuntimeScalar methodOverloaded;
    /**
     * The fallback method handler
     */
    final RuntimeScalar methodFallback;

    /**
     * Private constructor to create an OverloadContext instance.
     *
     * @param perlClassName    The Perl class name
     * @param methodOverloaded The overloaded method handler
     * @param methodFallback   The fallback method handler
     */
    private OverloadContext(String perlClassName, RuntimeScalar methodOverloaded, RuntimeScalar methodFallback) {
        this.perlClassName = perlClassName;
        this.methodOverloaded = methodOverloaded;
        this.methodFallback = methodFallback;
    }

    /**
     * Factory method to create overload context if applicable for a given RuntimeScalar.
     * Checks if the scalar is a blessed object and has overloading enabled.
     *
     * @param blessId Pointer to the class of the blessed object
     * @return OverloadContext instance if overloading is enabled, null otherwise
     */
    public static OverloadContext prepare(int blessId) {
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

    public static RuntimeScalar tryOneArgumentOverload(RuntimeScalar runtimeScalar, int blessId, String operator, String methodName, Function<RuntimeScalar, RuntimeScalar> fallbackFunction) {
        // Prepare overload context and check if object is eligible for overloading
        OverloadContext ctx = OverloadContext.prepare(blessId);
        if (ctx == null) return null;
        // Try primary overload method
        RuntimeScalar result = ctx.tryOverload(operator, new RuntimeArray(runtimeScalar));
        if (result != null) return result;
        // Try fallback
        result = ctx.tryOverloadFallback(runtimeScalar, "(0+", "(\"\"", "(bool");
        if (result != null) {
            return fallbackFunction.apply(result);
        }
        // Try nomethod
        result = ctx.tryOverloadNomethod(runtimeScalar, methodName);
        return result;
    }

    public static RuntimeScalar tryTwoArgumentOverload(RuntimeScalar arg1, RuntimeScalar arg2, int blessId, int blessId2, String overloadName, String methodName) {
        if (blessId != 0) {
            // Try primary overload method
            OverloadContext ctx = prepare(blessId);
            if (ctx != null) {
                RuntimeScalar result = ctx.tryOverload(overloadName, new RuntimeArray(arg1, arg2, scalarFalse));
                if (result != null) return result;
            }
        }
        if (blessId2 != 0) {
            // Try swapped overload
            OverloadContext ctx = prepare(blessId2);
            if (ctx != null) {
                RuntimeScalar result = ctx.tryOverload(overloadName, new RuntimeArray(arg2, arg1, scalarTrue));
                if (result != null) return result;
            }
        }
        if (blessId != 0) {
            // Try first nomethod
            OverloadContext ctx = prepare(blessId);
            if (ctx != null) {
                RuntimeScalar result = ctx.tryOverload("(nomethod", new RuntimeArray(arg1, arg2, scalarFalse, new RuntimeScalar(methodName)));
                if (result != null) return result;
            }
        }
        if (blessId2 != 0) {
            // Try swapped nomethod
            OverloadContext ctx = prepare(blessId2);
            if (ctx != null) {
                RuntimeScalar result = ctx.tryOverload("(nomethod", new RuntimeArray(arg2, arg1, scalarTrue, new RuntimeScalar(methodName)));
                return result;
            }
        }
        return null;
    }

    public RuntimeScalar tryOverloadNomethod(RuntimeScalar runtimeScalar, String methodName) {
        return tryOverload("(nomethod", new RuntimeArray(runtimeScalar, scalarUndef, scalarUndef, new RuntimeScalar(methodName)));
    }

    /**
     * Attempts to execute fallback overloading methods if primary method fails.
     *
     * @param runtimeScalar   The scalar value to process
     * @param fallbackMethods Variable number of fallback method names to try in sequence
     * @return RuntimeScalar result from successful fallback execution, or null if all attempts fail
     */
    public RuntimeScalar tryOverloadFallback(RuntimeScalar runtimeScalar, String... fallbackMethods) {
        if (methodFallback == null) return null;

        // Execute fallback method to determine if alternative methods should be tried
        RuntimeScalar fallback = RuntimeCode.apply(methodFallback, new RuntimeArray(), SCALAR).getFirst();

        // If fallback returns undefined or true, try alternative conversion methods
        if (!fallback.getDefinedBoolean() || fallback.getBoolean()) {
            // Try each fallback method in sequence
            for (String fallbackMethod : fallbackMethods) {
                RuntimeScalar result = this.tryOverload(fallbackMethod, new RuntimeArray(runtimeScalar));
                if (result != null) return result;
            }
        }
        return null;
    }

    /**
     * Attempts to execute an overloaded method with given arguments.
     *
     * @param methodName     The name of the method to execute
     * @param perlMethodArgs Array of arguments to pass to the method
     * @return RuntimeScalar result from method execution, or null if method not found
     */
    public RuntimeScalar tryOverload(String methodName, RuntimeArray perlMethodArgs) {
        // Look for method in class hierarchy
        RuntimeScalar perlMethod = InheritanceResolver.findMethodInHierarchy(methodName, perlClassName, null, 0);
        if (perlMethod == null) {
            return null;
        }
        // Execute found method with provided arguments
        return RuntimeCode.apply(perlMethod, perlMethodArgs, SCALAR).getFirst();
    }
}
