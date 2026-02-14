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
                        ((RuntimeScalar) registers[rd]).set((RuntimeScalar) registers[rs]);
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
                    // COMPARISON OPERATORS
                    // =================================================================

                    case Opcodes.COMPARE_NUM: {
                        // Numeric comparison: rd = rs1 <=> rs2
                        int rd = bytecode[pc++];
                        int rs1 = bytecode[pc++];
                        int rs2 = bytecode[pc++];

                        // Convert operands to scalar if needed
                        RuntimeBase val1 = registers[rs1];
                        RuntimeBase val2 = registers[rs2];
                        RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
                        RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();

                        registers[rd] = CompareOperators.spaceship(s1, s2);
                        break;
                    }

                    case Opcodes.COMPARE_STR: {
                        // String comparison: rd = rs1 cmp rs2
                        int rd = bytecode[pc++];
                        int rs1 = bytecode[pc++];
                        int rs2 = bytecode[pc++];

                        // Convert operands to scalar if needed
                        RuntimeBase val1 = registers[rs1];
                        RuntimeBase val2 = registers[rs2];
                        RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
                        RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();

                        registers[rd] = CompareOperators.cmp(s1, s2);
                        break;
                    }

                    case Opcodes.EQ_NUM: {
                        // Numeric equality: rd = (rs1 == rs2)
                        int rd = bytecode[pc++];
                        int rs1 = bytecode[pc++];
                        int rs2 = bytecode[pc++];

                        // Convert operands to scalar if needed
                        RuntimeBase val1 = registers[rs1];
                        RuntimeBase val2 = registers[rs2];
                        RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
                        RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();

                        registers[rd] = CompareOperators.equalTo(s1, s2);
                        break;
                    }

                    case Opcodes.LT_NUM: {
                        // Less than: rd = (rs1 < rs2)
                        int rd = bytecode[pc++];
                        int rs1 = bytecode[pc++];
                        int rs2 = bytecode[pc++];

                        // Convert operands to scalar if needed
                        RuntimeBase val1 = registers[rs1];
                        RuntimeBase val2 = registers[rs2];
                        RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
                        RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();

                        registers[rd] = CompareOperators.lessThan(s1, s2);
                        break;
                    }

                    case Opcodes.GT_NUM: {
                        // Greater than: rd = (rs1 > rs2)
                        int rd = bytecode[pc++];
                        int rs1 = bytecode[pc++];
                        int rs2 = bytecode[pc++];

                        // Convert operands to scalar if needed
                        RuntimeBase val1 = registers[rs1];
                        RuntimeBase val2 = registers[rs2];
                        RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
                        RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();

                        registers[rd] = CompareOperators.greaterThan(s1, s2);
                        break;
                    }

                    case Opcodes.NE_NUM: {
                        // Not equal: rd = (rs1 != rs2)
                        int rd = bytecode[pc++];
                        int rs1 = bytecode[pc++];
                        int rs2 = bytecode[pc++];

                        // Convert operands to scalar if needed
                        RuntimeBase val1 = registers[rs1];
                        RuntimeBase val2 = registers[rs2];
                        RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
                        RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();

                        registers[rd] = CompareOperators.notEqualTo(s1, s2);
                        break;
                    }

                    // =================================================================
                    // LOGICAL OPERATORS
                    // =================================================================

                    case Opcodes.NOT: {
                        // Logical NOT: rd = !rs
                        int rd = bytecode[pc++];
                        int rs = bytecode[pc++];
                        RuntimeScalar val = (RuntimeScalar) registers[rs];
                        registers[rd] = val.getBoolean() ?
                            RuntimeScalarCache.scalarFalse : RuntimeScalarCache.scalarTrue;
                        break;
                    }

                    case Opcodes.DEFINED: {
                        // Defined check: rd = defined(rs)
                        int rd = bytecode[pc++];
                        int rs = bytecode[pc++];
                        RuntimeBase val = registers[rs];
                        boolean isDefined = val != null && val.getDefinedBoolean();
                        registers[rd] = isDefined ?
                            RuntimeScalarCache.scalarTrue : RuntimeScalarCache.scalarFalse;
                        break;
                    }

                    case Opcodes.REF: {
                        // Ref check: rd = ref(rs) - returns blessed class name or type
                        int rd = bytecode[pc++];
                        int rs = bytecode[pc++];
                        RuntimeBase val = registers[rs];
                        RuntimeScalar result;
                        if (val instanceof RuntimeScalar) {
                            result = org.perlonjava.operators.ReferenceOperators.ref((RuntimeScalar) val);
                        } else {
                            // For non-scalar types, convert to scalar first
                            result = org.perlonjava.operators.ReferenceOperators.ref(val.scalar());
                        }
                        registers[rd] = result;
                        break;
                    }

                    case Opcodes.BLESS: {
                        // Bless: rd = bless(rs_ref, rs_package)
                        int rd = bytecode[pc++];
                        int refReg = bytecode[pc++];
                        int packageReg = bytecode[pc++];
                        RuntimeScalar ref = (RuntimeScalar) registers[refReg];
                        RuntimeScalar packageName = (RuntimeScalar) registers[packageReg];
                        registers[rd] = org.perlonjava.operators.ReferenceOperators.bless(ref, packageName);
                        break;
                    }

                    case Opcodes.ISA: {
                        // ISA: rd = isa(rs_obj, rs_package)
                        int rd = bytecode[pc++];
                        int objReg = bytecode[pc++];
                        int packageReg = bytecode[pc++];
                        RuntimeScalar obj = (RuntimeScalar) registers[objReg];
                        RuntimeScalar packageName = (RuntimeScalar) registers[packageReg];
                        // Create RuntimeArray with arguments
                        RuntimeArray isaArgs = new RuntimeArray();
                        isaArgs.push(obj);
                        isaArgs.push(packageName);
                        // Call Universal.isa
                        RuntimeList result = org.perlonjava.perlmodule.Universal.isa(isaArgs, RuntimeContextType.SCALAR);
                        registers[rd] = result.scalar();
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

                    case Opcodes.PRE_AUTOINCREMENT: {
                        // Pre-increment: ++rd
                        int rd = bytecode[pc++];
                        ((RuntimeScalar) registers[rd]).preAutoIncrement();
                        break;
                    }

                    case Opcodes.POST_AUTOINCREMENT: {
                        // Post-increment: rd++
                        int rd = bytecode[pc++];
                        ((RuntimeScalar) registers[rd]).postAutoIncrement();
                        break;
                    }

                    case Opcodes.PRE_AUTODECREMENT: {
                        // Pre-decrement: --rd
                        int rd = bytecode[pc++];
                        ((RuntimeScalar) registers[rd]).preAutoDecrement();
                        break;
                    }

                    case Opcodes.POST_AUTODECREMENT: {
                        // Post-decrement: rd--
                        int rd = bytecode[pc++];
                        ((RuntimeScalar) registers[rd]).postAutoDecrement();
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
                        // Warn with message: warn(rs)
                        int warnRs = bytecode[pc++];
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
                        // Format: [EVAL_TRY] [catch_offset_high] [catch_offset_low]

                        int catchOffsetHigh = bytecode[pc++];
                        int catchOffsetLow = bytecode[pc++];
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
