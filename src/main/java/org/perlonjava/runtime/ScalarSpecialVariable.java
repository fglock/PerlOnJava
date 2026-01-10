package org.perlonjava.runtime;

import org.perlonjava.regex.RuntimeRegex;

import java.util.Stack;

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

    private record InputLineState(RuntimeIO lastHandle, int lastLineNumber, RuntimeScalar localValue) {
    }

    private static final Stack<InputLineState> inputLineStateStack = new Stack<>();

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
        if (variableId == Id.INPUT_LINE_NUMBER) {
            if (lvalue == null) {
                lvalue = new RuntimeScalar(0);
            }
            return;
        }
        throw new PerlCompilerException("Modification of a read-only value attempted");
    }

    @Override
    public RuntimeScalar set(RuntimeScalar value) {
        if (variableId == Id.INPUT_LINE_NUMBER) {
            vivify();
            if (RuntimeIO.lastAccesseddHandle != null) {
                RuntimeIO.lastAccesseddHandle.currentLineNumber = value.getInt();
                lvalue.set(RuntimeIO.lastAccesseddHandle.currentLineNumber);
            } else {
                lvalue.set(value);
            }
            this.type = lvalue.type;
            this.value = lvalue.value;
            return lvalue;
        }
        return super.set(value);
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
                case INPUT_LINE_NUMBER -> {
                    if (RuntimeIO.lastAccesseddHandle == null) {
                        if (lvalue != null) {
                            yield lvalue;
                        }
                        yield scalarUndef;
                    }
                    yield getScalarInt(RuntimeIO.lastAccesseddHandle.currentLineNumber);
                }
                case LAST_PAREN_MATCH -> {
                    String lastCapture = RuntimeRegex.lastCaptureString();
                    yield lastCapture != null ? new RuntimeScalar(lastCapture) : scalarUndef;
                }
                case LAST_SUCCESSFUL_PATTERN -> new RuntimeScalar(RuntimeRegex.lastSuccessfulPattern);
                case LAST_REGEXP_CODE_RESULT -> {
                    // $^R - Result of last (?{...}) code block
                    // Get the last matched regex and retrieve its code block result
                    if (RuntimeRegex.lastSuccessfulPattern != null) {
                        RuntimeScalar codeBlockResult = RuntimeRegex.lastSuccessfulPattern.getLastCodeBlockResult();
                        yield codeBlockResult != null ? codeBlockResult : scalarUndef;
                    }
                    yield scalarUndef;
                }
            };
            return result;
        } catch (IllegalStateException e) {
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
     * Retrieves the long representation of the special variable.
     *
     * @return The long value of the special variable.
     */
    @Override
    public long getLong() {
        return this.getValueAsScalar().getLong();
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
     * Saves the current state of the RuntimeScalar instance.
     *
     * <p>This method creates a snapshot of the current type and value of the scalar,
     * and pushes it onto a static stack for later restoration.
     */
    @Override
    public void dynamicSaveState() {
        if (variableId == Id.INPUT_LINE_NUMBER) {
            RuntimeIO handle = RuntimeIO.lastAccesseddHandle;
            int lineNumber = handle != null ? handle.currentLineNumber : (lvalue != null ? lvalue.getInt() : 0);
            RuntimeScalar localValue = lvalue != null ? new RuntimeScalar(lvalue) : null;
            inputLineStateStack.push(new InputLineState(handle, lineNumber, localValue));
            return;
        }
        super.dynamicSaveState();
    }

    /**
     * Restores the most recently saved state of the RuntimeScalar instance.
     *
     * <p>This method pops the most recent state from the static stack and restores
     * the type and value to the current scalar. If no state is saved, it does nothing.
     */
    @Override
    public void dynamicRestoreState() {
        if (variableId == Id.INPUT_LINE_NUMBER) {
            if (!inputLineStateStack.isEmpty()) {
                InputLineState previous = inputLineStateStack.pop();
                RuntimeIO.lastAccesseddHandle = previous.lastHandle;
                if (previous.lastHandle != null) {
                    previous.lastHandle.currentLineNumber = previous.lastLineNumber;
                }
                lvalue = previous.localValue;
                if (lvalue != null) {
                    this.type = lvalue.type;
                    this.value = lvalue.value;
                }
            }
            return;
        }
        super.dynamicRestoreState();
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
        LAST_REGEXP_CODE_RESULT, // $^R - Result of last (?{...}) code block in regex
    }
}
