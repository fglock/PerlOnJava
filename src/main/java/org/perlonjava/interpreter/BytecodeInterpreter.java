package org.perlonjava.interpreter;

import org.perlonjava.runtime.*;
import org.perlonjava.operators.*;

/**
 * Bytecode interpreter with switch-based dispatch and pure register architecture.
 *
 * Key design principles:
 * 1. Pure register machine (NO expression stack) - required for control flow correctness
 * 2. 3-address code format: rd = rs1 op rs2 (explicit register operands)
 * 3. Call same org.perlonjava.operators.* methods as compiler (100% code reuse)
 * 4. Share GlobalVariable maps with compiled code (same global state)
 * 5. Handle RuntimeControlFlowList for last/next/redo/goto/tail-call
 * 6. Switch-based dispatch (JVM optimizes to tableswitch - O(1) jump table)
 */
public class BytecodeInterpreter {

    /**
     * Execute interpreted bytecode.
     *
     * @param code        The InterpretedCode to execute
     * @param args        The arguments array (@_)
     * @param callContext The calling context (VOID/SCALAR/LIST/RUNTIME)
     * @return RuntimeList containing the result (may be RuntimeControlFlowList)
     */
    public static RuntimeList execute(InterpretedCode code, RuntimeArray args, int callContext) {
        return execute(code, args, callContext, null);
    }

