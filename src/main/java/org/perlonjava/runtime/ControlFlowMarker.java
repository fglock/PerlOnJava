package org.perlonjava.runtime;

/**
 * Marker class that holds control flow information for non-local control flow.
 * This is attached to RuntimeList objects to indicate they represent a control flow action.
 */
public class ControlFlowMarker {
    /** The type of control flow (LAST, NEXT, REDO, GOTO, TAILCALL) */
    public final ControlFlowType type;
    
    /** The label for LAST/NEXT/REDO/GOTO (null for unlabeled control flow) */
    public final String label;
    
    /** The code reference for TAILCALL (goto &NAME) */
    public final RuntimeScalar codeRef;
    
    /** The arguments for TAILCALL (goto &NAME) */
    public final RuntimeArray args;
    
    /**
     * Constructor for control flow (last/next/redo/goto).
     * 
     * @param type The control flow type
     * @param label The label to jump to (null for unlabeled)
     */
    public ControlFlowMarker(ControlFlowType type, String label) {
        this.type = type;
        this.label = label;
        this.codeRef = null;
        this.args = null;
    }
    
    /**
     * Constructor for tail call (goto &NAME).
     * 
     * @param codeRef The code reference to call
     * @param args The arguments to pass
     */
    public ControlFlowMarker(RuntimeScalar codeRef, RuntimeArray args) {
        this.type = ControlFlowType.TAILCALL;
        this.label = null;
        this.codeRef = codeRef;
        this.args = args;
    }
}

