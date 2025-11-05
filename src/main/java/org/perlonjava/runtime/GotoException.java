package org.perlonjava.runtime;

/**
 * Exception thrown by 'goto LABEL' for non-local jumps.
 * This exception is used when a 'goto' statement targets a label
 * that is not in the current lexical scope but in an outer call frame.
 */
public class GotoException extends ControlFlowException {
    /**
     * Constructs a GotoException with the specified target label.
     * 
     * @param targetLabel The label to jump to
     */
    public GotoException(String targetLabel) {
        super(targetLabel);
    }
}

