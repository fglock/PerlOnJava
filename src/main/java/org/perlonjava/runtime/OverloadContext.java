package org.perlonjava.runtime;

import org.perlonjava.mro.InheritanceResolver;

import java.util.function.Function;

import static org.perlonjava.runtime.RuntimeContextType.SCALAR;
import static org.perlonjava.runtime.RuntimeScalarCache.*;

/**
 * Helper class to manage overloading context for a given scalar in Perl-style object system.
 * Handles method overloading and fallback mechanisms for blessed objects.
 * 
 * <p><b>How Perl Overloading Works:</b>
 * <ul>
 *   <li>Classes use {@code use overload} to define operator overloads</li>
 *   <li>The overload pragma creates special methods in the class namespace:
 *     <ul>
 *       <li>{@code ((} - marker method indicating overload is enabled</li>
 *       <li>{@code ()} - fallback method</li>
 *       <li>{@code (0+} - numeric conversion method</li>
 *       <li>{@code ("")} - string conversion method</li>
 *       <li>etc. for other operators</li>
 *     </ul>
 *   </li>
 *   <li>When an overloaded operator is used, we:
 *     <ol>
 *       <li>Check if the object is blessed</li>
 *       <li>Look for {@code ((} method to see if overload is enabled</li>
 *       <li>Look for the specific operator method (e.g., {@code (0+})</li>
 *       <li>Try fallback mechanisms if direct method not found</li>
 *     </ol>
 *   </li>
 * </ul>
 * 
 * <p><b>Math::BigInt Example:</b>
 * <pre>
 * package Math::BigInt;
 * use overload
 *     '0+' => sub { $_[0]->bstr() },  # Creates (0+ method
 *     '""' => \&bstr,                  # Creates ("" method
 *     # ... other operators
 *     ;
 * # The overload pragma also creates (( and () markers
 * </pre>
 * 
 * @see InheritanceResolver#findMethodInHierarchy
 * @see Overload#numify
 * @see Overload#stringify
 */
public class OverloadContext {
    private static final boolean TRACE_OVERLOAD_CONTEXT = false;
    
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
        // Fast path: positive blessIds are non-overloaded classes (set at bless time)
        // Negative blessIds indicate classes with overloads
        // This saves ~10-20ns HashMap lookup per hash access
        if (blessId > 0) {
            return null;
        }

        // Check cache first
        OverloadContext cachedContext = InheritanceResolver.getCachedOverloadContext(blessId);
        if (cachedContext != null) {
            return cachedContext;
        }

        // Resolve Perl class name from blessing ID
        String perlClassName = NameNormalizer.getBlessStr(blessId);

        if (TRACE_OVERLOAD_CONTEXT) {
            System.err.println("TRACE OverloadContext.prepare:");
            System.err.println("  blessId: " + blessId);
            System.err.println("  perlClassName: " + perlClassName);
            System.err.flush();
        }

        // Look for overload markers in the class hierarchy
        RuntimeScalar methodOverloaded = InheritanceResolver.findMethodInHierarchy("((", perlClassName, null, 0);
        RuntimeScalar methodFallback = InheritanceResolver.findMethodInHierarchy("()", perlClassName, null, 0);

        if (TRACE_OVERLOAD_CONTEXT) {
            System.err.println("  methodOverloaded ((): " + (methodOverloaded != null ? "FOUND" : "NULL"));
            System.err.println("  methodFallback (): " + (methodFallback != null ? "FOUND" : "NULL"));
            System.err.flush();
        }

        // Create context if overloading is enabled
        OverloadContext context = null;
        if (methodOverloaded != null || methodFallback != null) {
            context = new OverloadContext(perlClassName, methodOverloaded, methodFallback);
            // Cache the result
            InheritanceResolver.cacheOverloadContext(blessId, context);
        }

        return context;
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
