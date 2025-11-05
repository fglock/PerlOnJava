package org.perlonjava.runtime;

/**
 * Base exception for non-local control flow operations.
 * These exceptions are used to implement next/last/redo/goto across method boundaries.
 * 
 * The exceptions are created without stack traces for performance optimization,
 * as they are not actual errors but control flow mechanisms.
 */
public abstract class ControlFlowException extends RuntimeException {
    protected final String targetLabel;  // null for unlabeled next/last/redo

    /**
     * Constructs a control flow exception with the specified target label.
     * 
     * @param targetLabel The label to jump to, or null for unlabeled statements
     */
    public ControlFlowException(String targetLabel) {
        super(null, null, false, false);  // No stack trace (performance optimization)
        this.targetLabel = targetLabel;
    }

    /**
     * Gets the target label for this control flow exception.
     * 
     * @return The target label name, or null for unlabeled statements
     */
    public String getTargetLabel() {
        return targetLabel;
    }

    /**
     * Checks if this exception should be caught by the given label.
     * 
     * @param labelName The label name to check against
     * @return true if this exception matches the given label
     */
    public boolean matchesLabel(String labelName) {
        // Unlabeled statements match the innermost loop
        if (targetLabel == null) {
            return labelName == null;
        }
        // Labeled statements must match exactly
        return targetLabel.equals(labelName);
    }
}

