package org.perlonjava.codegen;

import org.perlonjava.runtime.RuntimeCode;

import java.lang.invoke.MethodHandle;

/**
 * Compiled bytecode that extends RuntimeCode.
 *
 * This class represents Perl code that has been compiled to JVM bytecode.
 * It wraps the generated Class<?> and provides the same RuntimeCode interface
 * as InterpretedCode, enabling seamless switching between compiler and interpreter.
 *
 * DESIGN: Following the InterpretedCode pattern:
 * - InterpretedCode stores bytecode[] and overrides apply() to call BytecodeInterpreter
 * - CompiledCode stores Class<?> and uses parent apply() to call MethodHandle
 *
 * This allows the EmitterMethodCreator.createRuntimeCode() factory to return either
 * CompiledCode or InterpretedCode based on whether compilation succeeded or fell
 * back to the interpreter.
 */
public class CompiledCode extends RuntimeCode {
    // The generated JVM class (useful for debugging and EmitSubroutine bytecode generation)
    public final Class<?> generatedClass;

    // The compiler context used to create this code (may be useful for debugging)
    public final EmitterContext compileContext;

    /**
     * Constructor for CompiledCode.
     *
     * @param methodHandle The MethodHandle for the apply() method
     * @param codeObject   The instance of the generated class (with closure variables)
     * @param prototype    The subroutine prototype (e.g., "$" for one scalar parameter)
     * @param generatedClass The compiled JVM class
     * @param compileContext The compiler context (optional, for debugging)
     */
    public CompiledCode(MethodHandle methodHandle, Object codeObject,
                       String prototype, Class<?> generatedClass,
                       EmitterContext compileContext) {
        super(methodHandle, codeObject, prototype);
        this.generatedClass = generatedClass;
        this.compileContext = compileContext;
    }

    // No need to override apply() - parent RuntimeCode implementation works perfectly
    // The MethodHandle dispatches to compiled JVM bytecode automatically

    @Override
    public String toString() {
        return "CompiledCode{" +
               "class=" + (generatedClass != null ? generatedClass.getName() : "null") +
               ", prototype='" + prototype + '\'' +
               ", defined=" + defined() +
               '}';
    }
}
