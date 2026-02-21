package org.perlonjava.runtime.runtimetypes;

/**
 * Enum representing the type of non-local control flow.
 * Used to mark RuntimeList objects with control flow information.
 */
public enum ControlFlowType {
    /** Exit loop immediately (like C's break) */
    LAST,
    
    /** Start next iteration of loop (like C's continue) */
    NEXT,
    
    /** Restart loop block without re-evaluating conditional */
    REDO,
    
    /** Jump to a labeled statement */
    GOTO,
    
    /** Tail call optimization for goto &NAME */
    TAILCALL
}

