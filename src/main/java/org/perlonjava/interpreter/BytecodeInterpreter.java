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

        // Eval block exception handling: stack of catch PCs
        // When EVAL_TRY is executed, push the catch PC onto this stack
        // When exception occurs, pop from stack and jump to catch PC
        java.util.Stack<Integer> evalCatchStack = new java.util.Stack<>();

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
                        // Load integer: rd = immediate (create NEW mutable scalar, not cached)
                        int rd = bytecode[pc++] & 0xFF;
                        int value = readInt(bytecode, pc);
                        pc += 4;
                        // Create NEW RuntimeScalar (mutable) instead of using cache
                        // This is needed for local variables that may be modified (++/--)
                        registers[rd] = new RuntimeScalar(value);
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
                        // Create array from list: rd = array(rs_list)
                        int rd = bytecode[pc++] & 0xFF;
                        int listReg = bytecode[pc++] & 0xFF;

                        // Convert to list (polymorphic - works for PerlRange, RuntimeList, etc.)
                        RuntimeBase source = registers[listReg];
                        if (source instanceof RuntimeArray) {
                            // Already an array - pass through
                            registers[rd] = source;
                        } else {
                            // Convert to list, then to array (works for PerlRange, RuntimeList, etc.)
                            RuntimeList list = source.getList();
                            registers[rd] = new RuntimeArray(list);
                        }
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
                        RuntimeBase argsBase = registers[argsReg];

                        // Convert args to RuntimeArray if needed
                        RuntimeArray callArgs;
                        if (argsBase instanceof RuntimeArray) {
                            callArgs = (RuntimeArray) argsBase;
                        } else if (argsBase instanceof RuntimeList) {
                            // Convert RuntimeList to RuntimeArray (from ListNode)
                            callArgs = new RuntimeArray((RuntimeList) argsBase);
                        } else {
                            // Single scalar argument
                            callArgs = new RuntimeArray((RuntimeScalar) argsBase);
                        }

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
                        // Print to filehandle
                        // Format: [PRINT] [rs_content] [rs_filehandle]
                        int contentReg = bytecode[pc++] & 0xFF;
                        int filehandleReg = bytecode[pc++] & 0xFF;

                        Object val = registers[contentReg];
                        RuntimeScalar fh = (RuntimeScalar) registers[filehandleReg];

                        RuntimeList list;
                        if (val instanceof RuntimeList) {
                            list = (RuntimeList) val;
                        } else if (val instanceof RuntimeScalar) {
                            // Convert scalar to single-element list
                            list = new RuntimeList();
                            list.add((RuntimeScalar) val);
                        } else {
                            list = new RuntimeList();
                        }

                        // Call IOOperator.print()
                        org.perlonjava.operators.IOOperator.print(list, fh);
                        break;
                    }

                    case Opcodes.SAY: {
                        // Say to filehandle
                        // Format: [SAY] [rs_content] [rs_filehandle]
                        int contentReg = bytecode[pc++] & 0xFF;
                        int filehandleReg = bytecode[pc++] & 0xFF;

                        Object val = registers[contentReg];
                        RuntimeScalar fh = (RuntimeScalar) registers[filehandleReg];

                        RuntimeList list;
                        if (val instanceof RuntimeList) {
                            list = (RuntimeList) val;
                        } else if (val instanceof RuntimeScalar) {
                            // Convert scalar to single-element list
                            list = new RuntimeList();
                            list.add((RuntimeScalar) val);
                        } else {
                            list = new RuntimeList();
                        }

                        // Call IOOperator.say()
                        org.perlonjava.operators.IOOperator.say(list, fh);
                        break;
                    }

                    // =================================================================
                    // SUPERINSTRUCTIONS - Eliminate MOVE overhead
                    // =================================================================

                    case Opcodes.INC_REG: {
                        // Increment register in-place: r++
                        int rd = bytecode[pc++] & 0xFF;
                        registers[rd] = MathOperators.add((RuntimeScalar) registers[rd], 1);
                        break;
                    }

                    case Opcodes.DEC_REG: {
                        // Decrement register in-place: r--
                        int rd = bytecode[pc++] & 0xFF;
                        registers[rd] = MathOperators.subtract((RuntimeScalar) registers[rd], 1);
                        break;
                    }

                    case Opcodes.ADD_ASSIGN: {
                        // Add and assign: rd = rd + rs
                        int rd = bytecode[pc++] & 0xFF;
                        int rs = bytecode[pc++] & 0xFF;
                        registers[rd] = MathOperators.add(
                            (RuntimeScalar) registers[rd],
                            (RuntimeScalar) registers[rs]
                        );
                        break;
                    }

                    case Opcodes.ADD_ASSIGN_INT: {
                        // Add immediate and assign: rd = rd + imm
                        int rd = bytecode[pc++] & 0xFF;
                        int immediate = readInt(bytecode, pc);
                        pc += 4;
                        registers[rd] = MathOperators.add((RuntimeScalar) registers[rd], immediate);
                        break;
                    }

                    case Opcodes.PRE_AUTOINCREMENT: {
                        // Pre-increment: ++rd
                        int rd = bytecode[pc++] & 0xFF;
                        ((RuntimeScalar) registers[rd]).preAutoIncrement();
                        break;
                    }

                    case Opcodes.POST_AUTOINCREMENT: {
                        // Post-increment: rd++
                        int rd = bytecode[pc++] & 0xFF;
                        ((RuntimeScalar) registers[rd]).postAutoIncrement();
                        break;
                    }

                    case Opcodes.PRE_AUTODECREMENT: {
                        // Pre-decrement: --rd
                        int rd = bytecode[pc++] & 0xFF;
                        ((RuntimeScalar) registers[rd]).preAutoDecrement();
                        break;
                    }

                    case Opcodes.POST_AUTODECREMENT: {
                        // Post-decrement: rd--
                        int rd = bytecode[pc++] & 0xFF;
                        ((RuntimeScalar) registers[rd]).postAutoDecrement();
                        break;
                    }

                    // =================================================================
                    // ERROR HANDLING
                    // =================================================================

                    case Opcodes.DIE: {
                        // Die with message: die(rs)
                        int dieRs = bytecode[pc++] & 0xFF;
                        RuntimeBase message = registers[dieRs];

                        // Get token index for this die location if available
                        Integer tokenIndex = code.pcToTokenIndex != null
                                ? code.pcToTokenIndex.get(pc - 2) // PC before we read register
                                : null;

                        // Call WarnDie.die() with proper parameters
                        // die(RuntimeBase message, RuntimeScalar where, String fileName, int lineNumber)
                        RuntimeScalar where = new RuntimeScalar(" at " + code.sourceName + " line " + code.sourceLine);
                        WarnDie.die(message, where, code.sourceName, code.sourceLine);

                        // Should never reach here (die throws exception)
                        throw new RuntimeException("die() did not throw exception");
                    }

                    case Opcodes.WARN: {
                        // Warn with message: warn(rs)
                        int warnRs = bytecode[pc++] & 0xFF;
                        RuntimeBase message = registers[warnRs];

                        // Get token index for this warn location if available
                        Integer tokenIndex = code.pcToTokenIndex != null
                                ? code.pcToTokenIndex.get(pc - 2) // PC before we read register
                                : null;

                        // Call WarnDie.warn() with proper parameters
                        RuntimeScalar where = new RuntimeScalar(" at " + code.sourceName + " line " + code.sourceLine);
                        WarnDie.warn(message, where, code.sourceName, code.sourceLine);
                        break;
                    }

                    // =================================================================
                    // REFERENCE OPERATIONS
                    // =================================================================

                    case Opcodes.CREATE_REF: {
                        // Create reference: rd = rs.createReference()
                        int rd = bytecode[pc++] & 0xFF;
                        int rs = bytecode[pc++] & 0xFF;
                        RuntimeBase value = registers[rs];
                        registers[rd] = value.createReference();
                        break;
                    }

                    case Opcodes.DEREF: {
                        // Dereference: rd = rs (dereferencing depends on context)
                        // For now, just copy the reference - proper dereferencing
                        // is context-dependent and handled by specific operators
                        int rd = bytecode[pc++] & 0xFF;
                        int rs = bytecode[pc++] & 0xFF;
                        registers[rd] = registers[rs];
                        break;
                    }

                    case Opcodes.GET_TYPE: {
                        // Get type: rd = new RuntimeScalar(rs.type)
                        int rd = bytecode[pc++] & 0xFF;
                        int rs = bytecode[pc++] & 0xFF;
                        RuntimeScalar value = (RuntimeScalar) registers[rs];
                        // RuntimeScalar.type is an int constant from RuntimeScalarType
                        registers[rd] = new RuntimeScalar(value.type);
                        break;
                    }

                    // =================================================================
                    // EVAL BLOCK SUPPORT
                    // =================================================================

                    case Opcodes.EVAL_TRY: {
                        // Start of eval block with exception handling
                        // Format: [EVAL_TRY] [catch_offset_high] [catch_offset_low]

                        int catchOffsetHigh = bytecode[pc++] & 0xFF;
                        int catchOffsetLow = bytecode[pc++] & 0xFF;
                        int catchOffset = (catchOffsetHigh << 8) | catchOffsetLow;
                        int tryStartPc = pc - 3; // PC where EVAL_TRY opcode is
                        int catchPc = tryStartPc + catchOffset;

                        // Push catch PC onto eval stack
                        evalCatchStack.push(catchPc);

                        // Clear $@ at start of eval block
                        GlobalVariable.setGlobalVariable("main::@", "");

                        // Continue execution - if exception occurs, outer catch handler
                        // will check evalCatchStack and jump to catchPc
                        break;
                    }

                    case Opcodes.EVAL_END: {
                        // End of successful eval block - clear $@ and pop catch stack
                        GlobalVariable.setGlobalVariable("main::@", "");

                        // Pop the catch PC from eval stack (we didn't need it)
                        if (!evalCatchStack.isEmpty()) {
                            evalCatchStack.pop();
                        }
                        break;
                    }

                    case Opcodes.EVAL_CATCH: {
                        // Exception handler for eval block
                        // Format: [EVAL_CATCH] [rd]
                        // This is only reached when an exception is caught

                        int rd = bytecode[pc++] & 0xFF;

                        // WarnDie.catchEval() should have already been called to set $@
                        // Just store undef as the eval result
                        registers[rd] = RuntimeScalarCache.scalarUndef;
                        break;
                    }

                    // =================================================================
                    // LIST OPERATIONS
                    // =================================================================

                    case Opcodes.CREATE_LIST: {
                        // Create RuntimeList from registers
                        // Format: [CREATE_LIST] [rd] [count] [rs1] [rs2] ... [rsN]

                        int rd = bytecode[pc++] & 0xFF;
                        int count = bytecode[pc++] & 0xFF;

                        // Optimize for common cases
                        if (count == 0) {
                            // Empty list - fastest path
                            registers[rd] = new RuntimeList();
                        } else if (count == 1) {
                            // Single element - avoid loop overhead
                            int rs = bytecode[pc++] & 0xFF;
                            RuntimeList list = new RuntimeList();
                            list.add(registers[rs]);
                            registers[rd] = list;
                        } else {
                            // Multiple elements - preallocate and populate
                            RuntimeList list = new RuntimeList();

                            // Read all register indices and add elements
                            for (int i = 0; i < count; i++) {
                                int rs = bytecode[pc++] & 0xFF;
                                list.add(registers[rs]);
                            }

                            registers[rd] = list;
                        }
                        break;
                    }

                    // =================================================================
                    // STRING OPERATIONS
                    // =================================================================

                    case Opcodes.JOIN: {
                        // String join: rd = join(separator, list)
                        int rd = bytecode[pc++] & 0xFF;
                        int separatorReg = bytecode[pc++] & 0xFF;
                        int listReg = bytecode[pc++] & 0xFF;

                        RuntimeScalar separator = (RuntimeScalar) registers[separatorReg];
                        RuntimeBase list = registers[listReg];

                        // Call StringOperators.joinForInterpolation (doesn't warn on undef)
                        registers[rd] = org.perlonjava.operators.StringOperators.joinForInterpolation(separator, list);
                        break;
                    }

                    case Opcodes.SELECT: {
                        // Select default output filehandle: rd = IOOperator.select(list, SCALAR)
                        int rd = bytecode[pc++] & 0xFF;
                        int listReg = bytecode[pc++] & 0xFF;

                        RuntimeList list = (RuntimeList) registers[listReg];
                        RuntimeScalar result = org.perlonjava.operators.IOOperator.select(list, RuntimeContextType.SCALAR);
                        registers[rd] = result;
                        break;
                    }

                    // =================================================================
                    // SLOW OPERATIONS
                    // =================================================================

                    case Opcodes.SLOW_OP: {
                        // Dispatch to slow operation handler
                        // Format: [SLOW_OP] [slow_op_id] [operands...]
                        // The slow_op_id is a dense sequence (0,1,2...) for tableswitch optimization
                        pc = SlowOpcodeHandler.execute(bytecode, pc, registers, code);
                        break;
                    }

                    default:
                        // Unknown opcode
                        int opcodeInt = opcode & 0xFF;
                        throw new RuntimeException(
                            "Unknown opcode: " + opcodeInt +
                            " at pc=" + (pc - 1) +
                            " in " + code.sourceName + ":" + code.sourceLine
                        );
                }
            }

            // Fell through end of bytecode - return empty list
            return new RuntimeList();

        } catch (Throwable e) {
            // Check if we're inside an eval block
            if (!evalCatchStack.isEmpty()) {
                // Inside eval block - catch the exception
                evalCatchStack.pop(); // Pop the catch handler

                // Call WarnDie.catchEval() to set $@
                WarnDie.catchEval(e);

                // Eval block failed - return empty list
                // (The result will be undef in scalar context, empty in list context)
                return new RuntimeList();
            }

            // Not in eval block - propagate exception
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
