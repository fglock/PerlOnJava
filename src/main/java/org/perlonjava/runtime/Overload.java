package org.perlonjava.runtime;

import org.perlonjava.parser.NumberParser;

import static org.perlonjava.runtime.RuntimeScalarCache.scalarFalse;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarTrue;

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
    private static final boolean TRACE_OVERLOAD = false;

    /**
     * Converts a {@link RuntimeScalar} object to its string representation following
     * Perl's stringification rules.
     *
     * @param runtimeScalar the {@code RuntimeScalar} object to be stringified
     * @return the string representation based on overloading rules
     */
    public static RuntimeScalar stringify(RuntimeScalar runtimeScalar) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = RuntimeScalarType.blessedId(runtimeScalar);
        if (blessId != 0) {
            OverloadContext ctx = OverloadContext.prepare(blessId);
            if (ctx != null) {
                // Try primary overload method
                RuntimeScalar result = ctx.tryOverload("(\"\"", new RuntimeArray(runtimeScalar));
                if (result != null) return result;
                // Try fallback
                result = ctx.tryOverloadFallback(runtimeScalar, "(0+", "(bool");
                if (result != null) return result;
                // Try nomethod
                result = ctx.tryOverloadNomethod(runtimeScalar, "\"\"");
                if (result != null) return result;
            }
        }

        // Default string conversion for non-blessed or non-overloaded objects
        // For REFERENCE type, use the REFERENCE's toStringRef() to get "REF(...)" format
        // For other reference types, use the value's toStringRef()
        if (runtimeScalar.type == RuntimeScalarType.REFERENCE) {
            return new RuntimeScalar(runtimeScalar.toStringRef());
        }
        return new RuntimeScalar(((RuntimeBase) runtimeScalar.value).toStringRef());
    }

    /**
     * Converts a {@link RuntimeScalar} object to its numeric representation following
     * Perl's numification rules.
     *
     * @param runtimeScalar the {@code RuntimeScalar} object to be numified
     * @return the numeric representation based on overloading rules
     */
    public static RuntimeScalar numify(RuntimeScalar runtimeScalar) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = RuntimeScalarType.blessedId(runtimeScalar);
        
        if (TRACE_OVERLOAD) {
            System.err.println("TRACE Overload.numify:");
            System.err.println("  Input scalar: " + runtimeScalar);
            System.err.println("  Input type: " + runtimeScalar.type);
            System.err.println("  blessId: " + blessId);
            if (blessId != 0) {
                System.err.println("  Blessed as: " + NameNormalizer.getBlessStr(blessId));
            }
            System.err.flush();
        }
        
        if (blessId != 0) {
            OverloadContext ctx = OverloadContext.prepare(blessId);
            
            if (TRACE_OVERLOAD) {
                System.err.println("  OverloadContext: " + (ctx != null ? "FOUND" : "NULL"));
                System.err.flush();
            }
            
            if (ctx != null) {
                // Try primary overload method
                RuntimeScalar result = ctx.tryOverload("(0+", new RuntimeArray(runtimeScalar));
                
                if (TRACE_OVERLOAD) {
                    System.err.println("  tryOverload (0+: " + (result != null ? result : "NULL"));
                    System.err.flush();
                }
                
                if (result != null) return result;
                // Try fallback
                result = ctx.tryOverloadFallback(runtimeScalar, "(\"\"", "(bool");
                
                if (TRACE_OVERLOAD) {
                    System.err.println("  tryOverloadFallback: " + (result != null ? result : "NULL"));
                    System.err.flush();
                }
                
                if (result != null) return result;
                // Try nomethod
                result = ctx.tryOverloadNomethod(runtimeScalar, "0+");
                
                if (TRACE_OVERLOAD) {
                    System.err.println("  tryOverloadNomethod: " + (result != null ? result : "NULL"));
                    System.err.flush();
                }
                
                if (result != null) return result;
            }
        }

        // Default number conversion for non-blessed or non-overloaded objects
        // Use RuntimeScalarReference interface which both RuntimeBase and RuntimeIO implement
        RuntimeScalar defaultResult = new RuntimeScalar(((RuntimeScalarReference) runtimeScalar.value).getDoubleRef());
        
        if (TRACE_OVERLOAD) {
            System.err.println("  Returning DEFAULT hash code: " + defaultResult);
            System.err.flush();
        }
        
        return defaultResult;
    }

    /**
     * Converts a {@link RuntimeScalar} object to its boolean representation following
     * Perl's boolification rules.
     *
     * @param runtimeScalar the {@code RuntimeScalar} object to be boolified
     * @return the boolean representation based on overloading rules
     */
    public static RuntimeScalar boolify(RuntimeScalar runtimeScalar) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = RuntimeScalarType.blessedId(runtimeScalar);
        if (blessId != 0) {
            OverloadContext ctx = OverloadContext.prepare(blessId);
            if (ctx != null) {
                // Try primary overload method
                RuntimeScalar result = ctx.tryOverload("(bool", new RuntimeArray(runtimeScalar));
                if (result != null) return result;
                // Try fallback
                result = ctx.tryOverloadFallback(runtimeScalar, "(0+", "(\"\"");
                if (result != null) return result;
                // Try nomethod
                result = ctx.tryOverloadNomethod(runtimeScalar, "bool");
                if (result != null) return result;
            }
        }

        // Default bool conversion for non-blessed or non-overloaded objects
        return scalarTrue;
    }

    /**
     * Performs boolean negation on a {@link RuntimeScalar} object following
     * Perl's boolean negation rules.
     *
     * @param runtimeScalar the {@code RuntimeScalar} object to be negated
     * @return the negated boolean representation based on overloading rules
     */
    public static RuntimeScalar bool_not(RuntimeScalar runtimeScalar) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = RuntimeScalarType.blessedId(runtimeScalar);
        if (blessId != 0) {
            OverloadContext ctx = OverloadContext.prepare(blessId);
            if (ctx != null) {
                // Try primary overload method
                RuntimeScalar result = ctx.tryOverload("(!", new RuntimeArray(runtimeScalar));
                if (result != null) return result;
                // Try fallback with negation of result
                result = ctx.tryOverloadFallback(runtimeScalar, "(bool", "(0+", "(\"\"");
                if (result != null) {
                    return result.getBoolean() ? scalarFalse : scalarTrue;
                }
                // Try nomethod
                result = ctx.tryOverloadNomethod(runtimeScalar, "!");
                if (result != null) return result;
            }
        }

        // Default bool negation for non-blessed or non-overloaded objects
        return scalarFalse;
    }
}
