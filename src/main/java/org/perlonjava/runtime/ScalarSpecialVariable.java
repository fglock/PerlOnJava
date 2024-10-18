package org.perlonjava.runtime;

/**
 * Represents a Perl special scalar variable, such as $`, $&, $', or $1.
 * These variables are used to capture specific parts of a string during regex operations.
 * The class extends RuntimeBaseProxy to provide access to these special variables.
 */
public class ScalarSpecialVariable extends RuntimeBaseProxy {

    // The type of special variable, represented by an enum.
    final Id variableId;
    final int position;

    /**
     * Constructs a ScalarSpecialVariable for a specific type of special variable.
     *
     * @param variableId The type of special variable (e.g., PREMATCH, MATCH, POSTMATCH).
     */
    public ScalarSpecialVariable(Id variableId) {
        super();
        this.variableId = variableId;
        this.position = 0;
    }

    /**
     * Constructs a ScalarSpecialVariable for a specific capture group position.
     *
     * @param variableId The type of special variable (e.g., CAPTURE).
     * @param position   The position of the capture group.
     */
    public ScalarSpecialVariable(Id variableId, int position) {
        super();
        this.variableId = variableId;
        this.position = position;
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
     * Adds the string value of this special variable to another scalar variable.
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
     * Retrieves the string value of the special variable based on its type.
     *
     * @return The string value of the special variable, or null if not available.
     */
    public String getStringValue() {
        try {
            // Return the appropriate string based on the type of special variable.
            switch (variableId) {
                case CAPTURE:
                    return RuntimeRegex.captureString(position);
                case MATCH:
                    return RuntimeRegex.matchString();
                case PREMATCH:
                    return RuntimeRegex.preMatchString();
                case POSTMATCH:
                    return RuntimeRegex.postMatchString();
                default:
                    return null;
            }
        } catch (IllegalStateException e) {
            return null; // Return null if the matcher is in an invalid state.
        }
    }

    /**
     * Retrieves the integer representation of the special variable.
     *
     * @return The integer value of the special variable.
     */
    @Override
    public int getInt() {
        return new RuntimeScalar(this.getStringValue()).getInt();
    }

    /**
     * Retrieves the double representation of the special variable.
     *
     * @return The double value of the special variable.
     */
    @Override
    public double getDouble() {
        return new RuntimeScalar(this.getStringValue()).getDouble();
    }

    /**
     * Returns the string representation of the special variable.
     *
     * @return The string value of the special variable, or an empty string if null.
     */
    @Override
    public String toString() {
        String str = this.getStringValue();
        return str == null ? "" : str;
    }

    /**
     * Evaluates the boolean representation of the special variable.
     *
     * @return True if the string value is not null, not empty, and not "0".
     */
    @Override
    public boolean getBoolean() {
        String str = this.getStringValue();
        return (str != null && !str.isEmpty() && !str.equals("0"));
    }

    /**
     * Checks if the special variable is defined.
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
        CAPTURE,   // Represents a captured substring.
        PREMATCH,  // Represents the part of the string before the matched substring.
        MATCH,     // Represents the matched substring.
        POSTMATCH  // Represents the part of the string after the matched substring.
    }
}