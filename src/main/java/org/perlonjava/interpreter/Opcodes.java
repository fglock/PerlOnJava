package org.perlonjava.interpreter;

/**
 * Bytecode opcodes for the PerlOnJava interpreter.
 *
 * Design: Dense opcodes (0-255) to enable JVM tableswitch optimization.
 * Register-machine architecture minimizes stack operations.
 */
public class Opcodes {
    // Control flow
    public static final byte NOP = 0;           // No operation
    public static final byte RETURN = 1;         // Return from subroutine
    public static final byte JUMP = 2;           // Unconditional jump
    public static final byte JUMP_IF_FALSE = 3;  // Conditional jump

    // Variable access (register machine)
    public static final byte LOAD_LOCAL = 10;    // Load local variable to stack
    public static final byte STORE_LOCAL = 11;   // Store stack top to local variable
    public static final byte LOAD_GLOBAL = 12;   // Load global variable
    public static final byte STORE_GLOBAL = 13;  // Store to global variable

    // Constants
    public static final byte LOAD_INT = 20;      // Load integer constant
    public static final byte LOAD_STRING = 21;   // Load string constant
    public static final byte LOAD_CONST = 22;    // Load object constant

    // Arithmetic (fast paths for common types)
    public static final byte ADD_INT = 30;       // Integer addition (no type check)
    public static final byte ADD_SCALAR = 31;    // Scalar addition (with type coercion)
    public static final byte SUB_INT = 32;       // Integer subtraction
    public static final byte MUL_INT = 33;       // Integer multiplication

    // String operations
    public static final byte CONCAT = 40;        // String concatenation
    public static final byte SUBSTR = 41;        // Substring operation

    // Subroutine calls
    public static final byte CALL_BUILTIN = 50;  // Call builtin function
    public static final byte CALL_SUB = 51;      // Call user subroutine
    public static final byte CALL_METHOD = 52;   // Call object method

    // Context operations
    public static final byte LIST_TO_SCALAR = 60;  // Convert list to scalar context
    public static final byte SCALAR_TO_LIST = 61;  // Convert scalar to list context

    // Stack operations (minimal - register machine)
    public static final byte DUP = 70;           // Duplicate stack top
    public static final byte POP = 71;           // Discard stack top

    // Print operation for benchmarking
    public static final byte PRINT = 80;         // Print value

    private Opcodes() {} // Utility class
}
