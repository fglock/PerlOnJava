package org.perlonjava.runtime;

/**
 * DualVar represents a Perl scalar that has both numeric and string values.
 * It returns the numeric value in numeric context and string value in string context.
 */
public class DualVar {
    public final RuntimeScalar numericValue;
    public final RuntimeScalar stringValue;

    public DualVar(RuntimeScalar num, RuntimeScalar str) {
        this.numericValue = num;
        this.stringValue = str;
    }

    @Override
    public String toString() {
        return stringValue.toString();
    }
}