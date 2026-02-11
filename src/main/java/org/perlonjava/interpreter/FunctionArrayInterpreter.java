package org.perlonjava.interpreter;

/**
 * Function-array based bytecode interpreter prototype.
 *
 * Uses an array of handler objects with virtual dispatch.
 * Expected advantages:
 * - Modular: each opcode is separate class
 * - No method size limits
 * - Extensible: can add opcodes at runtime
 *
 * Expected disadvantages:
 * - Megamorphic call site (256 different target types)
 * - Virtual dispatch prevents inlining
 * - Object allocation for state wrapper
 * - Cache misses from random memory access
 */
public class FunctionArrayInterpreter {

    /**
     * Interpreter state passed to each handler.
     */
    public static class InterpreterState {
        public byte[] bytecode;
        public int pc;
        public Object[] stack;
        public int sp;
        public Object[] locals;
        public int[] intPool;
        public long result;

        public InterpreterState(InterpretedCode code) {
            this.bytecode = code.bytecode;
            this.pc = 0;
            this.stack = new Object[code.maxStack];
            this.sp = 0;
            this.locals = new Object[code.maxLocals];
            this.intPool = code.intPool;
            this.result = 0;

            // Initialize locals
            for (int i = 0; i < locals.length; i++) {
                locals[i] = 0L;
            }
        }
    }

    /**
     * Handler interface for opcodes.
     */
    public interface OpcodeHandler {
        void execute(InterpreterState state);
    }

    // Handler implementations
    private static class NopHandler implements OpcodeHandler {
        @Override
        public void execute(InterpreterState state) {
            // No operation
        }
    }

    private static class LoadLocalHandler implements OpcodeHandler {
        @Override
        public void execute(InterpreterState state) {
            int index = state.bytecode[state.pc++] & 0xFF;
            state.stack[state.sp++] = state.locals[index];
        }
    }

    private static class StoreLocalHandler implements OpcodeHandler {
        @Override
        public void execute(InterpreterState state) {
            int index = state.bytecode[state.pc++] & 0xFF;
            state.locals[index] = state.stack[--state.sp];
        }
    }

    private static class LoadIntHandler implements OpcodeHandler {
        @Override
        public void execute(InterpreterState state) {
            int poolIndex = state.bytecode[state.pc++] & 0xFF;
            state.stack[state.sp++] = (long) state.intPool[poolIndex];
        }
    }

    private static class AddIntHandler implements OpcodeHandler {
        @Override
        public void execute(InterpreterState state) {
            long b = (long) state.stack[--state.sp];
            long a = (long) state.stack[--state.sp];
            state.stack[state.sp++] = a + b;
        }
    }

    private static class SubIntHandler implements OpcodeHandler {
        @Override
        public void execute(InterpreterState state) {
            long b = (long) state.stack[--state.sp];
            long a = (long) state.stack[--state.sp];
            state.stack[state.sp++] = a - b;
        }
    }

    private static class MulIntHandler implements OpcodeHandler {
        @Override
        public void execute(InterpreterState state) {
            long b = (long) state.stack[--state.sp];
            long a = (long) state.stack[--state.sp];
            state.stack[state.sp++] = a * b;
        }
    }

    private static class DupHandler implements OpcodeHandler {
        @Override
        public void execute(InterpreterState state) {
            state.stack[state.sp] = state.stack[state.sp - 1];
            state.sp++;
        }
    }

    private static class PopHandler implements OpcodeHandler {
        @Override
        public void execute(InterpreterState state) {
            state.sp--;
        }
    }

    private static class ReturnHandler implements OpcodeHandler {
        @Override
        public void execute(InterpreterState state) {
            if (state.sp > 0) {
                state.result = (long) state.stack[--state.sp];
            }
            state.pc = state.bytecode.length; // Exit loop
        }
    }

    // Handler dispatch table
    private static final OpcodeHandler[] HANDLERS = new OpcodeHandler[256];

    static {
        // Initialize handlers
        HANDLERS[Opcodes.NOP] = new NopHandler();
        HANDLERS[Opcodes.LOAD_LOCAL] = new LoadLocalHandler();
        HANDLERS[Opcodes.STORE_LOCAL] = new StoreLocalHandler();
        HANDLERS[Opcodes.LOAD_INT] = new LoadIntHandler();
        HANDLERS[Opcodes.ADD_INT] = new AddIntHandler();
        HANDLERS[Opcodes.SUB_INT] = new SubIntHandler();
        HANDLERS[Opcodes.MUL_INT] = new MulIntHandler();
        HANDLERS[Opcodes.DUP] = new DupHandler();
        HANDLERS[Opcodes.POP] = new PopHandler();
        HANDLERS[Opcodes.RETURN] = new ReturnHandler();
    }

    /**
     * Execute bytecode using function-array dispatch.
     *
     * @param code The bytecode to execute
     * @return Execution result (for benchmarking)
     */
    public static long execute(InterpretedCode code) {
        InterpreterState state = new InterpreterState(code);

        // Main dispatch loop
        while (state.pc < state.bytecode.length) {
            byte opcode = state.bytecode[state.pc++];
            OpcodeHandler handler = HANDLERS[opcode & 0xFF];
            if (handler != null) {
                handler.execute(state);
            } else {
                throw new IllegalArgumentException("Unknown opcode: " + opcode);
            }
        }

        return state.result;
    }
}
