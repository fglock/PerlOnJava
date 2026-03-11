package org.perlonjava.backend.jvm;

import org.perlonjava.backend.bytecode.InterpretedCode;

/**
 * Exception thrown when JVM bytecode compilation fails (e.g., ASM frame computation crash)
 * and the code has been compiled to InterpretedCode as a fallback.
 * 
 * <p>This allows callers like EmitSubroutine to catch this exception and generate
 * bytecode that uses the InterpretedCode instead of a JVM-compiled class.</p>
 */
public class InterpreterFallbackException extends RuntimeException {
    
    /** The InterpretedCode that was compiled as a fallback */
    public final InterpretedCode interpretedCode;
    
    /** The variable names array for closure support */
    public final String[] envNames;
    
    public InterpreterFallbackException(InterpretedCode interpretedCode, String[] envNames) {
        super("JVM compilation failed, using interpreter fallback");
        this.interpretedCode = interpretedCode;
        this.envNames = envNames;
    }
}
