package org.perlonjava.interpreter;

/**
 * Switch-based bytecode interpreter prototype.
 *
 * Uses a single large switch statement for opcode dispatch.
 * Expected advantages:
 * - JVM JIT optimizes to tableswitch (O(1) jump table)
 * - Can inline case bodies into switch
 * - CPU branch prediction learns patterns
 * - No object allocation or indirection
 *
 * Expected disadvantage:
 * - Large switch may hit JVM method size limits
 */
public class SwitchInterpreter {

    /**
     * Execute bytecode using switch-based dispatch.
     *
     * @param code The bytecode to execute
     * @return Execution result (for benchmarking)
     */
    public static long execute(InterpretedCode code) {
        Object[] locals = new Object[code.maxLocals];
        Object[] stack = new Object[code.maxStack];
        int sp = 0;
        int pc = 0;
        byte[] bytecode = code.bytecode;
        int[] intPool = code.intPool;

        // Initialize locals with test values
        for (int i = 0; i < locals.length; i++) {
            locals[i] = 0L;
        }

        long result = 0;

        // Main dispatch loop
        while (pc < bytecode.length) {
            byte opcode = bytecode[pc++];

            switch (opcode) {
                case Opcodes.NOP:
                    // No operation - just continue
                    break;

                case Opcodes.LOAD_LOCAL:
                    int index = bytecode[pc++] & 0xFF;
                    stack[sp++] = locals[index];
                    break;

                case Opcodes.STORE_LOCAL:
                    int storeIndex = bytecode[pc++] & 0xFF;
                    locals[storeIndex] = stack[--sp];
                    break;

                case Opcodes.LOAD_INT:
                    int poolIndex = bytecode[pc++] & 0xFF;
                    stack[sp++] = (long) intPool[poolIndex];
                    break;

                case Opcodes.ADD_INT:
                    long b = (long) stack[--sp];
                    long a = (long) stack[--sp];
                    stack[sp++] = a + b;
                    break;

                case Opcodes.SUB_INT:
                    long subB = (long) stack[--sp];
                    long subA = (long) stack[--sp];
                    stack[sp++] = subA - subB;
                    break;

                case Opcodes.MUL_INT:
                    long mulB = (long) stack[--sp];
                    long mulA = (long) stack[--sp];
                    stack[sp++] = mulA * mulB;
                    break;

                case Opcodes.DUP:
                    stack[sp] = stack[sp - 1];
                    sp++;
                    break;

                case Opcodes.POP:
                    sp--;
                    break;

                case Opcodes.RETURN:
                    if (sp > 0) {
                        result = (long) stack[--sp];
                    }
                    return result;

                default:
                    throw new IllegalArgumentException("Unknown opcode: " + opcode);
            }
        }

        return result;
    }
}
