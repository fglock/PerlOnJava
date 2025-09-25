package org.perlonjava.runtime;

import org.perlonjava.regex.RuntimeRegex;

import static org.perlonjava.runtime.RuntimeScalarCache.getScalarInt;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;

/**
 * Represents a Perl special scalar variable, such as $`, $&, $', or $1.
 * These variables are used to capture specific parts of a string during regex operations.
 * The class extends RuntimeBaseProxy to provide access to these special variables.
 *
 * <p>This class provides functionality to handle special scalar variables in Perl,
 * which are typically used in the context of regular expression operations. Each
 * special variable has a specific role, such as capturing matched substrings or
 * parts of the string before or after a match.</p>
 */
public class ScalarSpecialVariable extends RuntimeBaseProxy {

    // The type of special variable, represented by an enum.
    final Id variableId;

    // The position of the capture group, used only for CAPTURE type variables.
    final int position;

    /**
     * Constructs a ScalarSpecialVariable for a specific type of special variable.
     *
     * @param variableId The type of special variable (e.g., PREMATCH, MATCH, POSTMATCH).
     */
    public ScalarSpecialVariable(Id variableId) {
        super();
        this.variableId = variableId;
        this.position = 0; // Default position is 0 for non-capture variables.
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
     *
     * <p>This method is overridden to prevent modification of the special
     * variable, as these are intended to be read-only.</p>
     */
    @Override
    void vivify() {
        throw new PerlCompilerException("Modification of a read-only value attempted");
    }

    // Add itself to a RuntimeArray.
    public void addToArray(RuntimeArray array) {
        array.elements.add(new RuntimeScalar(this.getValueAsScalar()));
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
            System.err.println("DEBUG: ScalarSpecialVariable.getValueAsScalar() called for " + variableId);
            RuntimeScalar result = switch (variableId) {
                case CAPTURE -> {
                    String capture = RuntimeRegex.captureString(position);
                    yield capture != null ? new RuntimeScalar(capture) : scalarUndef;
                }
                case MATCH -> {
                    String match = RuntimeRegex.matchString();
                    yield match != null ? new RuntimeScalar(match) : scalarUndef;
                }
                case PREMATCH -> {
                    String prematch = RuntimeRegex.preMatchString();
                    yield prematch != null ? new RuntimeScalar(prematch) : scalarUndef;
                }
                case POSTMATCH -> {
                    String postmatch = RuntimeRegex.postMatchString();
                    yield postmatch != null ? new RuntimeScalar(postmatch) : scalarUndef;
                }
                case LAST_FH -> new RuntimeScalar(RuntimeIO.lastAccesseddHandle);
                case INPUT_LINE_NUMBER -> RuntimeIO.lastAccesseddHandle == null
                        ? scalarUndef
                        : getScalarInt(RuntimeIO.lastAccesseddHandle.currentLineNumber);
                case LAST_PAREN_MATCH -> {
                    String lastCapture = RuntimeRegex.lastCaptureString();
                    yield lastCapture != null ? new RuntimeScalar(lastCapture) : scalarUndef;
                }
                case LAST_SUCCESSFUL_PATTERN -> new RuntimeScalar(RuntimeRegex.lastSuccessfulPattern);
            };
            System.err.println("DEBUG: ScalarSpecialVariable.getValueAsScalar() returning: " + (result.getDefinedBoolean() ? "'" + result.toString() + "'" : "UNDEF"));
            return result;
        } catch (IllegalStateException e) {
            System.err.println("DEBUG: ScalarSpecialVariable.getValueAsScalar() caught IllegalStateException: " + e.getMessage());
            return scalarUndef;
        }
    }

    public RuntimeScalar getNumber() {
        return this.getValueAsScalar().getNumber();
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
     * @return The string value of the special variable.
     */
    @Override
    public String toString() {
        return this.getValueAsScalar().toString();
    }

    /**
     * Evaluates the boolean representation of the special variable.
     *
     * @return True value of the special variable.
     */
    @Override
    public boolean getBoolean() {
        return this.getValueAsScalar().getBoolean();
    }

    /**
     * Checks if the special variable is defined.
     *
     * @return True if the value is not null.
     */
    @Override
    public boolean getDefinedBoolean() {
        return this.getValueAsScalar().getDefinedBoolean();
    }

    /**
     * Get the special variable as a file handle.
     *
     * @return The file handle associated with the special variable.
     */
    @Override
    public RuntimeIO getRuntimeIO() {
        return this.getValueAsScalar().getRuntimeIO();
    }

    /**
     * Adds this entity to the specified RuntimeList.
     *
     * @param list the RuntimeList to which this entity will be added
     */
    @Override
    public void addToList(RuntimeList list) {
        list.add(this.getValueAsScalar());
    }

    /**
     * Enum to represent the id of the special variable.
     *
     * <p>This enum defines the different types of special variables that can be
     * represented by this class, each corresponding to a specific role in regex
     * operations or file handling.</p>
     */
    public enum Id {
        CAPTURE,   // Represents a captured substring.
        PREMATCH,  // Represents the part of the string before the matched substring.
        MATCH,     // Represents the matched substring.
        POSTMATCH, // Represents the part of the string after the matched substring.
        LAST_FH,    // Represents the last filehandle used in an input operation.
        INPUT_LINE_NUMBER, // Represents the current line number in an input operation.
        LAST_PAREN_MATCH, // The highest capture variable ($1, $2, ...) which has a defined value.
        LAST_SUCCESSFUL_PATTERN, // ${^LAST_SUCCESSFUL_PATTERN}
    }
}
