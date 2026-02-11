package org.perlonjava.interpreter;

/**
 * Container for interpreted bytecode.
 *
 * This class holds the bytecode, constant pool, and metadata needed
 * to execute Perl code in the interpreter.
 */
public class InterpretedCode {
    public final byte[] bytecode;        // Instruction opcodes
    public final Object[] constants;     // Constant pool (mixed objects)
    public final int[] intPool;          // Integer constants (avoid boxing)
    public final String[] stringPool;    // String constants
    public final int maxLocals;          // Number of local variable slots
    public final int maxStack;           // Max operand stack depth

    public InterpretedCode(
        byte[] bytecode,
        Object[] constants,
        int[] intPool,
        String[] stringPool,
        int maxLocals,
        int maxStack
    ) {
        this.bytecode = bytecode;
        this.constants = constants;
        this.intPool = intPool;
        this.stringPool = stringPool;
        this.maxLocals = maxLocals;
        this.maxStack = maxStack;
    }

    /**
     * Simple builder for constructing InterpretedCode during benchmarking.
     */
    public static class Builder {
        private byte[] bytecode;
        private Object[] constants = new Object[0];
        private int[] intPool = new int[0];
        private String[] stringPool = new String[0];
        private int maxLocals = 10;
        private int maxStack = 10;

        public Builder bytecode(byte[] bytecode) {
            this.bytecode = bytecode;
            return this;
        }

        public Builder maxLocals(int maxLocals) {
            this.maxLocals = maxLocals;
            return this;
        }

        public Builder maxStack(int maxStack) {
            this.maxStack = maxStack;
            return this;
        }

        public Builder intPool(int[] intPool) {
            this.intPool = intPool;
            return this;
        }

        public InterpretedCode build() {
            return new InterpretedCode(bytecode, constants, intPool, stringPool, maxLocals, maxStack);
        }
    }
}
