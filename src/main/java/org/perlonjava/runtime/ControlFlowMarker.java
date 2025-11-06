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
    
    /** Source file name where the control flow originated (for error messages) */
    public final String fileName;
    
    /** Line number where the control flow originated (for error messages) */
    public final int lineNumber;
    
    /**
     * Constructor for control flow (last/next/redo/goto).
     * 
     * @param type The control flow type
     * @param label The label to jump to (null for unlabeled)
     * @param fileName Source file name (for error messages)
     * @param lineNumber Line number (for error messages)
     */
    public ControlFlowMarker(ControlFlowType type, String label, String fileName, int lineNumber) {
        this.type = type;
        this.label = label;
        this.fileName = fileName;
        this.lineNumber = lineNumber;
        this.codeRef = null;
        this.args = null;
    }
    
    /**
     * Constructor for tail call (goto &NAME).
     * 
     * @param codeRef The code reference to call
     * @param args The arguments to pass
     * @param fileName Source file name (for error messages)
     * @param lineNumber Line number (for error messages)
     */
    public ControlFlowMarker(RuntimeScalar codeRef, RuntimeArray args, String fileName, int lineNumber) {
        this.type = ControlFlowType.TAILCALL;
        this.label = null;
        this.fileName = fileName;
        this.lineNumber = lineNumber;
        this.codeRef = codeRef;
        this.args = args;
    }
    
    /**
     * Debug method to print control flow information.
     */
    public void debugPrint(String context) {
        System.err.println("[DEBUG] " + context + ": type=" + type + 
                          ", label=" + label + 
                          ", codeRef=" + (codeRef != null ? codeRef : "null") +
                          ", args=" + (args != null ? args.size() : "null") +
                          " @ " + fileName + ":" + lineNumber);
    }
    
    /**
     * Throws an appropriate PerlCompilerException for this control flow that couldn't be handled.
     * Includes source file and line number in the error message.
     * 
     * @throws PerlCompilerException Always throws with contextual error message
     */
    public void throwError() {
        String location = " at " + fileName + " line " + lineNumber;
        
        if (type == ControlFlowType.TAILCALL) {
            // Tail call should have been handled by trampoline at returnLabel
            throw new PerlCompilerException("Tail call escaped to top level (internal error)" + location);
        } else if (type == ControlFlowType.GOTO) {
            if (label != null) {
                throw new PerlCompilerException("Can't find label " + label + location);
            } else {
                throw new PerlCompilerException("goto must have a label" + location);
            }
        } else {
            // last/next/redo
            String operation = type.name().toLowerCase();
            if (label != null) {
                throw new PerlCompilerException("Label not found for \"" + operation + " " + label + "\"" + location);
            } else {
                throw new PerlCompilerException("Can't \"" + operation + "\" outside a loop block" + location);
            }
        }
    }
}

