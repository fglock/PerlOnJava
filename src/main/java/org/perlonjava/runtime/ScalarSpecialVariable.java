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
        throw new PerlCompilerException("Modification of a read-only value attempted");
    }

    /**
     * Adds the string value of this special variable to another scalar variable.
     *
     * @param var The scalar variable to which the value will be added.
     * @return The updated scalar variable.
     */
    public RuntimeScalar addToScalar(RuntimeScalar var) {
        return this.getValueAsScalar().addToScalar(var);
    }

    /**
     * Retrieves the RuntimeScalar value of the special variable based on its type.
     *
     * @return The RuntimeScalar value of the special variable, or null if not available.
     */
    private RuntimeScalar getValueAsScalar() {
        try {
            return switch (variableId) {
                case CAPTURE -> new RuntimeScalar(RuntimeRegex.captureString(position));
                case MATCH -> new RuntimeScalar(RuntimeRegex.matchString());
                case PREMATCH -> new RuntimeScalar(RuntimeRegex.preMatchString());
                case POSTMATCH -> new RuntimeScalar(RuntimeRegex.postMatchString());
                default -> null;
            };
        } catch (IllegalStateException e) {
            return null;
        }
    }

    /**
     * Retrieves the integer representation of the special variable.
     *
     * @return The integer value of the special variable.
     */
    @Override
    public int getInt() {
        return this.getValueAsScalar().getInt();
    }

    /**
     * Retrieves the double representation of the special variable.
     *
     * @return The double value of the special variable.
     */
    @Override
    public double getDouble() {
        return this.getValueAsScalar().getDouble();
    }

    /**
     * Returns the string representation of the special variable.
     *
     * @return The string value of the special variable, or an empty string if null.
     */
    @Override
    public String toString() {
        return this.getValueAsScalar().toString();
    }

    /**
     * Evaluates the boolean representation of the special variable.
     *
     * @return True if the string value is not null, not empty, and not "0".
     */
    @Override
    public boolean getBoolean() {
        return this.getValueAsScalar().getBoolean();
    }

    /**
     * Checks if the special variable is defined.
     *
     * @return True if the string value is not null.
     */
    @Override
    public boolean getDefinedBoolean() {
        return this.getValueAsScalar().getDefinedBoolean();
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
