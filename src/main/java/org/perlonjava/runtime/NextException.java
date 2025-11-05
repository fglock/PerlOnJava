package org.perlonjava.runtime;

/**
 * Exception thrown by 'next LABEL' for non-local jumps.
 * This exception is used when a 'next' statement targets a label
 * that is not in the current lexical scope but in an outer call frame.
 */
public class NextException extends ControlFlowException {
    /**
     * Constructs a NextException with the specified target label.
     * 
     * @param targetLabel The label to jump to, or null for unlabeled 'next'
     */
    public NextException(String targetLabel) {
        super(targetLabel);
    }
}