    /**
     * Execute interpreted bytecode with subroutine name for stack traces.
     *
     * @param code           The InterpretedCode to execute
     * @param args           The arguments array (@_)
     * @param callContext    The calling context
     * @param subroutineName Subroutine name for stack traces (may be null)
     * @return RuntimeList containing the result (may be RuntimeControlFlowList)
     */
    public static RuntimeList execute(InterpretedCode code, RuntimeArray args, int callContext, String subroutineName) {
        // Pure register file (NOT stack-based - matches compiler for control flow correctness)
        RuntimeBase[] registers = new RuntimeBase[code.maxRegisters];

        // Initialize special registers (same as compiler)
        registers[0] = code;           // $this (for closures - register 0)
        registers[1] = args;           // @_ (arguments - register 1)
        registers[2] = RuntimeScalarCache.getScalarInt(callContext); // wantarray (register 2)

        // Copy captured variables (closure support)
        if (code.capturedVars != null && code.capturedVars.length > 0) {
            System.arraycopy(code.capturedVars, 0, registers, 3, code.capturedVars.length);
        }

        int pc = 0;  // Program counter
        byte[] bytecode = code.bytecode;

        try {
            // Main dispatch loop - JVM JIT optimizes switch to tableswitch (O(1) jump)
            while (pc < bytecode.length) {
                byte opcode = bytecode[pc++];

                switch (opcode) {
                    // =================================================================
                    // CONTROL FLOW
                    // =================================================================

                    case Opcodes.NOP:
                        // No operation
                        break;

                    case Opcodes.RETURN: {
                        // Return from subroutine: return rd
                        int retReg = bytecode[pc++] & 0xFF;
                        RuntimeBase retVal = registers[retReg];

                        if (retVal == null) {
                            return new RuntimeList();
                        } else if (retVal instanceof RuntimeList) {
                            return (RuntimeList) retVal;
                        } else if (retVal instanceof RuntimeScalar) {
                            return new RuntimeList((RuntimeScalar) retVal);
                        } else if (retVal instanceof RuntimeArray) {
                            return ((RuntimeArray) retVal).getList();
                        } else {
                            // Shouldn't happen, but handle gracefully
                            return new RuntimeList(new RuntimeScalar(retVal.toString()));
                        }
                    }

                    case Opcodes.GOTO: {
                        // Unconditional jump: pc = offset
                        int offset = readInt(bytecode, pc);
                        pc = offset;  // Registers persist across jump (unlike stack-based!)
                        break;
                    }

                    case Opcodes.GOTO_IF_FALSE: {
                        // Conditional jump: if (!rs) pc = offset
                        int condReg = bytecode[pc++] & 0xFF;
                        int target = readInt(bytecode, pc);
                        pc += 4;

                        RuntimeScalar cond = (RuntimeScalar) registers[condReg];
                        if (!cond.getBoolean()) {
                            pc = target;  // Jump - all registers stay valid!
                        }
                        break;
                    }

                    case Opcodes.GOTO_IF_TRUE: {
                        // Conditional jump: if (rs) pc = offset
                        int condReg = bytecode[pc++] & 0xFF;
                        int target = readInt(bytecode, pc);
                        pc += 4;

                        RuntimeScalar cond = (RuntimeScalar) registers[condReg];
                        if (cond.getBoolean()) {
                            pc = target;
                        }
                        break;
                    }

                    // =================================================================
                    // REGISTER OPERATIONS
                    // =================================================================

                    case Opcodes.MOVE: {
                        // Register copy: rd = rs
                        int dest = bytecode[pc++] & 0xFF;
                        int src = bytecode[pc++] & 0xFF;
                        registers[dest] = registers[src];
                        break;
                    }

                    case Opcodes.LOAD_CONST: {
                        // Load from constant pool: rd = constants[index]
                        int rd = bytecode[pc++] & 0xFF;
                        int constIndex = bytecode[pc++] & 0xFF;
                        registers[rd] = (RuntimeBase) code.constants[constIndex];
                        break;
                    }

                    case Opcodes.LOAD_INT: {
                        // Load cached integer: rd = RuntimeScalarCache.getScalarInt(immediate)
                        int rd = bytecode[pc++] & 0xFF;
                        int value = readInt(bytecode, pc);
                        pc += 4;
                        // Uses SAME cache as compiled code
                        registers[rd] = RuntimeScalarCache.getScalarInt(value);
                        break;
                    }

                    case Opcodes.LOAD_STRING: {
                        // Load string: rd = new RuntimeScalar(stringPool[index])
                        int rd = bytecode[pc++] & 0xFF;
                        int strIndex = bytecode[pc++] & 0xFF;
                        registers[rd] = new RuntimeScalar(code.stringPool[strIndex]);
                        break;
                    }

                    case Opcodes.LOAD_UNDEF: {
                        // Load undef: rd = new RuntimeScalar()
                        int rd = bytecode[pc++] & 0xFF;
                        registers[rd] = new RuntimeScalar();
                        break;
                    }

                    // =================================================================
                    // VARIABLE ACCESS - GLOBAL
                    // =================================================================

                    case Opcodes.LOAD_GLOBAL_SCALAR: {
                        // Load global scalar: rd = GlobalVariable.getGlobalVariable(name)
                        int rd = bytecode[pc++] & 0xFF;
                        int nameIdx = bytecode[pc++] & 0xFF;
                        String name = code.stringPool[nameIdx];
                        // Uses SAME GlobalVariable as compiled code
                        registers[rd] = GlobalVariable.getGlobalVariable(name);
                        break;
                    }

                    case Opcodes.STORE_GLOBAL_SCALAR: {
                        // Store global scalar: GlobalVariable.getGlobalVariable(name).set(rs)
                        int nameIdx = bytecode[pc++] & 0xFF;
                        int srcReg = bytecode[pc++] & 0xFF;
                        String name = code.stringPool[nameIdx];
                        GlobalVariable.getGlobalVariable(name).set((RuntimeScalar) registers[srcReg]);
                        break;
                    }

                    case Opcodes.LOAD_GLOBAL_ARRAY: {
                        // Load global array: rd = GlobalVariable.getGlobalArray(name)
                        int rd = bytecode[pc++] & 0xFF;
                        int nameIdx = bytecode[pc++] & 0xFF;
                        String name = code.stringPool[nameIdx];
                        registers[rd] = GlobalVariable.getGlobalArray(name);
                        break;
                    }

                    case Opcodes.LOAD_GLOBAL_HASH: {
                        // Load global hash: rd = GlobalVariable.getGlobalHash(name)
                        int rd = bytecode[pc++] & 0xFF;
                        int nameIdx = bytecode[pc++] & 0xFF;
                        String name = code.stringPool[nameIdx];
                        registers[rd] = GlobalVariable.getGlobalHash(name);
                        break;
                    }

                    case Opcodes.LOAD_GLOBAL_CODE: {
                        // Load global code: rd = GlobalVariable.getGlobalCodeRef(name)
                        int rd = bytecode[pc++] & 0xFF;
                        int nameIdx = bytecode[pc++] & 0xFF;
                        String name = code.stringPool[nameIdx];
                        registers[rd] = GlobalVariable.getGlobalCodeRef(name);
                        break;
                    }

                    // =================================================================
                    // ARITHMETIC OPERATORS
                    // =================================================================

                    case Opcodes.ADD_SCALAR: {
                        // Addition: rd = rs1 + rs2
                        int rd = bytecode[pc++] & 0xFF;
                        int rs1 = bytecode[pc++] & 0xFF;
                        int rs2 = bytecode[pc++] & 0xFF;
                        // Calls SAME method as compiled code
                        registers[rd] = MathOperators.add(
                            (RuntimeScalar) registers[rs1],
                            (RuntimeScalar) registers[rs2]
                        );
                        break;
                    }

                    case Opcodes.SUB_SCALAR: {
                        // Subtraction: rd = rs1 - rs2
                        int rd = bytecode[pc++] & 0xFF;
                        int rs1 = bytecode[pc++] & 0xFF;
                        int rs2 = bytecode[pc++] & 0xFF;
                        registers[rd] = MathOperators.subtract(
                            (RuntimeScalar) registers[rs1],
                            (RuntimeScalar) registers[rs2]
                        );
                        break;
                    }

                    case Opcodes.MUL_SCALAR: {
                        // Multiplication: rd = rs1 * rs2
                        int rd = bytecode[pc++] & 0xFF;
                        int rs1 = bytecode[pc++] & 0xFF;
                        int rs2 = bytecode[pc++] & 0xFF;
                        registers[rd] = MathOperators.multiply(
                            (RuntimeScalar) registers[rs1],
                            (RuntimeScalar) registers[rs2]
                        );
                        break;
                    }

                    case Opcodes.DIV_SCALAR: {
                        // Division: rd = rs1 / rs2
                        int rd = bytecode[pc++] & 0xFF;
                        int rs1 = bytecode[pc++] & 0xFF;
                        int rs2 = bytecode[pc++] & 0xFF;
                        registers[rd] = MathOperators.divide(
                            (RuntimeScalar) registers[rs1],
                            (RuntimeScalar) registers[rs2]
                        );
                        break;
                    }

                    case Opcodes.NEG_SCALAR: {
                        // Negation: rd = -rs
                        int rd = bytecode[pc++] & 0xFF;
                        int rs = bytecode[pc++] & 0xFF;
                        registers[rd] = MathOperators.unaryMinus((RuntimeScalar) registers[rs]);
                        break;
                    }

                    // Specialized unboxed operations (rare optimizations)
                    case Opcodes.ADD_SCALAR_INT: {
                        // Addition with immediate: rd = rs + immediate
                        int rd = bytecode[pc++] & 0xFF;
                        int rs = bytecode[pc++] & 0xFF;
                        int immediate = readInt(bytecode, pc);
                        pc += 4;
                        // Calls specialized unboxed method (rare optimization)
                        registers[rd] = MathOperators.add(
                            (RuntimeScalar) registers[rs],
                            immediate  // primitive int, not RuntimeScalar
                        );
                        break;
                    }

                    // =================================================================
                    // STRING OPERATORS
                    // =================================================================

                    case Opcodes.CONCAT: {
                        // String concatenation: rd = rs1 . rs2
                        int rd = bytecode[pc++] & 0xFF;
                        int rs1 = bytecode[pc++] & 0xFF;
                        int rs2 = bytecode[pc++] & 0xFF;
                        registers[rd] = StringOperators.stringConcat(
                            (RuntimeScalar) registers[rs1],
                            (RuntimeScalar) registers[rs2]
                        );
                        break;
                    }

                    case Opcodes.LENGTH: {
                        // String length: rd = length(rs)
                        int rd = bytecode[pc++] & 0xFF;
                        int rs = bytecode[pc++] & 0xFF;
                        registers[rd] = StringOperators.length((RuntimeScalar) registers[rs]);
                        break;
                    }

                    // =================================================================
                    // COMPARISON OPERATORS
                    // =================================================================

                    case Opcodes.COMPARE_NUM: {
                        // Numeric comparison: rd = rs1 <=> rs2
                        int rd = bytecode[pc++] & 0xFF;
                        int rs1 = bytecode[pc++] & 0xFF;
                        int rs2 = bytecode[pc++] & 0xFF;
                        registers[rd] = CompareOperators.spaceship(
                            (RuntimeScalar) registers[rs1],
                            (RuntimeScalar) registers[rs2]
                        );
                        break;
                    }

                    case Opcodes.EQ_NUM: {
                        // Numeric equality: rd = (rs1 == rs2)
                        int rd = bytecode[pc++] & 0xFF;
                        int rs1 = bytecode[pc++] & 0xFF;
                        int rs2 = bytecode[pc++] & 0xFF;
                        registers[rd] = CompareOperators.equalTo(
                            (RuntimeScalar) registers[rs1],
                            (RuntimeScalar) registers[rs2]
                        );
                        break;
                    }

                    case Opcodes.LT_NUM: {
                        // Less than: rd = (rs1 < rs2)
                        int rd = bytecode[pc++] & 0xFF;
                        int rs1 = bytecode[pc++] & 0xFF;
                        int rs2 = bytecode[pc++] & 0xFF;
                        registers[rd] = CompareOperators.lessThan(
                            (RuntimeScalar) registers[rs1],
                            (RuntimeScalar) registers[rs2]
                        );
                        break;
                    }

                    // =================================================================
                    // LOGICAL OPERATORS
                    // =================================================================

                    case Opcodes.NOT: {
                        // Logical NOT: rd = !rs
                        int rd = bytecode[pc++] & 0xFF;
                        int rs = bytecode[pc++] & 0xFF;
                        RuntimeScalar val = (RuntimeScalar) registers[rs];
                        registers[rd] = val.getBoolean() ?
                            RuntimeScalarCache.scalarFalse : RuntimeScalarCache.scalarTrue;
                        break;
                    }

                    // =================================================================
                    // ARRAY OPERATIONS
                    // =================================================================

                    case Opcodes.ARRAY_GET: {
                        // Array element access: rd = array[index]
                        int rd = bytecode[pc++] & 0xFF;
                        int arrayReg = bytecode[pc++] & 0xFF;
                        int indexReg = bytecode[pc++] & 0xFF;
                        RuntimeArray arr = (RuntimeArray) registers[arrayReg];
                        RuntimeScalar idx = (RuntimeScalar) registers[indexReg];
                        // Uses RuntimeArray API directly
                        registers[rd] = arr.get(idx.getInt());
                        break;
                    }

                    case Opcodes.ARRAY_SET: {
                        // Array element store: array[index] = value
                        int arrayReg = bytecode[pc++] & 0xFF;
                        int indexReg = bytecode[pc++] & 0xFF;
                        int valueReg = bytecode[pc++] & 0xFF;
                        RuntimeArray arr = (RuntimeArray) registers[arrayReg];
                        RuntimeScalar idx = (RuntimeScalar) registers[indexReg];
                        RuntimeScalar val = (RuntimeScalar) registers[valueReg];
                        arr.get(idx.getInt()).set(val);  // Get element then set its value
                        break;
                    }

                    case Opcodes.ARRAY_PUSH: {
                        // Array push: push(@array, value)
                        int arrayReg = bytecode[pc++] & 0xFF;
                        int valueReg = bytecode[pc++] & 0xFF;
                        RuntimeArray arr = (RuntimeArray) registers[arrayReg];
                        RuntimeScalar val = (RuntimeScalar) registers[valueReg];
                        arr.push(val);
                        break;
                    }

                    case Opcodes.ARRAY_SIZE: {
                        // Array size: rd = scalar(@array)
                        int rd = bytecode[pc++] & 0xFF;
                        int arrayReg = bytecode[pc++] & 0xFF;
                        RuntimeArray arr = (RuntimeArray) registers[arrayReg];
                        registers[rd] = new RuntimeScalar(arr.size());
                        break;
                    }

                    case Opcodes.CREATE_ARRAY: {
                        // Create array: rd = []
                        int rd = bytecode[pc++] & 0xFF;
                        registers[rd] = new RuntimeArray();
                        break;
                    }

                    // =================================================================
                    // HASH OPERATIONS
                    // =================================================================

                    case Opcodes.HASH_GET: {
                        // Hash element access: rd = hash{key}
                        int rd = bytecode[pc++] & 0xFF;
                        int hashReg = bytecode[pc++] & 0xFF;
                        int keyReg = bytecode[pc++] & 0xFF;
                        RuntimeHash hash = (RuntimeHash) registers[hashReg];
                        RuntimeScalar key = (RuntimeScalar) registers[keyReg];
                        // Uses RuntimeHash API directly
                        registers[rd] = hash.get(key);
                        break;
                    }

                    case Opcodes.HASH_SET: {
                        // Hash element store: hash{key} = value
                        int hashReg = bytecode[pc++] & 0xFF;
                        int keyReg = bytecode[pc++] & 0xFF;
                        int valueReg = bytecode[pc++] & 0xFF;
                        RuntimeHash hash = (RuntimeHash) registers[hashReg];
                        RuntimeScalar key = (RuntimeScalar) registers[keyReg];
                        RuntimeScalar val = (RuntimeScalar) registers[valueReg];
                        hash.put(key.toString(), val);  // Convert key to String
                        break;
                    }

                    case Opcodes.CREATE_HASH: {
                        // Create hash: rd = {}
                        int rd = bytecode[pc++] & 0xFF;
                        registers[rd] = new RuntimeHash();
                        break;
                    }

                    // =================================================================
                    // SUBROUTINE CALLS
                    // =================================================================

                    case Opcodes.CALL_SUB: {
                        // Call subroutine: rd = coderef->(args)
                        // May return RuntimeControlFlowList!
                        int rd = bytecode[pc++] & 0xFF;
                        int coderefReg = bytecode[pc++] & 0xFF;
                        int argsReg = bytecode[pc++] & 0xFF;
                        int context = bytecode[pc++] & 0xFF;

                        RuntimeScalar codeRef = (RuntimeScalar) registers[coderefReg];
                        RuntimeArray callArgs = (RuntimeArray) registers[argsReg];

                        // RuntimeCode.apply works for both compiled AND interpreted code
                        RuntimeList result = RuntimeCode.apply(codeRef, "", callArgs, context);

                        registers[rd] = result;

                        // Check for control flow (last/next/redo/goto/tail-call)
                        if (result.isNonLocalGoto()) {
                            // Propagate control flow up the call stack
                            return result;
                        }
                        break;
                    }

                    // =================================================================
                    // CONTROL FLOW - SPECIAL (RuntimeControlFlowList)
                    // =================================================================

                    case Opcodes.CREATE_LAST: {
                        // Create LAST control flow: rd = RuntimeControlFlowList(LAST, label)
                        int rd = bytecode[pc++] & 0xFF;
                        int labelIdx = bytecode[pc++] & 0xFF;
                        String label = labelIdx == 255 ? null : code.stringPool[labelIdx];
                        registers[rd] = new RuntimeControlFlowList(
                            ControlFlowType.LAST, label,
                            code.sourceName, code.sourceLine
                        );
                        break;
                    }

                    case Opcodes.CREATE_NEXT: {
                        // Create NEXT control flow: rd = RuntimeControlFlowList(NEXT, label)
                        int rd = bytecode[pc++] & 0xFF;
                        int labelIdx = bytecode[pc++] & 0xFF;
                        String label = labelIdx == 255 ? null : code.stringPool[labelIdx];
                        registers[rd] = new RuntimeControlFlowList(
                            ControlFlowType.NEXT, label,
                            code.sourceName, code.sourceLine
                        );
                        break;
                    }

                    case Opcodes.CREATE_REDO: {
                        // Create REDO control flow: rd = RuntimeControlFlowList(REDO, label)
                        int rd = bytecode[pc++] & 0xFF;
                        int labelIdx = bytecode[pc++] & 0xFF;
                        String label = labelIdx == 255 ? null : code.stringPool[labelIdx];
                        registers[rd] = new RuntimeControlFlowList(
                            ControlFlowType.REDO, label,
                            code.sourceName, code.sourceLine
                        );
                        break;
                    }

                    case Opcodes.IS_CONTROL_FLOW: {
                        // Check if value is control flow: rd = (rs instanceof RuntimeControlFlowList)
                        int rd = bytecode[pc++] & 0xFF;
                        int rs = bytecode[pc++] & 0xFF;
                        boolean isControlFlow = registers[rs] instanceof RuntimeControlFlowList;
                        registers[rd] = isControlFlow ?
                            RuntimeScalarCache.scalarTrue : RuntimeScalarCache.scalarFalse;
                        break;
                    }

                    // =================================================================
                    // MISCELLANEOUS
                    // =================================================================

                    case Opcodes.PRINT: {
                        // Print to STDOUT
                        int rs = bytecode[pc++] & 0xFF;
                        RuntimeScalar val = (RuntimeScalar) registers[rs];
                        System.out.print(val.toString());
                        break;
                    }

                    case Opcodes.SAY: {
                        // Say to STDOUT (print with newline)
                        int rs = bytecode[pc++] & 0xFF;
                        RuntimeScalar val = (RuntimeScalar) registers[rs];
                        System.out.println(val.toString());
                        break;
                    }

                    default:
                        throw new RuntimeException(
                            "Unknown opcode: " + (opcode & 0xFF) +
                            " at pc=" + (pc - 1) +
                            " in " + code.sourceName + ":" + code.sourceLine
                        );
                }
            }

            // Fell through end of bytecode - return empty list
            return new RuntimeList();

        } catch (Exception e) {
            // Add context to exceptions
            throw new RuntimeException(
                "Interpreter error in " + code.sourceName + ":" + code.sourceLine +
                " at pc=" + pc + ": " + e.getMessage(),
                e
            );
        }
    }

    /**
     * Read a 32-bit big-endian integer from bytecode.
     */
    private static int readInt(byte[] bytecode, int pc) {
        return ((bytecode[pc] & 0xFF) << 24) |
               ((bytecode[pc + 1] & 0xFF) << 16) |
               ((bytecode[pc + 2] & 0xFF) << 8) |
               (bytecode[pc + 3] & 0xFF);
    }
}
