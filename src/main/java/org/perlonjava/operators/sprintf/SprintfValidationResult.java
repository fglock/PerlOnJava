package org.perlonjava.operators.sprintf;

/**
 * Result of validating a sprintf format specifier
 */
public record SprintfValidationResult(Status status, String errorMessage) {
    public SprintfValidationResult(Status status) {
        this(status, null);
    }

    public boolean isValid() {
        return status == Status.VALID;
    }

    public enum Status {
        VALID,                    // Format is valid
        INVALID_APPEND_ERROR,     // Invalid format, append " INVALID" to output
        INVALID_NO_APPEND,        // Invalid format, but don't append " INVALID" (e.g., space formats)
        INVALID_MISSING_ARG       // Will result in " MISSING" output
    }

}