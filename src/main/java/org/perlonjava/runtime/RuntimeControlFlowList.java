package org.perlonjava.runtime;

/**
 * A specialized RuntimeList that carries control flow information.
 * This is returned by control flow statements (last/next/redo/goto/goto &NAME)
 * to signal non-local control flow across subroutine boundaries.
 */
public class RuntimeControlFlowList extends RuntimeList {
    /** The control flow marker with type and label/codeRef information */
    public final ControlFlowMarker marker;
    
    /**
     * Constructor for control flow (last/next/redo/goto).
     * 
     * @param type The control flow type
     * @param label The label to jump to (null for unlabeled)
     */
    public RuntimeControlFlowList(ControlFlowType type, String label) {
        super();
        this.marker = new ControlFlowMarker(type, label);
    }
    
    /**
     * Constructor for tail call (goto &NAME).
     * 
     * @param codeRef The code reference to call
     * @param args The arguments to pass
     */
    public RuntimeControlFlowList(RuntimeScalar codeRef, RuntimeArray args) {
        super();
        this.marker = new ControlFlowMarker(codeRef, args);
    }
    
    /**
     * Get the control flow type.
     * 
     * @return The control flow type
     */
    public ControlFlowType getControlFlowType() {
        return marker.type;
    }
    
    /**
     * Get the control flow label.
     * 
     * @return The label, or null if unlabeled
     */
    public String getControlFlowLabel() {
        return marker.label;
    }
    
    /**
     * Get the tail call code reference.
     * 
     * @return The code reference, or null if not a tail call
     */
    public RuntimeScalar getTailCallCodeRef() {
        return marker.codeRef;
    }
    
    /**
     * Get the tail call arguments.
     * 
     * @return The arguments, or null if not a tail call
     */
    public RuntimeArray getTailCallArgs() {
        return marker.args;
    }
}

