package org.perlonjava.runtime;

import java.io.Serial;

import static org.perlonjava.runtime.RuntimeScalarCache.getScalarInt;

/**
 * PerlCompilerException is a custom exception class used in the Perl compiler.
 * It extends RuntimeException and provides detailed error messages
 * that include the file name, line number, and a snippet of code.
 */
public class PerlCompilerException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    // Detailed error message that includes additional context about the error
    private final String errorMessage;

    /**
     * Constructs a new PerlCompilerException using the error message utility.
     * This constructor is useful when you have a specific token index and want
     * to format the error message using a utility class.
     *
     * @param tokenIndex       the index of the token where the error occurred
     * @param message          the detail message describing the error
     * @param errorMessageUtil the utility for formatting error messages
     */
    public PerlCompilerException(int tokenIndex, String message, ErrorMessageUtil errorMessageUtil) {
        super(message);
        // Use the utility to format the error message with the token index
        this.errorMessage = errorMessageUtil.errorMessage(tokenIndex, message);
    }

    public PerlCompilerException(int tokenIndex, String message, ErrorMessageUtil errorMessageUtil, Throwable cause) {
        super(message, cause);
        // Use the utility to format the error message with the token index
        this.errorMessage = errorMessageUtil.errorMessage(tokenIndex, message);
    }

    /**
     * Constructs a new PerlCompilerException using runtime information.
     * This constructor attempts to gather caller information such as package name,
     * file name, and line number to provide more context in the error message.
     *
     * @param message the detail message describing the error
     */
    public PerlCompilerException(String message) {
        super(message);

        if (message.endsWith("\n")) {
            // Return the message as-is if it already ends with a newline
            this.errorMessage = message;
            return;
        }

        // Retrieve caller information: package name, file name, line number
        RuntimeList caller = RuntimeCode.caller(new RuntimeList(getScalarInt(0)), RuntimeContextType.LIST);

        // Check if caller information is available
        if (caller.size() < 3) {
            this.errorMessage = message + "\n";
            return;
        }

        // Extract caller information
        String packageName = caller.elements.get(0).toString();
        String fileName = caller.elements.get(1).toString();
        int line = ((RuntimeScalar) caller.elements.get(2)).getInt();

        // Construct the detailed error message with file and line information
        this.errorMessage = message + " at " + fileName + " line " + line + "\n";
    }

    /**
     * Returns the detailed error message.
     *
     * @return the detailed error message
     */
    @Override
    public String getMessage() {
        return errorMessage;
    }
}
