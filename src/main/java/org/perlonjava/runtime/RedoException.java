package org.perlonjava.runtime;

/**
 * Exception thrown by 'redo LABEL' for non-local jumps.
 * This exception is used when a 'redo' statement targets a label
 * that is not in the current lexical scope but in an outer call frame.
 */
public class RedoException extends PerlControlFlowException {
    /**
     * Constructs a RedoException with the specified target label.
     * 
     * @param targetLabel The label to jump to, or null for unlabeled 'redo'
     */
    public RedoException(String targetLabel) {
        super(targetLabel);
    }
}

