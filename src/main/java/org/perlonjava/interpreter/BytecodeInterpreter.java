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
        // Track interpreter state for stack traces
        String framePackageName = code.packageName != null ? code.packageName : "main";
        String frameSubName = subroutineName != null ? subroutineName : (code.subName != null ? code.subName : "(eval)");
        InterpreterState.push(code, framePackageName, frameSubName);

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
        short[] bytecode = code.bytecode;

        // Eval block exception handling: stack of catch PCs
        // When EVAL_TRY is executed, push the catch PC onto this stack
        // When exception occurs, pop from stack and jump to catch PC
        java.util.Stack<Integer> evalCatchStack = new java.util.Stack<>();

        try {
            // Main dispatch loop - JVM JIT optimizes switch to tableswitch (O(1) jump)
            while (pc < bytecode.length) {
                short opcode = bytecode[pc++];

                switch (opcode) {
                    // =================================================================
                    // CONTROL FLOW
                    // =================================================================

                    case Opcodes.NOP:
                        // No operation
                        break;

                    case Opcodes.RETURN: {
                        // Return from subroutine: return rd
                        int retReg = bytecode[pc++];
                        RuntimeBase retVal = registers[retReg];

                        if (retVal == null) {
                            return new RuntimeList();
                        }
                        return retVal.getList();
                    }

                    case Opcodes.GOTO: {
                        // Unconditional jump: pc = offset
                        int offset = readInt(bytecode, pc);
                        pc = offset;  // Registers persist across jump (unlike stack-based!)
                        break;
                    }

                    case Opcodes.GOTO_IF_FALSE: {
                        // Conditional jump: if (!rs) pc = offset
                        int condReg = bytecode[pc++];
                        int target = readInt(bytecode, pc);
                        pc += 2;

                        // Convert to scalar if needed for boolean test
                        RuntimeBase condBase = registers[condReg];
                        RuntimeScalar cond = (condBase instanceof RuntimeScalar)
                            ? (RuntimeScalar) condBase
                            : condBase.scalar();

                        if (!cond.getBoolean()) {
                            pc = target;  // Jump - all registers stay valid!
                        }
                        break;
                    }

                    case Opcodes.GOTO_IF_TRUE: {
                        // Conditional jump: if (rs) pc = offset
                        int condReg = bytecode[pc++];
                        int target = readInt(bytecode, pc);
                        pc += 2;

                        // Convert to scalar if needed for boolean test
                        RuntimeBase condBase = registers[condReg];
                        RuntimeScalar cond = (condBase instanceof RuntimeScalar)
                            ? (RuntimeScalar) condBase
                            : condBase.scalar();

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
                        int dest = bytecode[pc++];
                        int src = bytecode[pc++];
                        registers[dest] = registers[src];
                        break;
                    }

                    case Opcodes.LOAD_CONST: {
                        // Load from constant pool: rd = constants[index]
                        int rd = bytecode[pc++];
                        int constIndex = bytecode[pc++];
                        registers[rd] = (RuntimeBase) code.constants[constIndex];
                        break;
                    }

                    case Opcodes.LOAD_INT: {
                        // Load integer: rd = immediate (create NEW mutable scalar, not cached)
                        int rd = bytecode[pc++];
                        int value = readInt(bytecode, pc);
                        pc += 2;
                        // Create NEW RuntimeScalar (mutable) instead of using cache
                        // This is needed for local variables that may be modified (++/--)
                        registers[rd] = new RuntimeScalar(value);
                        break;
                    }

                    case Opcodes.LOAD_STRING: {
                        // Load string: rd = new RuntimeScalar(stringPool[index])
                        int rd = bytecode[pc++];
                        int strIndex = bytecode[pc++];
                        registers[rd] = new RuntimeScalar(code.stringPool[strIndex]);
                        break;
                    }

                    case Opcodes.LOAD_UNDEF: {
                        // Load undef: rd = new RuntimeScalar()
                        int rd = bytecode[pc++];
                        registers[rd] = new RuntimeScalar();
                        break;
                    }

                    // =================================================================
                    // VARIABLE ACCESS - GLOBAL
                    // =================================================================

                    case Opcodes.LOAD_GLOBAL_SCALAR: {
                        // Load global scalar: rd = GlobalVariable.getGlobalVariable(name)
                        int rd = bytecode[pc++];
                        int nameIdx = bytecode[pc++];
                        String name = code.stringPool[nameIdx];
                        // Uses SAME GlobalVariable as compiled code
                        registers[rd] = GlobalVariable.getGlobalVariable(name);
                        break;
                    }

                    case Opcodes.STORE_GLOBAL_SCALAR: {
                        // Store global scalar: GlobalVariable.getGlobalVariable(name).set(rs)
                        int nameIdx = bytecode[pc++];
                        int srcReg = bytecode[pc++];
                        String name = code.stringPool[nameIdx];

                        // Convert to scalar if needed
                        RuntimeBase value = registers[srcReg];
                        RuntimeScalar scalarValue = (value instanceof RuntimeScalar)
                            ? (RuntimeScalar) value
                            : value.scalar();

                        GlobalVariable.getGlobalVariable(name).set(scalarValue);
                        break;
                    }

                    case Opcodes.STORE_GLOBAL_ARRAY: {
                        // Store global array: GlobalVariable.getGlobalArray(name).setFromList(list)
                        int nameIdx = bytecode[pc++];
                        int srcReg = bytecode[pc++];
                        String name = code.stringPool[nameIdx];

                        RuntimeArray globalArray = GlobalVariable.getGlobalArray(name);
                        RuntimeBase value = registers[srcReg];

                        if (value == null) {
                            // Output disassembly around the error
                            String disasm = code.disassemble();
                            throw new PerlCompilerException("STORE_GLOBAL_ARRAY: Register r" + srcReg +
                                " is null when storing to @" + name + " at pc=" + (pc-3) + "\n\nDisassembly:\n" + disasm);
                        }

                        // Clear and populate the global array from the source
                        if (value instanceof RuntimeArray) {
                            globalArray.elements.clear();
                            globalArray.elements.addAll(((RuntimeArray) value).elements);
                        } else if (value instanceof RuntimeList) {
                            globalArray.setFromList((RuntimeList) value);
                        } else {
                            globalArray.setFromList(value.getList());
                        }
                        break;
                    }

                    case Opcodes.STORE_GLOBAL_HASH: {
                        // Store global hash: GlobalVariable.getGlobalHash(name).setFromList(list)
                        int nameIdx = bytecode[pc++];
                        int srcReg = bytecode[pc++];
                        String name = code.stringPool[nameIdx];

                        RuntimeHash globalHash = GlobalVariable.getGlobalHash(name);
                        RuntimeBase value = registers[srcReg];

                        // Clear and populate the global hash from the source
                        if (value instanceof RuntimeHash) {
                            globalHash.elements.clear();
                            globalHash.elements.putAll(((RuntimeHash) value).elements);
                        } else if (value instanceof RuntimeList) {
                            globalHash.setFromList((RuntimeList) value);
                        } else {
                            globalHash.setFromList(value.getList());
                        }
                        break;
                    }

                    case Opcodes.LOAD_GLOBAL_ARRAY: {
                        // Load global array: rd = GlobalVariable.getGlobalArray(name)
                        int rd = bytecode[pc++];
                        int nameIdx = bytecode[pc++];
                        String name = code.stringPool[nameIdx];
                        registers[rd] = GlobalVariable.getGlobalArray(name);
                        break;
                    }

                    case Opcodes.LOAD_GLOBAL_HASH: {
                        // Load global hash: rd = GlobalVariable.getGlobalHash(name)
                        int rd = bytecode[pc++];
                        int nameIdx = bytecode[pc++];
                        String name = code.stringPool[nameIdx];
                        registers[rd] = GlobalVariable.getGlobalHash(name);
                        break;
                    }

                    case Opcodes.LOAD_GLOBAL_CODE: {
                        // Load global code: rd = GlobalVariable.getGlobalCodeRef(name)
                        int rd = bytecode[pc++];
                        int nameIdx = bytecode[pc++];
                        String name = code.stringPool[nameIdx];
                        registers[rd] = GlobalVariable.getGlobalCodeRef(name);
                        break;
                    }

                    case Opcodes.STORE_GLOBAL_CODE: {
                        // Store global code: GlobalVariable.globalCodeRefs.put(name, codeRef)
                        int nameIdx = bytecode[pc++];
                        int codeReg = bytecode[pc++];
                        String name = code.stringPool[nameIdx];
                        RuntimeScalar codeRef = (RuntimeScalar) registers[codeReg];
                        // Store the code reference in the global namespace
                        GlobalVariable.globalCodeRefs.put(name, codeRef);
                        break;
                    }

                    case Opcodes.CREATE_CLOSURE: {
                        // Create closure with captured variables
                        // Format: CREATE_CLOSURE rd template_idx num_captures reg1 reg2 ...
                        int rd = bytecode[pc++];
                        int templateIdx = bytecode[pc++];
                        int numCaptures = bytecode[pc++];

                        // Get the template InterpretedCode from constants
                        InterpretedCode template = (InterpretedCode) code.constants[templateIdx];

                        // Capture the current register values
                        RuntimeBase[] capturedVars = new RuntimeBase[numCaptures];
                        for (int i = 0; i < numCaptures; i++) {
                            int captureReg = bytecode[pc++];
                            capturedVars[i] = registers[captureReg];
                        }

                        // Create a new InterpretedCode with the captured variables
                        InterpretedCode closureCode = new InterpretedCode(
                            template.bytecode,
                            template.constants,
                            template.stringPool,
                            template.maxRegisters,
                            capturedVars,  // The captured variables!
                            template.sourceName,
                            template.sourceLine,
                            template.pcToTokenIndex,
                            template.variableRegistry  // Preserve variable registry
                        );

                        // Wrap in RuntimeScalar
                        registers[rd] = new RuntimeScalar((RuntimeCode) closureCode);
                        break;
                    }

                    case Opcodes.SET_SCALAR: {
                        // Set scalar value: registers[rd].set(registers[rs])
                        // Used to set the value in a persistent scalar without overwriting the reference
                        int rd = bytecode[pc++];
                        int rs = bytecode[pc++];
                        // Auto-convert rs to scalar if needed
                        RuntimeBase rsBase = registers[rs];
                        RuntimeScalar rsScalar = (rsBase instanceof RuntimeScalar)
                                ? (RuntimeScalar) rsBase
                                : rsBase.scalar();
                        ((RuntimeScalar) registers[rd]).set(rsScalar);
                        break;
                    }

                    // =================================================================
                    // ARITHMETIC OPERATORS
                    // =================================================================

                    case Opcodes.ADD_SCALAR: {
                        // Addition: rd = rs1 + rs2
                        int rd = bytecode[pc++];
                        int rs1 = bytecode[pc++];
                        int rs2 = bytecode[pc++];

                        // Convert to scalar if needed
                        RuntimeBase val1 = registers[rs1];
                        RuntimeBase val2 = registers[rs2];
                        RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
                        RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();

                        // Calls SAME method as compiled code
                        registers[rd] = MathOperators.add(s1, s2);
                        break;
                    }

                    case Opcodes.SUB_SCALAR: {
                        // Subtraction: rd = rs1 - rs2
                        int rd = bytecode[pc++];
                        int rs1 = bytecode[pc++];
                        int rs2 = bytecode[pc++];

                        // Convert to scalar if needed
                        RuntimeBase val1 = registers[rs1];
                        RuntimeBase val2 = registers[rs2];
                        RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
                        RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();

                        registers[rd] = MathOperators.subtract(s1, s2);
                        break;
                    }

                    case Opcodes.MUL_SCALAR: {
                        // Multiplication: rd = rs1 * rs2
                        int rd = bytecode[pc++];
                        int rs1 = bytecode[pc++];
                        int rs2 = bytecode[pc++];

                        // Convert to scalar if needed
                        RuntimeBase val1 = registers[rs1];
                        RuntimeBase val2 = registers[rs2];
                        RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
                        RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();

                        registers[rd] = MathOperators.multiply(s1, s2);
                        break;
                    }

                    case Opcodes.DIV_SCALAR: {
                        // Division: rd = rs1 / rs2
                        int rd = bytecode[pc++];
                        int rs1 = bytecode[pc++];
                        int rs2 = bytecode[pc++];

                        // Convert to scalar if needed
                        RuntimeBase val1 = registers[rs1];
                        RuntimeBase val2 = registers[rs2];
                        RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
                        RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();

                        registers[rd] = MathOperators.divide(s1, s2);
                        break;
                    }

                    case Opcodes.MOD_SCALAR: {
                        // Modulus: rd = rs1 % rs2
                        int rd = bytecode[pc++];
                        int rs1 = bytecode[pc++];
                        int rs2 = bytecode[pc++];

                        // Convert to scalar if needed
                        RuntimeBase val1 = registers[rs1];
                        RuntimeBase val2 = registers[rs2];
                        RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
                        RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();

                        registers[rd] = MathOperators.modulus(s1, s2);
                        break;
                    }

                    case Opcodes.POW_SCALAR: {
                        // Exponentiation: rd = rs1 ** rs2
                        int rd = bytecode[pc++];
                        int rs1 = bytecode[pc++];
                        int rs2 = bytecode[pc++];

                        // Convert to scalar if needed
                        RuntimeBase val1 = registers[rs1];
                        RuntimeBase val2 = registers[rs2];
                        RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
                        RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();

                        registers[rd] = MathOperators.pow(s1, s2);
                        break;
                    }

                    case Opcodes.NEG_SCALAR: {
                        // Negation: rd = -rs
                        int rd = bytecode[pc++];
                        int rs = bytecode[pc++];
                        registers[rd] = MathOperators.unaryMinus((RuntimeScalar) registers[rs]);
                        break;
                    }

                    // Specialized unboxed operations (rare optimizations)
                    case Opcodes.ADD_SCALAR_INT: {
                        // Addition with immediate: rd = rs + immediate
                        int rd = bytecode[pc++];
                        int rs = bytecode[pc++];
                        int immediate = readInt(bytecode, pc);
                        pc += 2;
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
                        int rd = bytecode[pc++];
                        int rs1 = bytecode[pc++];
                        int rs2 = bytecode[pc++];
                        registers[rd] = StringOperators.stringConcat(
                            (RuntimeScalar) registers[rs1],
                            (RuntimeScalar) registers[rs2]
                        );
                        break;
                    }

                    case Opcodes.REPEAT: {
                        // String/list repetition: rd = rs1 x rs2
                        int rd = bytecode[pc++];
                        int rs1 = bytecode[pc++];
                        int rs2 = bytecode[pc++];
                        // Call Operator.repeat(base, count, context)
                        // Context: 1 = scalar context (for string repetition)
                        registers[rd] = Operator.repeat(
                            registers[rs1],
                            (RuntimeScalar) registers[rs2],
                            1  // scalar context
                        );
                        break;
                    }

                    case Opcodes.LENGTH: {
                        // String length: rd = length(rs)
                        int rd = bytecode[pc++];
                        int rs = bytecode[pc++];
                        registers[rd] = StringOperators.length((RuntimeScalar) registers[rs]);
                        break;
                    }

                    // =================================================================
                    // COMPARISON AND LOGICAL OPERATORS (opcodes 31-39) - Delegated
                    // =================================================================

                    case Opcodes.COMPARE_NUM:
                    case Opcodes.COMPARE_STR:
                    case Opcodes.EQ_NUM:
                    case Opcodes.NE_NUM:
                    case Opcodes.LT_NUM:
                    case Opcodes.GT_NUM:
                    case Opcodes.LE_NUM:
                    case Opcodes.GE_NUM:
                    case Opcodes.EQ_STR:
                    case Opcodes.NE_STR:
                    case Opcodes.NOT:
                        pc = executeComparisons(opcode, bytecode, pc, registers);
                        break;

                    // =================================================================
                    // TYPE AND REFERENCE OPERATORS (opcodes 102-105) - Delegated
                    // =================================================================

                    case Opcodes.DEFINED:
                    case Opcodes.REF:
                    case Opcodes.BLESS:
                    case Opcodes.ISA:
                    case Opcodes.PROTOTYPE:
                    case Opcodes.QUOTE_REGEX:
                        pc = executeTypeOps(opcode, bytecode, pc, registers, code);
                        break;

                    // =================================================================
                    // ITERATOR OPERATIONS - For efficient foreach loops
                    // =================================================================

                    case Opcodes.ITERATOR_CREATE: {
                        // Create iterator: rd = rs.iterator()
                        int rd = bytecode[pc++];
                        int rs = bytecode[pc++];

                        RuntimeBase iterable = registers[rs];
                        java.util.Iterator<RuntimeScalar> iterator = iterable.iterator();

                        // Store iterator as a constant (we need to preserve the Iterator object)
                        // Wrap in RuntimeScalar for storage
                        registers[rd] = new RuntimeScalar(iterator);
                        break;
                    }

                    case Opcodes.ITERATOR_HAS_NEXT: {
                        // Check iterator: rd = iterator.hasNext()
                        int rd = bytecode[pc++];
                        int iterReg = bytecode[pc++];

                        RuntimeScalar iterScalar = (RuntimeScalar) registers[iterReg];
                        @SuppressWarnings("unchecked")
                        java.util.Iterator<RuntimeScalar> iterator =
                            (java.util.Iterator<RuntimeScalar>) iterScalar.value;

                        boolean hasNext = iterator.hasNext();
                        registers[rd] = hasNext ? RuntimeScalarCache.scalarTrue : RuntimeScalarCache.scalarFalse;
                        break;
                    }

                    case Opcodes.ITERATOR_NEXT: {
                        // Get next element: rd = iterator.next()
                        int rd = bytecode[pc++];
                        int iterReg = bytecode[pc++];

                        RuntimeScalar iterScalar = (RuntimeScalar) registers[iterReg];
                        @SuppressWarnings("unchecked")
                        java.util.Iterator<RuntimeScalar> iterator =
                            (java.util.Iterator<RuntimeScalar>) iterScalar.value;

                        RuntimeScalar next = iterator.next();
                        registers[rd] = next;
                        break;
                    }

                    case Opcodes.FOREACH_NEXT_OR_EXIT: {
                        // Superinstruction for foreach loops
                        // Combines: hasNext check, next() call, and conditional exit
                        // Format: FOREACH_NEXT_OR_EXIT rd, iterReg, exitTarget
                        // If hasNext: rd = iterator.next(), continue to next instruction
                        // Else: jump to exitTarget (absolute address)
                        int rd = bytecode[pc++];
                        int iterReg = bytecode[pc++];
                        int exitTarget = readInt(bytecode, pc);  // Absolute target address
                        pc += 2;  // Skip the int we just read

                        RuntimeScalar iterScalar = (RuntimeScalar) registers[iterReg];
                        @SuppressWarnings("unchecked")
                        java.util.Iterator<RuntimeScalar> iterator =
                            (java.util.Iterator<RuntimeScalar>) iterScalar.value;

                        if (iterator.hasNext()) {
                            // Get next element and continue to body
                            registers[rd] = iterator.next();
                            // Fall through to next instruction (body)
                        } else {
                            // Exit loop - jump to absolute target
                            pc = exitTarget;  // ABSOLUTE jump, not relative!
                        }
                        break;
                    }

                    // =================================================================
                    // COMPOUND ASSIGNMENT OPERATORS (with overload support)
                    // =================================================================

                    case Opcodes.SUBTRACT_ASSIGN: {
                        // Compound assignment: rd = rd -= rs (checks for (-= overload first)
                        int rd = bytecode[pc++];
                        int rs = bytecode[pc++];

                        RuntimeBase val1 = registers[rd];
                        RuntimeBase val2 = registers[rs];
                        RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
                        RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();

                        registers[rd] = MathOperators.subtractAssign(s1, s2);
                        break;
                    }

                    case Opcodes.MULTIPLY_ASSIGN: {
                        // Compound assignment: rd = rd *= rs (checks for (*= overload first)
                        int rd = bytecode[pc++];
                        int rs = bytecode[pc++];

                        RuntimeBase val1 = registers[rd];
                        RuntimeBase val2 = registers[rs];
                        RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
                        RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();

                        registers[rd] = MathOperators.multiplyAssign(s1, s2);
                        break;
                    }

                    case Opcodes.DIVIDE_ASSIGN: {
                        // Compound assignment: rd = rd /= rs (checks for (/= overload first)
                        int rd = bytecode[pc++];
                        int rs = bytecode[pc++];

                        RuntimeBase val1 = registers[rd];
                        RuntimeBase val2 = registers[rs];
                        RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
                        RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();

                        registers[rd] = MathOperators.divideAssign(s1, s2);
                        break;
                    }

                    case Opcodes.MODULUS_ASSIGN: {
                        // Compound assignment: rd = rd %= rs (checks for (%= overload first)
                        int rd = bytecode[pc++];
                        int rs = bytecode[pc++];

                        RuntimeBase val1 = registers[rd];
                        RuntimeBase val2 = registers[rs];
                        RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
                        RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();

                        registers[rd] = MathOperators.modulusAssign(s1, s2);
                        break;
                    }

                    // =================================================================
                    // ARRAY OPERATIONS
                    // =================================================================

                    case Opcodes.ARRAY_GET: {
                        // Array element access: rd = array[index]
                        int rd = bytecode[pc++];
                        int arrayReg = bytecode[pc++];
                        int indexReg = bytecode[pc++];

                        RuntimeBase arrayBase = registers[arrayReg];
                        RuntimeScalar idx = (RuntimeScalar) registers[indexReg];

                        if (arrayBase instanceof RuntimeArray) {
                            RuntimeArray arr = (RuntimeArray) arrayBase;
                            registers[rd] = arr.get(idx.getInt());
                        } else if (arrayBase instanceof RuntimeList) {
                            RuntimeList list = (RuntimeList) arrayBase;
                            int index = idx.getInt();
                            if (index < 0) index = list.elements.size() + index;
                            registers[rd] = (index >= 0 && index < list.elements.size())
                                ? list.elements.get(index)
                                : new RuntimeScalar();
                        } else {
                            throw new RuntimeException("ARRAY_GET: register " + arrayReg + " contains " +
                                (arrayBase == null ? "null" : arrayBase.getClass().getName()) +
                                " instead of RuntimeArray or RuntimeList");
                        }
                        break;
                    }

                    case Opcodes.ARRAY_SET: {
                        // Array element store: array[index] = value
                        int arrayReg = bytecode[pc++];
                        int indexReg = bytecode[pc++];
                        int valueReg = bytecode[pc++];
                        RuntimeArray arr = (RuntimeArray) registers[arrayReg];
                        RuntimeScalar idx = (RuntimeScalar) registers[indexReg];
                        RuntimeScalar val = (RuntimeScalar) registers[valueReg];
                        arr.get(idx.getInt()).set(val);  // Get element then set its value
                        break;
                    }

                    case Opcodes.ARRAY_PUSH: {
                        // Array push: push(@array, value)
                        int arrayReg = bytecode[pc++];
                        int valueReg = bytecode[pc++];
                        RuntimeArray arr = (RuntimeArray) registers[arrayReg];
                        RuntimeBase val = registers[valueReg];
                        arr.push(val);
                        break;
                    }

                    case Opcodes.ARRAY_POP: {
                        // Array pop: rd = pop(@array)
                        int rd = bytecode[pc++];
                        int arrayReg = bytecode[pc++];
                        RuntimeArray arr = (RuntimeArray) registers[arrayReg];
                        registers[rd] = RuntimeArray.pop(arr);
                        break;
                    }

                    case Opcodes.ARRAY_SHIFT: {
                        // Array shift: rd = shift(@array)
                        int rd = bytecode[pc++];
                        int arrayReg = bytecode[pc++];
                        RuntimeArray arr = (RuntimeArray) registers[arrayReg];
                        registers[rd] = RuntimeArray.shift(arr);
                        break;
                    }

                    case Opcodes.ARRAY_UNSHIFT: {
                        // Array unshift: unshift(@array, value)
                        int arrayReg = bytecode[pc++];
                        int valueReg = bytecode[pc++];
                        RuntimeArray arr = (RuntimeArray) registers[arrayReg];
                        RuntimeBase val = registers[valueReg];
                        RuntimeArray.unshift(arr, val);
                        break;
                    }

                    case Opcodes.ARRAY_SIZE: {
                        // Array size: rd = scalar(@array) or scalar(value)
                        // Use polymorphic scalar() method - arrays return size, scalars return themselves
                        // Special case for RuntimeList: return size, not last element
                        int rd = bytecode[pc++];
                        int operandReg = bytecode[pc++];
                        RuntimeBase operand = registers[operandReg];
                        if (operand instanceof RuntimeList) {
                            // For RuntimeList in list assignment context, return the count
                            registers[rd] = new RuntimeScalar(((RuntimeList) operand).size());
                        } else {
                            registers[rd] = operand.scalar();
                        }
                        break;
                    }

                    case Opcodes.CREATE_ARRAY: {
                        // Create array reference from list: rd = new RuntimeArray(rs_list).createReference()
                        // Array literals always return references in Perl
                        int rd = bytecode[pc++];
                        int listReg = bytecode[pc++];

                        // Convert to list (polymorphic - works for PerlRange, RuntimeList, etc.)
                        RuntimeBase source = registers[listReg];
                        RuntimeArray array;
                        if (source instanceof RuntimeArray) {
                            // Already an array - pass through
                            array = (RuntimeArray) source;
                        } else {
                            // Convert to list, then to array (works for PerlRange, RuntimeList, etc.)
                            RuntimeList list = source.getList();
                            array = new RuntimeArray(list);
                        }

                        // Create reference (array literals always return references!)
                        registers[rd] = array.createReference();
                        break;
                    }

                    // =================================================================
                    // HASH OPERATIONS
                    // =================================================================

                    case Opcodes.HASH_GET: {
                        // Hash element access: rd = hash{key}
                        int rd = bytecode[pc++];
                        int hashReg = bytecode[pc++];
                        int keyReg = bytecode[pc++];
                        RuntimeHash hash = (RuntimeHash) registers[hashReg];
                        RuntimeScalar key = (RuntimeScalar) registers[keyReg];
                        // Uses RuntimeHash API directly
                        registers[rd] = hash.get(key);
                        break;
                    }

                    case Opcodes.HASH_SET: {
                        // Hash element store: hash{key} = value
                        int hashReg = bytecode[pc++];
                        int keyReg = bytecode[pc++];
                        int valueReg = bytecode[pc++];
                        RuntimeHash hash = (RuntimeHash) registers[hashReg];
                        RuntimeScalar key = (RuntimeScalar) registers[keyReg];
                        RuntimeScalar val = (RuntimeScalar) registers[valueReg];
                        hash.put(key.toString(), val);  // Convert key to String
                        break;
                    }

                    case Opcodes.HASH_EXISTS: {
                        // Check if hash key exists: rd = exists $hash{key}
                        int rd = bytecode[pc++];
                        int hashReg = bytecode[pc++];
                        int keyReg = bytecode[pc++];
                        RuntimeHash hash = (RuntimeHash) registers[hashReg];
                        RuntimeScalar key = (RuntimeScalar) registers[keyReg];
                        registers[rd] = hash.exists(key);
                        break;
                    }

                    case Opcodes.HASH_DELETE: {
                        // Delete hash key: rd = delete $hash{key}
                        int rd = bytecode[pc++];
                        int hashReg = bytecode[pc++];
                        int keyReg = bytecode[pc++];
                        RuntimeHash hash = (RuntimeHash) registers[hashReg];
                        RuntimeScalar key = (RuntimeScalar) registers[keyReg];
                        registers[rd] = hash.delete(key);
                        break;
                    }

                    case Opcodes.HASH_KEYS: {
                        // Get hash keys: rd = keys %hash
                        int rd = bytecode[pc++];
                        int hashReg = bytecode[pc++];
                        RuntimeHash hash = (RuntimeHash) registers[hashReg];
                        registers[rd] = hash.keys();
                        break;
                    }

                    case Opcodes.HASH_VALUES: {
                        // Get hash values: rd = values %hash
                        int rd = bytecode[pc++];
                        int hashReg = bytecode[pc++];
                        RuntimeHash hash = (RuntimeHash) registers[hashReg];
                        registers[rd] = hash.values();
                        break;
                    }

                    // =================================================================
                    // SUBROUTINE CALLS
                    // =================================================================

                    case Opcodes.CALL_SUB: {
                        // Call subroutine: rd = coderef->(args)
                        // May return RuntimeControlFlowList!
                        int rd = bytecode[pc++];
                        int coderefReg = bytecode[pc++];
                        int argsReg = bytecode[pc++];
                        int context = bytecode[pc++];

                        // Auto-convert coderef to scalar if needed
                        RuntimeBase codeRefBase = registers[coderefReg];
                        RuntimeScalar codeRef = (codeRefBase instanceof RuntimeScalar)
                                ? (RuntimeScalar) codeRefBase
                                : codeRefBase.scalar();
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

                        // Convert to scalar if called in scalar context
                        if (context == RuntimeContextType.SCALAR) {
                            registers[rd] = result.scalar();
                        } else {
                            registers[rd] = result;
                        }

                        // Check for control flow (last/next/redo/goto/tail-call)
                        if (result.isNonLocalGoto()) {
                            // Propagate control flow up the call stack
                            return result;
                        }
                        break;
                    }

                    case Opcodes.CALL_METHOD: {
                        // Call method: rd = RuntimeCode.call(invocant, method, currentSub, args, context)
                        // May return RuntimeControlFlowList!
                        int rd = bytecode[pc++];
                        int invocantReg = bytecode[pc++];
                        int methodReg = bytecode[pc++];
                        int currentSubReg = bytecode[pc++];
                        int argsReg = bytecode[pc++];
                        int context = bytecode[pc++];

                        RuntimeScalar invocant = (RuntimeScalar) registers[invocantReg];
                        RuntimeScalar method = (RuntimeScalar) registers[methodReg];
                        RuntimeScalar currentSub = (RuntimeScalar) registers[currentSubReg];
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

                        // RuntimeCode.call handles method resolution and dispatch
                        RuntimeList result = RuntimeCode.call(invocant, method, currentSub, callArgs, context);

                        // Convert to scalar if called in scalar context
                        if (context == RuntimeContextType.SCALAR) {
                            registers[rd] = result.scalar();
                        } else {
                            registers[rd] = result;
                        }

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
                        int rd = bytecode[pc++];
                        int labelIdx = bytecode[pc++];
                        String label = labelIdx == 255 ? null : code.stringPool[labelIdx];
                        registers[rd] = new RuntimeControlFlowList(
                            ControlFlowType.LAST, label,
                            code.sourceName, code.sourceLine
                        );
                        break;
                    }

                    case Opcodes.CREATE_NEXT: {
                        // Create NEXT control flow: rd = RuntimeControlFlowList(NEXT, label)
                        int rd = bytecode[pc++];
                        int labelIdx = bytecode[pc++];
                        String label = labelIdx == 255 ? null : code.stringPool[labelIdx];
                        registers[rd] = new RuntimeControlFlowList(
                            ControlFlowType.NEXT, label,
                            code.sourceName, code.sourceLine
                        );
                        break;
                    }

                    case Opcodes.CREATE_REDO: {
                        // Create REDO control flow: rd = RuntimeControlFlowList(REDO, label)
                        int rd = bytecode[pc++];
                        int labelIdx = bytecode[pc++];
                        String label = labelIdx == 255 ? null : code.stringPool[labelIdx];
                        registers[rd] = new RuntimeControlFlowList(
                            ControlFlowType.REDO, label,
                            code.sourceName, code.sourceLine
                        );
                        break;
                    }

                    case Opcodes.IS_CONTROL_FLOW: {
                        // Check if value is control flow: rd = (rs instanceof RuntimeControlFlowList)
                        int rd = bytecode[pc++];
                        int rs = bytecode[pc++];
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
                        int contentReg = bytecode[pc++];
                        int filehandleReg = bytecode[pc++];

                        Object val = registers[contentReg];

                        // Filehandle should be scalar - convert if needed
                        RuntimeBase fhBase = registers[filehandleReg];
                        RuntimeScalar fh = (fhBase instanceof RuntimeScalar)
                            ? (RuntimeScalar) fhBase
                            : fhBase.scalar();

                        RuntimeList list;
                        if (val instanceof RuntimeList) {
                            list = (RuntimeList) val;
                        } else if (val instanceof RuntimeArray) {
                            // Convert RuntimeArray to RuntimeList
                            list = new RuntimeList();
                            for (RuntimeScalar elem : (RuntimeArray) val) {
                                list.add(elem);
                            }
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
                        int contentReg = bytecode[pc++];
                        int filehandleReg = bytecode[pc++];

                        Object val = registers[contentReg];

                        // Filehandle should be scalar - convert if needed
                        RuntimeBase fhBase = registers[filehandleReg];
                        RuntimeScalar fh = (fhBase instanceof RuntimeScalar)
                            ? (RuntimeScalar) fhBase
                            : fhBase.scalar();

                        RuntimeList list;
                        if (val instanceof RuntimeList) {
                            list = (RuntimeList) val;
                        } else if (val instanceof RuntimeArray) {
                            // Convert RuntimeArray to RuntimeList
                            list = new RuntimeList();
                            for (RuntimeScalar elem : (RuntimeArray) val) {
                                list.add(elem);
                            }
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
                        int rd = bytecode[pc++];
                        registers[rd] = MathOperators.add((RuntimeScalar) registers[rd], 1);
                        break;
                    }

                    case Opcodes.DEC_REG: {
                        // Decrement register in-place: r--
                        int rd = bytecode[pc++];
                        registers[rd] = MathOperators.subtract((RuntimeScalar) registers[rd], 1);
                        break;
                    }

                    case Opcodes.ADD_ASSIGN: {
                        // Add and assign: rd = rd + rs
                        int rd = bytecode[pc++];
                        int rs = bytecode[pc++];
                        registers[rd] = MathOperators.add(
                            (RuntimeScalar) registers[rd],
                            (RuntimeScalar) registers[rs]
                        );
                        break;
                    }

                    case Opcodes.ADD_ASSIGN_INT: {
                        // Add immediate and assign: rd = rd + imm
                        int rd = bytecode[pc++];
                        int immediate = readInt(bytecode, pc);
                        pc += 2;
                        registers[rd] = MathOperators.add((RuntimeScalar) registers[rd], immediate);
                        break;
                    }

                    case Opcodes.STRING_CONCAT_ASSIGN: {
                        // String concatenation and assign: rd .= rs
                        int rd = bytecode[pc++];
                        int rs = bytecode[pc++];
                        registers[rd] = StringOperators.stringConcat(
                            (RuntimeScalar) registers[rd],
                            (RuntimeScalar) registers[rs]
                        );
                        break;
                    }

                    case Opcodes.PUSH_LOCAL_VARIABLE: {
                        // Push variable to local stack: DynamicVariableManager.pushLocalVariable(rs)
                        int rs = bytecode[pc++];
                        org.perlonjava.runtime.DynamicVariableManager.pushLocalVariable(registers[rs]);
                        break;
                    }

                    case Opcodes.STORE_GLOB: {
                        // Store to glob: glob.set(value)
                        int globReg = bytecode[pc++];
                        int valueReg = bytecode[pc++];
                        ((org.perlonjava.runtime.RuntimeGlob) registers[globReg]).set((RuntimeScalar) registers[valueReg]);
                        break;
                    }

                    case Opcodes.OPEN: {
                        // Open file: rd = IOOperator.open(ctx, args...)
                        int rd = bytecode[pc++];
                        int ctx = bytecode[pc++];
                        int argsReg = bytecode[pc++];
                        RuntimeArray argsArray = (RuntimeArray) registers[argsReg];
                        RuntimeBase[] argsVarargs = argsArray.elements.toArray(new RuntimeBase[0]);
                        registers[rd] = org.perlonjava.operators.IOOperator.open(ctx, argsVarargs);
                        break;
                    }

                    case Opcodes.READLINE: {
                        // Read line from filehandle: rd = Readline.readline(fh_ref, ctx)
                        int rd = bytecode[pc++];
                        int fhReg = bytecode[pc++];
                        int ctx = bytecode[pc++];
                        registers[rd] = org.perlonjava.operators.Readline.readline(
                            (RuntimeScalar) registers[fhReg], ctx
                        );
                        break;
                    }

                    case Opcodes.MATCH_REGEX: {
                        // Match regex: rd = RuntimeRegex.matchRegex(quotedRegex, string, ctx)
                        int rd = bytecode[pc++];
                        int stringReg = bytecode[pc++];
                        int regexReg = bytecode[pc++];
                        int ctx = bytecode[pc++];
                        registers[rd] = org.perlonjava.regex.RuntimeRegex.matchRegex(
                            (RuntimeScalar) registers[regexReg],  // quotedRegex first
                            (RuntimeScalar) registers[stringReg], // string second
                            ctx
                        );
                        break;
                    }

                    case Opcodes.CHOMP: {
                        // Chomp: rd = rs.chomp()
                        int rd = bytecode[pc++];
                        int rs = bytecode[pc++];
                        registers[rd] = registers[rs].chomp();
                        break;
                    }

                    case Opcodes.WANTARRAY: {
                        // Get wantarray context: rd = Operator.wantarray(wantarrayReg)
                        int rd = bytecode[pc++];
                        int wantarrayReg = bytecode[pc++];
                        int ctx = ((RuntimeScalar) registers[wantarrayReg]).getInt();
                        registers[rd] = org.perlonjava.operators.Operator.wantarray(ctx);
                        break;
                    }

                    case Opcodes.REQUIRE: {
                        // Require module or version: rd = ModuleOperators.require(rs)
                        int rd = bytecode[pc++];
                        int rs = bytecode[pc++];
                        registers[rd] = org.perlonjava.operators.ModuleOperators.require((RuntimeScalar) registers[rs]);
                        break;
                    }

                    case Opcodes.POS: {
                        // Get regex position: rd = rs.pos()
                        int rd = bytecode[pc++];
                        int rs = bytecode[pc++];
                        registers[rd] = ((RuntimeScalar) registers[rs]).pos();
                        break;
                    }

                    case Opcodes.INDEX: {
                        // Find substring position: rd = StringOperators.index(str, substr, pos)
                        int rd = bytecode[pc++];
                        int strReg = bytecode[pc++];
                        int substrReg = bytecode[pc++];
                        int posReg = bytecode[pc++];
                        registers[rd] = org.perlonjava.operators.StringOperators.index(
                            (RuntimeScalar) registers[strReg],
                            (RuntimeScalar) registers[substrReg],
                            (RuntimeScalar) registers[posReg]
                        );
                        break;
                    }

                    case Opcodes.RINDEX: {
                        // Find substring position from end: rd = StringOperators.rindex(str, substr, pos)
                        int rd = bytecode[pc++];
                        int strReg = bytecode[pc++];
                        int substrReg = bytecode[pc++];
                        int posReg = bytecode[pc++];
                        registers[rd] = org.perlonjava.operators.StringOperators.rindex(
                            (RuntimeScalar) registers[strReg],
                            (RuntimeScalar) registers[substrReg],
                            (RuntimeScalar) registers[posReg]
                        );
                        break;
                    }

                    case Opcodes.PRE_AUTOINCREMENT: {
                        // Pre-increment: ++rd
                        int rd = bytecode[pc++];
                        ((RuntimeScalar) registers[rd]).preAutoIncrement();
                        break;
                    }

                    case Opcodes.POST_AUTOINCREMENT: {
                        // Post-increment: rd = rs++
                        // The postAutoIncrement() method increments the variable and returns the OLD value
                        int rd = bytecode[pc++];  // Destination register for old value
                        int rs = bytecode[pc++];  // Source variable register
                        registers[rd] = ((RuntimeScalar) registers[rs]).postAutoIncrement();
                        break;
                    }

                    case Opcodes.PRE_AUTODECREMENT: {
                        // Pre-decrement: --rd
                        int rd = bytecode[pc++];
                        ((RuntimeScalar) registers[rd]).preAutoDecrement();
                        break;
                    }

                    case Opcodes.POST_AUTODECREMENT: {
                        // Post-decrement: rd = rs--
                        // The postAutoDecrement() method decrements the variable and returns the OLD value
                        int rd = bytecode[pc++];  // Destination register for old value
                        int rs = bytecode[pc++];  // Source variable register
                        registers[rd] = ((RuntimeScalar) registers[rs]).postAutoDecrement();
                        break;
                    }

                    // =================================================================
                    // ERROR HANDLING
                    // =================================================================

                    case Opcodes.DIE: {
                        // Die with message and precomputed location: die(msgReg, locationReg)
                        int msgReg = bytecode[pc++];
                        int locationReg = bytecode[pc++];
                        RuntimeBase message = registers[msgReg];
                        RuntimeScalar where = (RuntimeScalar) registers[locationReg];

                        // Call WarnDie.die() with precomputed location (zero overhead!)
                        WarnDie.die(message, where, code.sourceName, code.sourceLine);

                        // Should never reach here (die throws exception)
                        throw new RuntimeException("die() did not throw exception");
                    }

                    case Opcodes.WARN: {
                        // Warn with message and precomputed location: warn(msgReg, locationReg)
                        int msgReg = bytecode[pc++];
                        int locationReg = bytecode[pc++];
                        RuntimeBase message = registers[msgReg];
                        RuntimeScalar where = (RuntimeScalar) registers[locationReg];

                        // Call WarnDie.warn() with precomputed location
                        WarnDie.warn(message, where, code.sourceName, code.sourceLine);
                        break;
                    }

                    // =================================================================
                    // REFERENCE OPERATIONS
                    // =================================================================

                    case Opcodes.CREATE_REF: {
                        // Create reference: rd = rs.createReference()
                        int rd = bytecode[pc++];
                        int rs = bytecode[pc++];
                        RuntimeBase value = registers[rs];
                        registers[rd] = value.createReference();
                        break;
                    }

                    case Opcodes.DEREF: {
                        // Dereference: rd = rs (dereferencing depends on context)
                        // For now, just copy the reference - proper dereferencing
                        // is context-dependent and handled by specific operators
                        int rd = bytecode[pc++];
                        int rs = bytecode[pc++];
                        registers[rd] = registers[rs];
                        break;
                    }

                    case Opcodes.GET_TYPE: {
                        // Get type: rd = new RuntimeScalar(rs.type)
                        int rd = bytecode[pc++];
                        int rs = bytecode[pc++];
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
                        // Format: [EVAL_TRY] [catch_target_high] [catch_target_low]
                        // catch_target is absolute bytecode address (4 bytes)

                        int catchPc = readInt(bytecode, pc);  // Read 4-byte absolute address
                        pc += 2;  // Skip the 2 shorts we just read

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

                        int rd = bytecode[pc++];

                        // WarnDie.catchEval() should have already been called to set $@
                        // Just store undef as the eval result
                        registers[rd] = RuntimeScalarCache.scalarUndef;
                        break;
                    }

                    // =================================================================
                    // LIST OPERATIONS
                    // =================================================================

                    case Opcodes.LIST_TO_SCALAR: {
                        // Convert list to scalar context (returns size)
                        int rd = bytecode[pc++];
                        int rs = bytecode[pc++];
                        RuntimeBase val = registers[rs];
                        if (val instanceof RuntimeList) {
                            registers[rd] = new RuntimeScalar(((RuntimeList) val).elements.size());
                        } else if (val instanceof RuntimeArray) {
                            registers[rd] = new RuntimeScalar(((RuntimeArray) val).size());
                        } else {
                            // Already a scalar
                            registers[rd] = val.scalar();
                        }
                        break;
                    }

                    case Opcodes.SCALAR_TO_LIST: {
                        // Convert scalar to RuntimeList
                        int rd = bytecode[pc++];
                        int rs = bytecode[pc++];
                        RuntimeBase val = registers[rs];
                        if (val instanceof RuntimeList) {
                            // Already a list
                            registers[rd] = val;
                        } else if (val instanceof RuntimeArray) {
                            // Convert array to list
                            RuntimeList list = new RuntimeList();
                            for (RuntimeScalar elem : (RuntimeArray) val) {
                                list.elements.add(elem);
                            }
                            registers[rd] = list;
                        } else {
                            // Scalar to list - wrap in a list
                            RuntimeList list = new RuntimeList();
                            list.elements.add(val.scalar());
                            registers[rd] = list;
                        }
                        break;
                    }

                    case Opcodes.CREATE_LIST: {
                        // Create RuntimeList from registers
                        // Format: [CREATE_LIST] [rd] [count] [rs1] [rs2] ... [rsN]

                        int rd = bytecode[pc++];
                        int count = bytecode[pc++];

                        // Optimize for common cases
                        if (count == 0) {
                            // Empty list - fastest path
                            registers[rd] = new RuntimeList();
                        } else if (count == 1) {
                            // Single element - avoid loop overhead
                            int rs = bytecode[pc++];
                            RuntimeList list = new RuntimeList();
                            list.add(registers[rs]);
                            registers[rd] = list;
                        } else {
                            // Multiple elements - preallocate and populate
                            RuntimeList list = new RuntimeList();

                            // Read all register indices and add elements
                            for (int i = 0; i < count; i++) {
                                int rs = bytecode[pc++];
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
                        int rd = bytecode[pc++];
                        int separatorReg = bytecode[pc++];
                        int listReg = bytecode[pc++];

                        // Separator should be scalar - convert if needed
                        RuntimeBase separatorBase = registers[separatorReg];
                        RuntimeScalar separator = (separatorBase instanceof RuntimeScalar)
                            ? (RuntimeScalar) separatorBase
                            : separatorBase.scalar();

                        RuntimeBase list = registers[listReg];

                        // Call StringOperators.joinForInterpolation (doesn't warn on undef)
                        registers[rd] = org.perlonjava.operators.StringOperators.joinForInterpolation(separator, list);
                        break;
                    }

                    case Opcodes.SELECT: {
                        // Select default output filehandle: rd = IOOperator.select(list, SCALAR)
                        int rd = bytecode[pc++];
                        int listReg = bytecode[pc++];

                        RuntimeList list = (RuntimeList) registers[listReg];
                        RuntimeScalar result = org.perlonjava.operators.IOOperator.select(list, RuntimeContextType.SCALAR);
                        registers[rd] = result;
                        break;
                    }

                    case Opcodes.RANGE: {
                        // Create range: rd = PerlRange.createRange(rs_start, rs_end)
                        int rd = bytecode[pc++];
                        int startReg = bytecode[pc++];
                        int endReg = bytecode[pc++];

                        RuntimeBase startBase = registers[startReg];
                        RuntimeBase endBase = registers[endReg];

                        // Handle null registers by creating undef scalars
                        RuntimeScalar start = (startBase instanceof RuntimeScalar) ? (RuntimeScalar) startBase :
                                             (startBase == null) ? new RuntimeScalar() : startBase.scalar();
                        RuntimeScalar end = (endBase instanceof RuntimeScalar) ? (RuntimeScalar) endBase :
                                           (endBase == null) ? new RuntimeScalar() : endBase.scalar();

                        PerlRange range = PerlRange.createRange(start, end);
                        registers[rd] = range;
                        break;
                    }

                    case Opcodes.CREATE_HASH: {
                        // Create hash reference from list: rd = RuntimeHash.createHash(rs_list).createReference()
                        // Hash literals always return references in Perl
                        // This flattens any arrays in the list and creates key-value pairs
                        int rd = bytecode[pc++];
                        int listReg = bytecode[pc++];

                        RuntimeBase list = registers[listReg];
                        RuntimeHash hash = RuntimeHash.createHash(list);

                        // Create reference (hash literals always return references!)
                        registers[rd] = hash.createReference();
                        break;
                    }

                    case Opcodes.RAND: {
                        // Random number: rd = Random.rand(max)
                        int rd = bytecode[pc++];
                        int maxReg = bytecode[pc++];

                        RuntimeScalar max = (RuntimeScalar) registers[maxReg];
                        registers[rd] = org.perlonjava.operators.Random.rand(max);
                        break;
                    }

                    case Opcodes.MAP: {
                        // Map operator: rd = ListOperators.map(list, closure, ctx)
                        int rd = bytecode[pc++];
                        int listReg = bytecode[pc++];
                        int closureReg = bytecode[pc++];
                        int ctx = bytecode[pc++];

                        RuntimeBase listBase = registers[listReg];
                        RuntimeList list = listBase.getList();
                        RuntimeScalar closure = (RuntimeScalar) registers[closureReg];
                        RuntimeList result = org.perlonjava.operators.ListOperators.map(list, closure, ctx);
                        registers[rd] = result;
                        break;
                    }

                    case Opcodes.GREP: {
                        // Grep operator: rd = ListOperators.grep(list, closure, ctx)
                        int rd = bytecode[pc++];
                        int listReg = bytecode[pc++];
                        int closureReg = bytecode[pc++];
                        int ctx = bytecode[pc++];

                        RuntimeBase listBase = registers[listReg];
                        RuntimeList list = listBase.getList();
                        RuntimeScalar closure = (RuntimeScalar) registers[closureReg];
                        RuntimeList result = org.perlonjava.operators.ListOperators.grep(list, closure, ctx);
                        registers[rd] = result;
                        break;
                    }

                    case Opcodes.SORT: {
                        // Sort operator: rd = ListOperators.sort(list, closure, package)
                        int rd = bytecode[pc++];
                        int listReg = bytecode[pc++];
                        int closureReg = bytecode[pc++];
                        int packageIdx = readInt(bytecode, pc);
                        pc += 2;

                        RuntimeBase listBase = registers[listReg];
                        RuntimeList list = listBase.getList();
                        RuntimeScalar closure = (RuntimeScalar) registers[closureReg];
                        String packageName = code.stringPool[packageIdx];
                        RuntimeList result = org.perlonjava.operators.ListOperators.sort(list, closure, packageName);
                        registers[rd] = result;
                        break;
                    }

                    case Opcodes.NEW_ARRAY: {
                        // Create empty array: rd = new RuntimeArray()
                        int rd = bytecode[pc++];
                        registers[rd] = new RuntimeArray();
                        break;
                    }

                    case Opcodes.NEW_HASH: {
                        // Create empty hash: rd = new RuntimeHash()
                        int rd = bytecode[pc++];
                        registers[rd] = new RuntimeHash();
                        break;
                    }

                    case Opcodes.ARRAY_SET_FROM_LIST: {
                        // Set array content from list: array_reg.setFromList(list_reg)
                        // Format: [ARRAY_SET_FROM_LIST] [array_reg] [list_reg]
                        int arrayReg = bytecode[pc++];
                        int listReg = bytecode[pc++];

                        RuntimeArray array = (RuntimeArray) registers[arrayReg];
                        RuntimeBase listBase = registers[listReg];
                        RuntimeList list = listBase.getList();

                        // setFromList clears and repopulates the array
                        array.setFromList(list);
                        break;
                    }

                    case Opcodes.HASH_SET_FROM_LIST: {
                        // Set hash content from list: hash_reg = RuntimeHash.createHash(list_reg)
                        // Format: [HASH_SET_FROM_LIST] [hash_reg] [list_reg]
                        int hashReg = bytecode[pc++];
                        int listReg = bytecode[pc++];

                        RuntimeHash existingHash = (RuntimeHash) registers[hashReg];
                        RuntimeBase listBase = registers[listReg];

                        // Create new hash from list, then copy elements to existing hash
                        RuntimeHash newHash = RuntimeHash.createHash(listBase);
                        existingHash.elements = newHash.elements;
                        break;
                    }

                    // =================================================================
                    // PHASE 2: DIRECT OPCODES (114-154) - Range delegation
                    // =================================================================
                    // These operations were promoted from SLOW_OP for better performance.
                    // Organized in CONTIGUOUS groups for JVM tableswitch optimization.

                    // Group 1-2: Dereferencing and Slicing (114-121)
                    case Opcodes.DEREF_ARRAY:
                    case Opcodes.DEREF_HASH:
                    case Opcodes.ARRAY_SLICE:
                    case Opcodes.ARRAY_SLICE_SET:
                    case Opcodes.HASH_SLICE:
                    case Opcodes.HASH_SLICE_SET:
                    case Opcodes.HASH_SLICE_DELETE:
                    case Opcodes.LIST_SLICE_FROM:
                        pc = executeSliceOps(opcode, bytecode, pc, registers, code);
                        break;

                    // Group 3-4: Array/String/Exists/Delete (122-127)
                    case Opcodes.SPLICE:
                    case Opcodes.REVERSE:
                    case Opcodes.SPLIT:
                    case Opcodes.LENGTH_OP:
                    case Opcodes.EXISTS:
                    case Opcodes.DELETE:
                        pc = executeArrayStringOps(opcode, bytecode, pc, registers, code);
                        break;

                    // Group 5: Closure/Scope (128-131)
                    case Opcodes.RETRIEVE_BEGIN_SCALAR:
                    case Opcodes.RETRIEVE_BEGIN_ARRAY:
                    case Opcodes.RETRIEVE_BEGIN_HASH:
                    case Opcodes.LOCAL_SCALAR:
                        pc = executeScopeOps(opcode, bytecode, pc, registers, code);
                        break;

                    // Group 6-8: System Calls and IPC (132-150)
                    case Opcodes.CHOWN:
                    case Opcodes.WAITPID:
                    case Opcodes.FORK:
                    case Opcodes.GETPPID:
                    case Opcodes.GETPGRP:
                    case Opcodes.SETPGRP:
                    case Opcodes.GETPRIORITY:
                    case Opcodes.SETPRIORITY:
                    case Opcodes.GETSOCKOPT:
                    case Opcodes.SETSOCKOPT:
                    case Opcodes.SYSCALL:
                    case Opcodes.SEMGET:
                    case Opcodes.SEMOP:
                    case Opcodes.MSGGET:
                    case Opcodes.MSGSND:
                    case Opcodes.MSGRCV:
                    case Opcodes.SHMGET:
                    case Opcodes.SHMREAD:
                    case Opcodes.SHMWRITE:
                        pc = executeSystemOps(opcode, bytecode, pc, registers);
                        break;

                    // Group 9: Special I/O (151-154)
                    case Opcodes.EVAL_STRING:
                    case Opcodes.SELECT_OP:
                    case Opcodes.LOAD_GLOB:
                    case Opcodes.SLEEP_OP:
                        pc = executeSpecialIO(opcode, bytecode, pc, registers, code);
                        break;

                    // =================================================================
                    // SLOW OPERATIONS (DEPRECATED)
                    // =================================================================

                    // DEPRECATED: SLOW_OP removed - all operations now use direct opcodes (114-154)

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

        } catch (ClassCastException e) {
            // Special handling for ClassCastException to show which opcode is failing
            // Check if we're inside an eval block first
            if (!evalCatchStack.isEmpty()) {
                evalCatchStack.pop();
                WarnDie.catchEval(e);
                return new RuntimeList();
            }

            // Not in eval - show detailed error with bytecode context
            int errorPc = Math.max(0, pc - 1); // Go back one instruction

            // Show bytecode context (10 bytes before errorPc)
            StringBuilder bcContext = new StringBuilder();
            bcContext.append("\nBytecode context: [");
            for (int i = Math.max(0, errorPc - 10); i < Math.min(bytecode.length, errorPc + 5); i++) {
                if (i == errorPc) {
                    bcContext.append(" >>>");
                }
                bcContext.append(String.format(" %02X", bytecode[i] & 0xFF));
                if (i == errorPc) {
                    bcContext.append("<<<");
                }
            }
            bcContext.append(" ]");

            String errorMessage = "ClassCastException" + bcContext + ": " + e.getMessage();
            throw new RuntimeException(formatInterpreterError(code, errorPc, new Exception(errorMessage)), e);
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
            // If it's already a PerlDieException, re-throw as-is for proper formatting
            if (e instanceof PerlDieException) {
                throw (PerlDieException) e;
            }

            // Wrap other exceptions with interpreter context including bytecode context
            String errorMessage = formatInterpreterError(code, pc, e);
            throw new RuntimeException(errorMessage, e);
        } finally {
            // Always pop the interpreter state
            InterpreterState.pop();
        }
    }

    /**
     * Handle type and reference operations (opcodes 62-70, 102-105).
     * Separated to keep main execute() under JIT compilation limit.
     *
     * @return Updated program counter
     */
    private static int executeTypeOps(short opcode, short[] bytecode, int pc,
                                      RuntimeBase[] registers, InterpretedCode code) {
        switch (opcode) {
            case Opcodes.CREATE_LAST: {
                int rd = bytecode[pc++];
                int labelIdx = bytecode[pc++];
                String label = labelIdx == 255 ? null : code.stringPool[labelIdx];
                registers[rd] = new RuntimeControlFlowList(
                    ControlFlowType.LAST, label,
                    code.sourceName, code.sourceLine
                );
                return pc;
            }

            case Opcodes.CREATE_NEXT: {
                int rd = bytecode[pc++];
                int labelIdx = bytecode[pc++];
                String label = labelIdx == 255 ? null : code.stringPool[labelIdx];
                registers[rd] = new RuntimeControlFlowList(
                    ControlFlowType.NEXT, label,
                    code.sourceName, code.sourceLine
                );
                return pc;
            }

            case Opcodes.CREATE_REDO: {
                int rd = bytecode[pc++];
                int labelIdx = bytecode[pc++];
                String label = labelIdx == 255 ? null : code.stringPool[labelIdx];
                registers[rd] = new RuntimeControlFlowList(
                    ControlFlowType.REDO, label,
                    code.sourceName, code.sourceLine
                );
                return pc;
            }

            case Opcodes.CREATE_GOTO: {
                int rd = bytecode[pc++];
                int labelIdx = bytecode[pc++];
                String label = labelIdx == 255 ? null : code.stringPool[labelIdx];
                registers[rd] = new RuntimeControlFlowList(
                    ControlFlowType.GOTO, label,
                    code.sourceName, code.sourceLine
                );
                return pc;
            }

            case Opcodes.IS_CONTROL_FLOW: {
                int rd = bytecode[pc++];
                int rs = bytecode[pc++];
                boolean isControlFlow = registers[rs] instanceof RuntimeControlFlowList;
                registers[rd] = isControlFlow ?
                    RuntimeScalarCache.scalarTrue : RuntimeScalarCache.scalarFalse;
                return pc;
            }

            case Opcodes.GET_CONTROL_FLOW_TYPE: {
                int rd = bytecode[pc++];
                int rs = bytecode[pc++];
                RuntimeControlFlowList cf = (RuntimeControlFlowList) registers[rs];
                registers[rd] = new RuntimeScalar(cf.marker.type.ordinal());
                return pc;
            }

            case Opcodes.CREATE_REF: {
                int rd = bytecode[pc++];
                int rs = bytecode[pc++];
                RuntimeBase value = registers[rs];
                registers[rd] = value.createReference();
                return pc;
            }

            case Opcodes.DEREF: {
                int rd = bytecode[pc++];
                int rs = bytecode[pc++];
                registers[rd] = registers[rs];
                return pc;
            }

            case Opcodes.GET_TYPE: {
                int rd = bytecode[pc++];
                int rs = bytecode[pc++];
                RuntimeScalar value = (RuntimeScalar) registers[rs];
                registers[rd] = new RuntimeScalar(value.type);
                return pc;
            }

            case Opcodes.DEFINED: {
                int rd = bytecode[pc++];
                int rs = bytecode[pc++];
                RuntimeBase val = registers[rs];
                boolean isDefined = val != null && val.getDefinedBoolean();
                registers[rd] = isDefined ?
                    RuntimeScalarCache.scalarTrue : RuntimeScalarCache.scalarFalse;
                return pc;
            }

            case Opcodes.REF: {
                int rd = bytecode[pc++];
                int rs = bytecode[pc++];
                RuntimeBase val = registers[rs];
                RuntimeScalar result;
                if (val instanceof RuntimeScalar) {
                    result = org.perlonjava.operators.ReferenceOperators.ref((RuntimeScalar) val);
                } else {
                    result = org.perlonjava.operators.ReferenceOperators.ref(val.scalar());
                }
                registers[rd] = result;
                return pc;
            }

            case Opcodes.BLESS: {
                int rd = bytecode[pc++];
                int refReg = bytecode[pc++];
                int packageReg = bytecode[pc++];
                RuntimeScalar ref = (RuntimeScalar) registers[refReg];
                RuntimeScalar packageName = (RuntimeScalar) registers[packageReg];
                registers[rd] = org.perlonjava.operators.ReferenceOperators.bless(ref, packageName);
                return pc;
            }

            case Opcodes.ISA: {
                int rd = bytecode[pc++];
                int objReg = bytecode[pc++];
                int packageReg = bytecode[pc++];
                RuntimeScalar obj = (RuntimeScalar) registers[objReg];
                RuntimeScalar packageName = (RuntimeScalar) registers[packageReg];
                RuntimeArray isaArgs = new RuntimeArray();
                isaArgs.push(obj);
                isaArgs.push(packageName);
                RuntimeList result = org.perlonjava.perlmodule.Universal.isa(isaArgs, RuntimeContextType.SCALAR);
                registers[rd] = result.scalar();
                return pc;
            }

            case Opcodes.PROTOTYPE: {
                int rd = bytecode[pc++];
                int rs = bytecode[pc++];
                int packageIdx = readInt(bytecode, pc);
                pc += 2;  // readInt reads 2 shorts
                RuntimeScalar codeRef = (RuntimeScalar) registers[rs];
                String packageName = code.stringPool[packageIdx];
                registers[rd] = RuntimeCode.prototype(codeRef, packageName);
                return pc;
            }

            case Opcodes.QUOTE_REGEX: {
                int rd = bytecode[pc++];
                int patternReg = bytecode[pc++];
                int flagsReg = bytecode[pc++];
                RuntimeScalar pattern = (RuntimeScalar) registers[patternReg];
                RuntimeScalar flags = (RuntimeScalar) registers[flagsReg];
                registers[rd] = org.perlonjava.regex.RuntimeRegex.getQuotedRegex(pattern, flags);
                return pc;
            }

            default:
                throw new RuntimeException("Unknown type opcode: " + opcode);
        }
    }

    /**
     * Handle array and hash operations (opcodes 43-49, 51-56, 93-96).
     * Separated to keep main execute() under JIT compilation limit.
     *
     * @return Updated program counter
     */
    private static int executeCollections(short opcode, short[] bytecode, int pc,
                                          RuntimeBase[] registers, InterpretedCode code) {
        switch (opcode) {
            case Opcodes.ARRAY_SET: {
                int arrayReg = bytecode[pc++];
                int indexReg = bytecode[pc++];
                int valueReg = bytecode[pc++];
                RuntimeArray arr = (RuntimeArray) registers[arrayReg];
                RuntimeScalar idx = (RuntimeScalar) registers[indexReg];
                RuntimeScalar val = (RuntimeScalar) registers[valueReg];
                arr.get(idx.getInt()).set(val);
                return pc;
            }

            case Opcodes.ARRAY_PUSH: {
                int arrayReg = bytecode[pc++];
                int valueReg = bytecode[pc++];
                RuntimeArray arr = (RuntimeArray) registers[arrayReg];
                RuntimeBase val = registers[valueReg];
                arr.push(val);
                return pc;
            }

            case Opcodes.ARRAY_POP: {
                int rd = bytecode[pc++];
                int arrayReg = bytecode[pc++];
                RuntimeArray arr = (RuntimeArray) registers[arrayReg];
                registers[rd] = RuntimeArray.pop(arr);
                return pc;
            }

            case Opcodes.ARRAY_SHIFT: {
                int rd = bytecode[pc++];
                int arrayReg = bytecode[pc++];
                RuntimeArray arr = (RuntimeArray) registers[arrayReg];
                registers[rd] = RuntimeArray.shift(arr);
                return pc;
            }

            case Opcodes.ARRAY_UNSHIFT: {
                int arrayReg = bytecode[pc++];
                int valueReg = bytecode[pc++];
                RuntimeArray arr = (RuntimeArray) registers[arrayReg];
                RuntimeBase val = registers[valueReg];
                RuntimeArray.unshift(arr, val);
                return pc;
            }

            case Opcodes.ARRAY_SIZE: {
                int rd = bytecode[pc++];
                int operandReg = bytecode[pc++];
                RuntimeBase operand = registers[operandReg];
                if (operand instanceof RuntimeList) {
                    registers[rd] = new RuntimeScalar(((RuntimeList) operand).size());
                } else {
                    registers[rd] = operand.scalar();
                }
                return pc;
            }

            case Opcodes.CREATE_ARRAY: {
                int rd = bytecode[pc++];
                int listReg = bytecode[pc++];
                RuntimeBase source = registers[listReg];
                RuntimeArray array;
                if (source instanceof RuntimeArray) {
                    array = (RuntimeArray) source;
                } else {
                    RuntimeList list = source.getList();
                    array = new RuntimeArray(list);
                }
                registers[rd] = array.createReference();
                return pc;
            }

            case Opcodes.HASH_SET: {
                int hashReg = bytecode[pc++];
                int keyReg = bytecode[pc++];
                int valueReg = bytecode[pc++];
                RuntimeHash hash = (RuntimeHash) registers[hashReg];
                RuntimeScalar key = (RuntimeScalar) registers[keyReg];
                RuntimeScalar val = (RuntimeScalar) registers[valueReg];
                hash.put(key.toString(), val);
                return pc;
            }

            case Opcodes.HASH_EXISTS: {
                int rd = bytecode[pc++];
                int hashReg = bytecode[pc++];
                int keyReg = bytecode[pc++];
                RuntimeHash hash = (RuntimeHash) registers[hashReg];
                RuntimeScalar key = (RuntimeScalar) registers[keyReg];
                registers[rd] = hash.exists(key);
                return pc;
            }

            case Opcodes.HASH_DELETE: {
                int rd = bytecode[pc++];
                int hashReg = bytecode[pc++];
                int keyReg = bytecode[pc++];
                RuntimeHash hash = (RuntimeHash) registers[hashReg];
                RuntimeScalar key = (RuntimeScalar) registers[keyReg];
                registers[rd] = hash.delete(key);
                return pc;
            }

            case Opcodes.HASH_KEYS: {
                int rd = bytecode[pc++];
                int hashReg = bytecode[pc++];
                RuntimeHash hash = (RuntimeHash) registers[hashReg];
                registers[rd] = hash.keys();
                return pc;
            }

            case Opcodes.HASH_VALUES: {
                int rd = bytecode[pc++];
                int hashReg = bytecode[pc++];
                RuntimeHash hash = (RuntimeHash) registers[hashReg];
                registers[rd] = hash.values();
                return pc;
            }

            case Opcodes.CREATE_HASH: {
                int rd = bytecode[pc++];
                int listReg = bytecode[pc++];
                RuntimeBase list = registers[listReg];
                RuntimeHash hash = RuntimeHash.createHash(list);
                registers[rd] = hash.createReference();
                return pc;
            }

            case Opcodes.NEW_ARRAY: {
                int rd = bytecode[pc++];
                registers[rd] = new RuntimeArray();
                return pc;
            }

            case Opcodes.NEW_HASH: {
                int rd = bytecode[pc++];
                registers[rd] = new RuntimeHash();
                return pc;
            }

            case Opcodes.ARRAY_SET_FROM_LIST: {
                int arrayReg = bytecode[pc++];
                int listReg = bytecode[pc++];
                RuntimeArray array = (RuntimeArray) registers[arrayReg];
                RuntimeBase listBase = registers[listReg];
                RuntimeList list = listBase.getList();
                array.setFromList(list);
                return pc;
            }

            case Opcodes.HASH_SET_FROM_LIST: {
                int hashReg = bytecode[pc++];
                int listReg = bytecode[pc++];
                RuntimeHash hash = (RuntimeHash) registers[hashReg];
                RuntimeBase listBase = registers[listReg];
                RuntimeList list = listBase.getList();
                hash.setFromList(list);
                return pc;
            }

            default:
                throw new RuntimeException("Unknown collection opcode: " + opcode);
        }
    }

    /**
     * Handle arithmetic and string operations (opcodes 19-30, 110-113).
     * Separated to keep main execute() under JIT compilation limit.
     *
     * @return Updated program counter
     */
    private static int executeArithmetic(short opcode, short[] bytecode, int pc,
                                         RuntimeBase[] registers) {
        switch (opcode) {
            case Opcodes.MUL_SCALAR: {
                int rd = bytecode[pc++];
                int rs1 = bytecode[pc++];
                int rs2 = bytecode[pc++];
                RuntimeBase val1 = registers[rs1];
                RuntimeBase val2 = registers[rs2];
                RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
                RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();
                registers[rd] = MathOperators.multiply(s1, s2);
                return pc;
            }

            case Opcodes.DIV_SCALAR: {
                int rd = bytecode[pc++];
                int rs1 = bytecode[pc++];
                int rs2 = bytecode[pc++];
                RuntimeBase val1 = registers[rs1];
                RuntimeBase val2 = registers[rs2];
                RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
                RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();
                registers[rd] = MathOperators.divide(s1, s2);
                return pc;
            }

            case Opcodes.MOD_SCALAR: {
                int rd = bytecode[pc++];
                int rs1 = bytecode[pc++];
                int rs2 = bytecode[pc++];
                RuntimeBase val1 = registers[rs1];
                RuntimeBase val2 = registers[rs2];
                RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
                RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();
                registers[rd] = MathOperators.modulus(s1, s2);
                return pc;
            }

            case Opcodes.POW_SCALAR: {
                int rd = bytecode[pc++];
                int rs1 = bytecode[pc++];
                int rs2 = bytecode[pc++];
                RuntimeBase val1 = registers[rs1];
                RuntimeBase val2 = registers[rs2];
                RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
                RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();
                registers[rd] = MathOperators.pow(s1, s2);
                return pc;
            }

            case Opcodes.NEG_SCALAR: {
                int rd = bytecode[pc++];
                int rs = bytecode[pc++];
                registers[rd] = MathOperators.unaryMinus((RuntimeScalar) registers[rs]);
                return pc;
            }

            case Opcodes.ADD_SCALAR_INT: {
                int rd = bytecode[pc++];
                int rs = bytecode[pc++];
                int immediate = readInt(bytecode, pc);
                pc += 2;
                registers[rd] = MathOperators.add(
                    (RuntimeScalar) registers[rs],
                    immediate
                );
                return pc;
            }

            case Opcodes.CONCAT: {
                int rd = bytecode[pc++];
                int rs1 = bytecode[pc++];
                int rs2 = bytecode[pc++];
                registers[rd] = StringOperators.stringConcat(
                    (RuntimeScalar) registers[rs1],
                    (RuntimeScalar) registers[rs2]
                );
                return pc;
            }

            case Opcodes.REPEAT: {
                int rd = bytecode[pc++];
                int rs1 = bytecode[pc++];
                int rs2 = bytecode[pc++];
                registers[rd] = Operator.repeat(
                    registers[rs1],
                    (RuntimeScalar) registers[rs2],
                    1  // scalar context
                );
                return pc;
            }

            case Opcodes.LENGTH: {
                int rd = bytecode[pc++];
                int rs = bytecode[pc++];
                registers[rd] = StringOperators.length((RuntimeScalar) registers[rs]);
                return pc;
            }

            case Opcodes.SUBTRACT_ASSIGN: {
                int rd = bytecode[pc++];
                int rs = bytecode[pc++];
                RuntimeBase val1 = registers[rd];
                RuntimeBase val2 = registers[rs];
                RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
                RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();
                registers[rd] = MathOperators.subtractAssign(s1, s2);
                return pc;
            }

            case Opcodes.MULTIPLY_ASSIGN: {
                int rd = bytecode[pc++];
                int rs = bytecode[pc++];
                RuntimeBase val1 = registers[rd];
                RuntimeBase val2 = registers[rs];
                RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
                RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();
                registers[rd] = MathOperators.multiplyAssign(s1, s2);
                return pc;
            }

            case Opcodes.DIVIDE_ASSIGN: {
                int rd = bytecode[pc++];
                int rs = bytecode[pc++];
                RuntimeBase val1 = registers[rd];
                RuntimeBase val2 = registers[rs];
                RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
                RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();
                registers[rd] = MathOperators.divideAssign(s1, s2);
                return pc;
            }

            case Opcodes.MODULUS_ASSIGN: {
                int rd = bytecode[pc++];
                int rs = bytecode[pc++];
                RuntimeBase val1 = registers[rd];
                RuntimeBase val2 = registers[rs];
                RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
                RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();
                registers[rd] = MathOperators.modulusAssign(s1, s2);
                return pc;
            }

            // Phase 3: Promoted OperatorHandler operations (400+)
            case Opcodes.OP_POW: {
                // Power: rd = rs1 ** rs2
                int rd = bytecode[pc++];
                int rs1 = bytecode[pc++];
                int rs2 = bytecode[pc++];
                RuntimeBase val1 = registers[rs1];
                RuntimeBase val2 = registers[rs2];
                RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
                RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();
                registers[rd] = MathOperators.pow(s1, s2);
                return pc;
            }

            case Opcodes.OP_ABS: {
                // Absolute value: rd = abs(rs)
                int rd = bytecode[pc++];
                int rs = bytecode[pc++];
                RuntimeBase val = registers[rs];
                RuntimeScalar s = (val instanceof RuntimeScalar) ? (RuntimeScalar) val : val.scalar();
                registers[rd] = MathOperators.abs(s);
                return pc;
            }

            case Opcodes.OP_INT: {
                // Integer conversion: rd = int(rs)
                int rd = bytecode[pc++];
                int rs = bytecode[pc++];
                RuntimeBase val = registers[rs];
                RuntimeScalar s = (val instanceof RuntimeScalar) ? (RuntimeScalar) val : val.scalar();
                registers[rd] = MathOperators.integer(s);
                return pc;
            }

            default:
                throw new RuntimeException("Unknown arithmetic opcode: " + opcode);
        }
    }

    /**
     * Handle comparison and logical operations (opcodes 31-41).
     * Separated to keep main execute() under JIT compilation limit.
     *
     * @return Updated program counter
     */
    private static int executeComparisons(short opcode, short[] bytecode, int pc,
                                          RuntimeBase[] registers) {
        switch (opcode) {
            case Opcodes.COMPARE_NUM: {
                // Numeric comparison: rd = rs1 <=> rs2
                int rd = bytecode[pc++];
                int rs1 = bytecode[pc++];
                int rs2 = bytecode[pc++];
                RuntimeBase val1 = registers[rs1];
                RuntimeBase val2 = registers[rs2];
                RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
                RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();
                registers[rd] = CompareOperators.spaceship(s1, s2);
                return pc;
            }

            case Opcodes.COMPARE_STR: {
                // String comparison: rd = rs1 cmp rs2
                int rd = bytecode[pc++];
                int rs1 = bytecode[pc++];
                int rs2 = bytecode[pc++];
                RuntimeBase val1 = registers[rs1];
                RuntimeBase val2 = registers[rs2];
                RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
                RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();
                registers[rd] = CompareOperators.cmp(s1, s2);
                return pc;
            }

            case Opcodes.EQ_NUM: {
                // Numeric equality: rd = (rs1 == rs2)
                int rd = bytecode[pc++];
                int rs1 = bytecode[pc++];
                int rs2 = bytecode[pc++];
                RuntimeBase val1 = registers[rs1];
                RuntimeBase val2 = registers[rs2];
                RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
                RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();
                registers[rd] = CompareOperators.equalTo(s1, s2);
                return pc;
            }

            case Opcodes.LT_NUM: {
                // Less than: rd = (rs1 < rs2)
                int rd = bytecode[pc++];
                int rs1 = bytecode[pc++];
                int rs2 = bytecode[pc++];
                RuntimeBase val1 = registers[rs1];
                RuntimeBase val2 = registers[rs2];
                RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
                RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();
                registers[rd] = CompareOperators.lessThan(s1, s2);
                return pc;
            }

            case Opcodes.GT_NUM: {
                // Greater than: rd = (rs1 > rs2)
                int rd = bytecode[pc++];
                int rs1 = bytecode[pc++];
                int rs2 = bytecode[pc++];
                RuntimeBase val1 = registers[rs1];
                RuntimeBase val2 = registers[rs2];
                RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
                RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();
                registers[rd] = CompareOperators.greaterThan(s1, s2);
                return pc;
            }

            case Opcodes.LE_NUM: {
                // Less than or equal: rd = (rs1 <= rs2)
                int rd = bytecode[pc++];
                int rs1 = bytecode[pc++];
                int rs2 = bytecode[pc++];
                RuntimeBase val1 = registers[rs1];
                RuntimeBase val2 = registers[rs2];
                RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
                RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();
                registers[rd] = CompareOperators.lessThanOrEqual(s1, s2);
                return pc;
            }

            case Opcodes.GE_NUM: {
                // Greater than or equal: rd = (rs1 >= rs2)
                int rd = bytecode[pc++];
                int rs1 = bytecode[pc++];
                int rs2 = bytecode[pc++];
                RuntimeBase val1 = registers[rs1];
                RuntimeBase val2 = registers[rs2];
                RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
                RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();
                registers[rd] = CompareOperators.greaterThanOrEqual(s1, s2);
                return pc;
            }

            case Opcodes.NE_NUM: {
                // Not equal: rd = (rs1 != rs2)
                int rd = bytecode[pc++];
                int rs1 = bytecode[pc++];
                int rs2 = bytecode[pc++];
                RuntimeBase val1 = registers[rs1];
                RuntimeBase val2 = registers[rs2];
                RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
                RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();
                registers[rd] = CompareOperators.notEqualTo(s1, s2);
                return pc;
            }

            case Opcodes.EQ_STR: {
                // String equality: rd = (rs1 eq rs2)
                int rd = bytecode[pc++];
                int rs1 = bytecode[pc++];
                int rs2 = bytecode[pc++];
                RuntimeBase val1 = registers[rs1];
                RuntimeBase val2 = registers[rs2];
                RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
                RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();
                RuntimeScalar cmpResult = CompareOperators.cmp(s1, s2);
                boolean isEqual = (cmpResult.getInt() == 0);
                registers[rd] = isEqual ? RuntimeScalarCache.scalarTrue : RuntimeScalarCache.scalarFalse;
                return pc;
            }

            case Opcodes.NE_STR: {
                // String inequality: rd = (rs1 ne rs2)
                int rd = bytecode[pc++];
                int rs1 = bytecode[pc++];
                int rs2 = bytecode[pc++];
                RuntimeBase val1 = registers[rs1];
                RuntimeBase val2 = registers[rs2];
                RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
                RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();
                RuntimeScalar cmpResult = CompareOperators.cmp(s1, s2);
                boolean isNotEqual = (cmpResult.getInt() != 0);
                registers[rd] = isNotEqual ? RuntimeScalarCache.scalarTrue : RuntimeScalarCache.scalarFalse;
                return pc;
            }

            case Opcodes.NOT: {
                // Logical NOT: rd = !rs
                int rd = bytecode[pc++];
                int rs = bytecode[pc++];
                RuntimeScalar val = (RuntimeScalar) registers[rs];
                registers[rd] = val.getBoolean() ?
                    RuntimeScalarCache.scalarFalse : RuntimeScalarCache.scalarTrue;
                return pc;
            }

            case Opcodes.AND: {
                // AND is short-circuit and handled in compiler typically
                // If we get here, just do boolean and
                int rd = bytecode[pc++];
                int rs1 = bytecode[pc++];
                int rs2 = bytecode[pc++];
                RuntimeScalar v1 = ((RuntimeBase) registers[rs1]).scalar();
                RuntimeScalar v2 = ((RuntimeBase) registers[rs2]).scalar();
                registers[rd] = (v1.getBoolean() && v2.getBoolean()) ?
                    RuntimeScalarCache.scalarTrue : RuntimeScalarCache.scalarFalse;
                return pc;
            }

            case Opcodes.OR: {
                // OR is short-circuit and handled in compiler typically
                // If we get here, just do boolean or
                int rd = bytecode[pc++];
                int rs1 = bytecode[pc++];
                int rs2 = bytecode[pc++];
                RuntimeScalar v1 = ((RuntimeBase) registers[rs1]).scalar();
                RuntimeScalar v2 = ((RuntimeBase) registers[rs2]).scalar();
                registers[rd] = (v1.getBoolean() || v2.getBoolean()) ?
                    RuntimeScalarCache.scalarTrue : RuntimeScalarCache.scalarFalse;
                return pc;
            }

            default:
                throw new RuntimeException("Unknown comparison opcode: " + opcode);
        }
    }

    /**
     * Execute slice operations (opcodes 114-121).
     * Handles: DEREF_ARRAY, DEREF_HASH, *_SLICE, *_SLICE_SET, *_SLICE_DELETE, LIST_SLICE_FROM
     * Direct dispatch to SlowOpcodeHandler methods (Phase 2 complete).
     */
    private static int executeSliceOps(short opcode, short[] bytecode, int pc,
                                        RuntimeBase[] registers, InterpretedCode code) {
        // Direct method calls - no SLOWOP_* constants needed!
        switch (opcode) {
            case Opcodes.DEREF_ARRAY:
                return SlowOpcodeHandler.executeDerefArray(bytecode, pc, registers);
            case Opcodes.DEREF_HASH:
                return SlowOpcodeHandler.executeDerefHash(bytecode, pc, registers);
            case Opcodes.ARRAY_SLICE:
                return SlowOpcodeHandler.executeArraySlice(bytecode, pc, registers);
            case Opcodes.ARRAY_SLICE_SET:
                return SlowOpcodeHandler.executeArraySliceSet(bytecode, pc, registers);
            case Opcodes.HASH_SLICE:
                return SlowOpcodeHandler.executeHashSlice(bytecode, pc, registers);
            case Opcodes.HASH_SLICE_SET:
                return SlowOpcodeHandler.executeHashSliceSet(bytecode, pc, registers);
            case Opcodes.HASH_SLICE_DELETE:
                return SlowOpcodeHandler.executeHashSliceDelete(bytecode, pc, registers);
            case Opcodes.LIST_SLICE_FROM:
                return SlowOpcodeHandler.executeListSliceFrom(bytecode, pc, registers);
            default:
                throw new RuntimeException("Unknown slice opcode: " + opcode);
        }
    }

    /**
     * Execute array/string operations (opcodes 122-127).
     * Handles: SPLICE, REVERSE, SPLIT, LENGTH_OP, EXISTS, DELETE
     */
    private static int executeArrayStringOps(short opcode, short[] bytecode, int pc,
                                              RuntimeBase[] registers, InterpretedCode code) {
        switch (opcode) {
            case Opcodes.SPLICE:
                return SlowOpcodeHandler.executeSplice(bytecode, pc, registers);
            case Opcodes.REVERSE:
                return SlowOpcodeHandler.executeReverse(bytecode, pc, registers);
            case Opcodes.SPLIT:
                return SlowOpcodeHandler.executeSplit(bytecode, pc, registers);
            case Opcodes.LENGTH_OP:
                return SlowOpcodeHandler.executeLength(bytecode, pc, registers);
            case Opcodes.EXISTS:
                return SlowOpcodeHandler.executeExists(bytecode, pc, registers);
            case Opcodes.DELETE:
                return SlowOpcodeHandler.executeDelete(bytecode, pc, registers);
            default:
                throw new RuntimeException("Unknown array/string opcode: " + opcode);
        }
    }

    /**
     * Execute closure/scope operations (opcodes 128-131).
     * Handles: RETRIEVE_BEGIN_*, LOCAL_SCALAR
     */
    private static int executeScopeOps(short opcode, short[] bytecode, int pc,
                                        RuntimeBase[] registers, InterpretedCode code) {
        switch (opcode) {
            case Opcodes.RETRIEVE_BEGIN_SCALAR:
                return SlowOpcodeHandler.executeRetrieveBeginScalar(bytecode, pc, registers, code);
            case Opcodes.RETRIEVE_BEGIN_ARRAY:
                return SlowOpcodeHandler.executeRetrieveBeginArray(bytecode, pc, registers, code);
            case Opcodes.RETRIEVE_BEGIN_HASH:
                return SlowOpcodeHandler.executeRetrieveBeginHash(bytecode, pc, registers, code);
            case Opcodes.LOCAL_SCALAR:
                return SlowOpcodeHandler.executeLocalScalar(bytecode, pc, registers, code);
            default:
                throw new RuntimeException("Unknown scope opcode: " + opcode);
        }
    }

    /**
     * Execute system call and IPC operations (opcodes 132-150).
     * Handles: CHOWN, WAITPID, FORK, GETPPID, *PGRP, *PRIORITY, *SOCKOPT,
     *          SYSCALL, SEMGET, SEMOP, MSGGET, MSGSND, MSGRCV, SHMGET, SHMREAD, SHMWRITE
     */
    private static int executeSystemOps(short opcode, short[] bytecode, int pc,
                                         RuntimeBase[] registers) {
        switch (opcode) {
            case Opcodes.CHOWN:
                return SlowOpcodeHandler.executeChown(bytecode, pc, registers);
            case Opcodes.WAITPID:
                return SlowOpcodeHandler.executeWaitpid(bytecode, pc, registers);
            case Opcodes.FORK:
                return SlowOpcodeHandler.executeFork(bytecode, pc, registers);
            case Opcodes.GETPPID:
                return SlowOpcodeHandler.executeGetppid(bytecode, pc, registers);
            case Opcodes.GETPGRP:
                return SlowOpcodeHandler.executeGetpgrp(bytecode, pc, registers);
            case Opcodes.SETPGRP:
                return SlowOpcodeHandler.executeSetpgrp(bytecode, pc, registers);
            case Opcodes.GETPRIORITY:
                return SlowOpcodeHandler.executeGetpriority(bytecode, pc, registers);
            case Opcodes.SETPRIORITY:
                return SlowOpcodeHandler.executeSetpriority(bytecode, pc, registers);
            case Opcodes.GETSOCKOPT:
                return SlowOpcodeHandler.executeGetsockopt(bytecode, pc, registers);
            case Opcodes.SETSOCKOPT:
                return SlowOpcodeHandler.executeSetsockopt(bytecode, pc, registers);
            case Opcodes.SYSCALL:
                return SlowOpcodeHandler.executeSyscall(bytecode, pc, registers);
            case Opcodes.SEMGET:
                return SlowOpcodeHandler.executeSemget(bytecode, pc, registers);
            case Opcodes.SEMOP:
                return SlowOpcodeHandler.executeSemop(bytecode, pc, registers);
            case Opcodes.MSGGET:
                return SlowOpcodeHandler.executeMsgget(bytecode, pc, registers);
            case Opcodes.MSGSND:
                return SlowOpcodeHandler.executeMsgsnd(bytecode, pc, registers);
            case Opcodes.MSGRCV:
                return SlowOpcodeHandler.executeMsgrcv(bytecode, pc, registers);
            case Opcodes.SHMGET:
                return SlowOpcodeHandler.executeShmget(bytecode, pc, registers);
            case Opcodes.SHMREAD:
                return SlowOpcodeHandler.executeShmread(bytecode, pc, registers);
            case Opcodes.SHMWRITE:
                return SlowOpcodeHandler.executeShmwrite(bytecode, pc, registers);
            default:
                throw new RuntimeException("Unknown system opcode: " + opcode);
        }
    }

    /**
     * Execute special I/O operations (opcodes 151-154).
     * Handles: EVAL_STRING, SELECT_OP, LOAD_GLOB, SLEEP_OP
     */
    private static int executeSpecialIO(short opcode, short[] bytecode, int pc,
                                         RuntimeBase[] registers, InterpretedCode code) {
        switch (opcode) {
            case Opcodes.EVAL_STRING:
                return SlowOpcodeHandler.executeEvalString(bytecode, pc, registers, code);
            case Opcodes.SELECT_OP:
                return SlowOpcodeHandler.executeSelect(bytecode, pc, registers);
            case Opcodes.LOAD_GLOB:
                return SlowOpcodeHandler.executeLoadGlob(bytecode, pc, registers, code);
            case Opcodes.SLEEP_OP:
                return SlowOpcodeHandler.executeSleep(bytecode, pc, registers);
            default:
                throw new RuntimeException("Unknown special I/O opcode: " + opcode);
        }
    }

    /**
     * Read a 32-bit integer from bytecode (stored as 2 shorts: high 16 bits, low 16 bits).
     * Uses unsigned short values to reconstruct the full 32-bit integer.
     */
    private static int readInt(short[] bytecode, int pc) {
        int high = bytecode[pc] & 0xFFFF;      // Keep mask here - need full 32-bit range
        int low = bytecode[pc + 1] & 0xFFFF;   // Keep mask here - need full 32-bit range
        return (high << 16) | low;
    }

    /**
     * Format an interpreter error with source location.
     * Shows the error location using the pc-to-tokenIndex mapping if available.
     */
    private static String formatInterpreterError(InterpretedCode code, int errorPc, Throwable e) {
        StringBuilder sb = new StringBuilder();

        // Try to get token index from pcToTokenIndex map
        // Use floorEntry to find the nearest token index before or at errorPc
        Integer tokenIndex = null;
        if (code.pcToTokenIndex != null && !code.pcToTokenIndex.isEmpty()) {
            var entry = code.pcToTokenIndex.floorEntry(errorPc);
            if (entry != null) {
                tokenIndex = entry.getValue();
            }
        }

        if (tokenIndex != null && code.errorUtil != null) {
            // We have token index and errorUtil - convert to line number
            int lineNumber = code.errorUtil.getLineNumber(tokenIndex);
            sb.append("Interpreter error in ").append(code.sourceName)
              .append(" line ").append(lineNumber)
              .append(" (pc=").append(errorPc).append("): ")
              .append(e.getMessage());
        } else if (tokenIndex != null) {
            // We have token index but no errorUtil
            sb.append("Interpreter error in ").append(code.sourceName)
              .append(" at token ").append(tokenIndex)
              .append(" (pc=").append(errorPc).append("): ")
              .append(e.getMessage());
        } else {
            // No token index available, use source line from code
            sb.append("Interpreter error in ").append(code.sourceName)
              .append(" line ").append(code.sourceLine)
              .append(" (pc=").append(errorPc).append("): ")
              .append(e.getMessage());
        }

        return sb.toString();
    }
}
