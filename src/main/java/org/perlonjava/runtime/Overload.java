package org.perlonjava.runtime;

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

    /**
     * Converts a {@link RuntimeScalar} object to its string representation following
     * Perl's stringification rules.
     *
     * @param runtimeScalar the {@code RuntimeScalar} object to be stringified
     * @return the string representation based on overloading rules
     */
    public static RuntimeScalar stringify(RuntimeScalar runtimeScalar) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = runtimeScalar.blessedId();
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
        return new RuntimeScalar(((RuntimeBaseEntity) runtimeScalar.value).toStringRef());
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
        int blessId = runtimeScalar.blessedId();
        if (blessId != 0) {
            OverloadContext ctx = OverloadContext.prepare(blessId);
            if (ctx != null) {
                // Try primary overload method
                RuntimeScalar result = ctx.tryOverload("(0+", new RuntimeArray(runtimeScalar));
                if (result != null) return result;
                // Try fallback
                result = ctx.tryOverloadFallback(runtimeScalar, "(\"\"", "(bool");
                if (result != null) return result;
                // Try nomethod
                result = ctx.tryOverloadNomethod(runtimeScalar, "0+");
                if (result != null) return result;
            }
        }

        // Default number conversion for non-blessed or non-overloaded objects
        return new RuntimeScalar(((RuntimeBaseEntity) runtimeScalar.value).getDoubleRef());
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
        int blessId = runtimeScalar.blessedId();
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
        int blessId = runtimeScalar.blessedId();
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
