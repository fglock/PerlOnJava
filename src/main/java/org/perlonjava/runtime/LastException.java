package org.perlonjava.runtime;

/**
 * Exception thrown by 'last LABEL' for non-local jumps.
 * This exception is used when a 'last' statement targets a label
 * that is not in the current lexical scope but in an outer call frame.
 */
public class LastException extends PerlControlFlowException {
    /**
     * Constructs a LastException with the specified target label.
     * 
     * @param targetLabel The label to jump to, or null for unlabeled 'last'
     */
    public LastException(String targetLabel) {
        super(targetLabel);
    }
}

