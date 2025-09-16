package org.perlonjava.runtime;

/**
 * DualVar represents a Perl scalar that has both numeric and string values.
 * It returns the numeric value in numeric context and string value in string context.
 */
public record DualVar(RuntimeScalar numericValue, RuntimeScalar stringValue) {

    @Override
    public String toString() {
        return stringValue.toString();
    }
}