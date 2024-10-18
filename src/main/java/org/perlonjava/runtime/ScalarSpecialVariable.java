package org.perlonjava.runtime;

import java.util.regex.Matcher;

/**
 * Represents a Perl special scalar variable like $`, $&, $'
 */
public class ScalarSpecialVariable extends RuntimeBaseProxy {

    // The type of special variable.
    final Id variableId;

    /**
     * Constructs a ScalarSpecialVariable.
     *
     * @param variableId The type of special variable.
     */
    public ScalarSpecialVariable(Id variableId) {
        super();
        this.variableId = variableId;
    }

    /**
     * Throws an exception as this variable represents a constant item
     * and cannot be modified.
     */
    @Override
    void vivify() {
        throw new RuntimeException("Can't modify constant item $" + variableId);
    }

    /**
     * Adds the string value of this regex variable to another scalar variable.
     *
     * @param var The scalar variable to which the value will be added.
     * @return The updated scalar variable.
     */
    public RuntimeScalar addToScalar(RuntimeScalar var) {
        String str = this.getStringValue();
        if (str == null) {
            var.undefine(); // Undefine the variable if the string value is null.
        } else {
            var.set(str); // Set the string value to the variable.
        }
        return var;
    }

    /**
     * Retrieves the string value of the regex match group at the specified position.
     *
     * @return The string value of the match group, or null if not available.
     */
    public String getStringValue() {
        try {
            // return null or String
            switch (variableId) {
                case MATCH:
                    Matcher matcher = RuntimeRegex.globalMatcher;
                    return matcher == null ? null : matcher.group();
                case PREMATCH:
                    matcher = RuntimeRegex.globalMatcher;
                    return matcher.group();
                case POSTMATCH:
                    matcher = RuntimeRegex.globalMatcher;
                    return matcher.group();
                default:
                    return null;
            }
        } catch (IllegalStateException e) {
            return null; // Return null if the matcher is in an invalid state.
        }
    }

    /**
     * Retrieves the integer representation of the regex match group.
     *
     * @return The integer value of the match group.
     */
    @Override
    public int getInt() {
        return new RuntimeScalar(this.getStringValue()).getInt();
    }

    /**
     * Retrieves the double representation of the regex match group.
     *
     * @return The double value of the match group.
     */
    @Override
    public double getDouble() {
        return new RuntimeScalar(this.getStringValue()).getDouble();
    }

    /**
     * Returns the string representation of the regex match group.
     *
     * @return The string value of the match group, or an empty string if null.
     */
    @Override
    public String toString() {
        String str = this.getStringValue();
        return str == null ? "" : str;
    }

    /**
     * Evaluates the boolean representation of the regex match group.
     *
     * @return True if the string value is not null, not empty, and not "0".
     */
    @Override
    public boolean getBoolean() {
        String str = this.getStringValue();
        return (str != null && !str.isEmpty() && !str.equals("0"));
    }

    /**
     * Checks if the regex match group is defined.
     *
     * @return True if the string value is not null.
     */
    @Override
    public boolean getDefinedBoolean() {
        String str = this.getStringValue();
        return (str != null);
    }

    /**
     * Enum to represent the id of the special variable.
     */
    public enum Id {
        PREMATCH,  // $` Represents the part of the string before the matched substring.
        MATCH,     // $& Represents the matched substring.
        POSTMATCH, // $' Represents the part of the string after the matched substring.
    }

}