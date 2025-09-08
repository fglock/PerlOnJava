package org.perlonjava.operators;

/**
 * Result of validating a sprintf format specifier
 */
public class SprintfValidationResult {
    public enum Status {
        VALID,                    // Format is valid
        INVALID_APPEND_ERROR,     // Invalid format, append " INVALID" to output
        INVALID_NO_APPEND,        // Invalid format, but don't append " INVALID" (e.g., space formats)
        INVALID_MISSING_ARG       // Will result in " MISSING" output
    }

    private final Status status;
    private final String errorMessage;

    public SprintfValidationResult(Status status) {
        this(status, null);
    }

    public SprintfValidationResult(Status status, String errorMessage) {
        this.status = status;
        this.errorMessage = errorMessage;
    }

    public Status getStatus() {
        return status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public boolean isValid() {
        return status == Status.VALID;
    }

}