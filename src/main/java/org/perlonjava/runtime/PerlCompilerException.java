package org.perlonjava.runtime;

/**
 * PerlCompilerException is a custom exception class used in the Perl compiler.
 * It extends RuntimeException and provides detailed error messages
 * that include the file name, line number and a snippet of code.
 */
public class PerlCompilerException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String errorMessage;

    /**
     * Constructs a new PerlCompilerException using the error message utility
     *
     * @param tokenIndex       the index of the token where the error occurred
     * @param message          the detail message
     * @param errorMessageUtil the utility for formatting error messages
     */
    public PerlCompilerException(int tokenIndex, String message, ErrorMessageUtil errorMessageUtil) {
        super(message);
        this.errorMessage = errorMessageUtil.errorMessage(tokenIndex, message);
    }

    /**
     * Constructs a new PerlCompilerException using runtime information
     *
     * @param message          the detail message
     */
    public PerlCompilerException(String message) {
        super(message);
        // get caller information: package name, file name, line number
        RuntimeList caller = RuntimeScalar.caller(new RuntimeList(), RuntimeContextType.LIST);
        String packageName = caller.elements.get(0).toString();
        String fileName = caller.elements.get(1).toString();
        int line = ((RuntimeScalar) caller.elements.get(2)).getInt();
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

