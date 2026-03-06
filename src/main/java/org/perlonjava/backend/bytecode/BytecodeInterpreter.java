package org.perlonjava.backend.bytecode;

import org.perlonjava.runtime.operators.CompareOperators;
import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.regex.RuntimeRegex;
import org.perlonjava.runtime.runtimetypes.*;

/**
 * Bytecode interpreter with switch-based dispatch and pure register architecture.
 * <p>
 * Key design principles:
 * 1. Pure register machine (NO expression stack) - required for control flow correctness
 * 2. 3-address code format: rd = rs1 op rs2 (explicit register operands)
 * 3. Call same org.perlonjava.runtime.operators.* methods as compiler (100% code reuse)
 * 4. Share GlobalVariable maps with compiled code (same global state)
 * 5. Handle RuntimeControlFlowList for last/next/redo/goto/tail-call
 * 6. Switch-based dispatch (JVM optimizes to tableswitch - O(1) jump table)
 */
public class BytecodeInterpreter {

    // Debug flag for regex compilation (set at class load time)
    private static final boolean DEBUG_REGEX = System.getenv("DEBUG_REGEX") != null;

    static RuntimeScalar ensureMutableScalar(RuntimeBase val) {
        if (val instanceof RuntimeScalarReadOnly ro) {
            RuntimeScalar copy = new RuntimeScalar();
            copy.type = ro.type;
            copy.value = ro.value;
            return copy;
        }
        if (val instanceof ScalarSpecialVariable sv) {
            RuntimeScalar src = sv.getValueAsScalar();
            RuntimeScalar copy = new RuntimeScalar();
            copy.type = src.type;
            copy.value = src.value;
            return copy;
        }
        return (RuntimeScalar) val;
    }

    static boolean isImmutableProxy(RuntimeBase val) {
        return val instanceof RuntimeScalarReadOnly || val instanceof ScalarSpecialVariable;
    }

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
        final int[] bytecode = code.bytecode;

        // Eval block exception handling: stack of catch PCs
        // When EVAL_TRY is executed, push the catch PC onto this stack
        // When exception occurs, pop from stack and jump to catch PC
        java.util.Stack<Integer> evalCatchStack = new java.util.Stack<>();

        // Labeled block stack for non-local last/next/redo handling.
        // When a function call returns a RuntimeControlFlowList, we check this stack
        // to see if the label matches an enclosing labeled block.
        java.util.Stack<int[]> labeledBlockStack = new java.util.Stack<>();
        // Each entry is [labelStringPoolIdx, exitPc]

        java.util.Stack<RegexState> regexStateStack = new java.util.Stack<>();

        // Record DVM level so the finally block can clean up everything pushed
        // by this subroutine (local variables AND regex state snapshot).
        int savedLocalLevel = DynamicVariableManager.getLocalLevel();
        String savedPackage = InterpreterState.currentPackage.get().toString();
        InterpreterState.currentPackage.get().set(framePackageName);
        RegexState.save();
        // Structure: try { while(true) { try { ...dispatch... } catch { handle eval/die } } } finally { cleanup }
        //
        // Outer try/finally — cleanup only, no catch.
        //   Restores local variables, package name, and call stack on ANY exit (return, throw, etc.)
        //
        // Inner try/catch — implements Perl's eval { BLOCK } / die semantics.
        //   When Perl code calls `die` inside `eval { ... }`, the catch block sets $@ and
        //   uses `continue outer` to jump back to the top of the while(true) loop, resuming
        //   the bytecode dispatch at the eval's catch target PC. Without the while(true),
        //   `continue` would have nowhere to go after the catch block.
        try {
            outer:
            while (true) {
                try {
                    // Main dispatch loop - JVM JIT optimizes switch to tableswitch (O(1) jump)
                    while (pc < bytecode.length) {
                        // Update current PC for caller()/stack trace reporting.
                        // This allows ExceptionFormatter to map pc->tokenIndex->line using code.errorUtil,
                        // which also honors #line directives inside eval strings.
                        InterpreterState.setCurrentPc(pc);
                        int opcode = bytecode[pc++];

                        switch (opcode) {
                            // =================================================================
                            // CONTROL FLOW
                            // =================================================================

                            case Opcodes.NOP -> {
                                // No operation
                            }

                            case Opcodes.RETURN -> {
                                // Return from subroutine: return rd
                                int retReg = bytecode[pc++];
                                RuntimeBase retVal = registers[retReg];

                                if (retVal == null) {
                                    return new RuntimeList();
                                }
                                RuntimeList retList = retVal.getList();
                                RuntimeCode.materializeSpecialVarsInResult(retList);
                                return retList;
                            }

                            case Opcodes.GOTO -> {
                                // Unconditional jump: pc = offset
                                int offset = readInt(bytecode, pc);
                                pc = offset;  // Registers persist across jump (unlike stack-based!)
                            }

                            case Opcodes.LAST, Opcodes.NEXT, Opcodes.REDO -> {
                                // Loop control: jump to target PC
                                // Format: opcode, target (absolute PC as int)
                                int target = readInt(bytecode, pc);
                                pc = target;
                            }

                            case Opcodes.GOTO_IF_FALSE -> {
                                // Conditional jump: if (!rs) pc = offset
                                int condReg = bytecode[pc++];
                                int target = readInt(bytecode, pc);
                                pc += 1;

                                // Convert to scalar if needed for boolean test
                                RuntimeBase condBase = registers[condReg];
                                RuntimeScalar cond = (condBase instanceof RuntimeScalar)
                                        ? (RuntimeScalar) condBase
                                        : condBase.scalar();

                                if (!cond.getBoolean()) {
                                    pc = target;  // Jump - all registers stay valid!
                                }
                            }

                            case Opcodes.GOTO_IF_TRUE -> {
                                // Conditional jump: if (rs) pc = offset
                                int condReg = bytecode[pc++];
                                int target = readInt(bytecode, pc);
                                pc += 1;

                                // Convert to scalar if needed for boolean test
                                RuntimeBase condBase = registers[condReg];
                                RuntimeScalar cond = (condBase instanceof RuntimeScalar)
                                        ? (RuntimeScalar) condBase
                                        : condBase.scalar();

                                if (cond.getBoolean()) {
                                    pc = target;
                                }
                            }

                            // =================================================================
                            // REGISTER OPERATIONS
                            // =================================================================

                            case Opcodes.ALIAS -> {
                                // Register alias: rd = rs (shares reference, does NOT copy value)
                                // Must unwrap RuntimeScalarReadOnly to prevent read-only values in variable registers
                                int dest = bytecode[pc++];
                                int src = bytecode[pc++];
                                RuntimeBase srcVal = registers[src];
                                registers[dest] = isImmutableProxy(srcVal) ? ensureMutableScalar(srcVal) : srcVal;
                            }

                            case Opcodes.LOAD_CONST -> {
                                // Load from constant pool: rd = constants[index]
                                int rd = bytecode[pc++];
                                int constIndex = bytecode[pc++];
                                registers[rd] = (RuntimeBase) code.constants[constIndex];
                            }

                            case Opcodes.LOAD_INT -> {
                                // Load integer: rd = immediate (create NEW mutable scalar, not cached)
                                int rd = bytecode[pc++];
                                int value = readInt(bytecode, pc);
                                pc += 1;
                                // Create NEW RuntimeScalar (mutable) instead of using cache
                                // This is needed for local variables that may be modified (++/--)
                                registers[rd] = new RuntimeScalar(value);
                            }

                            case Opcodes.LOAD_STRING -> {
                                int rd = bytecode[pc++];
                                int strIndex = bytecode[pc++];
                                registers[rd] = new RuntimeScalar(code.stringPool[strIndex]);
                            }

                            case Opcodes.LOAD_BYTE_STRING -> {
                                int rd = bytecode[pc++];
                                int strIndex = bytecode[pc++];
                                RuntimeScalar bs = new RuntimeScalar(code.stringPool[strIndex]);
                                bs.type = RuntimeScalarType.BYTE_STRING;
                                registers[rd] = bs;
                            }

                            case Opcodes.LOAD_VSTRING -> {
                                int rd = bytecode[pc++];
                                int strIndex = bytecode[pc++];
                                RuntimeScalar vs = new RuntimeScalar(code.stringPool[strIndex]);
                                vs.type = RuntimeScalarType.VSTRING;
                                registers[rd] = vs;
                            }

                            case Opcodes.GLOB_OP -> {
                                pc = InlineOpcodeHandler.executeGlobOp(bytecode, pc, registers);
                            }

                            case Opcodes.LOAD_UNDEF -> {
                                // Load undef: rd = new RuntimeScalar()
                                int rd = bytecode[pc++];
                                registers[rd] = new RuntimeScalar();
                            }

                            case Opcodes.UNDEFINE_SCALAR -> {
                                pc = InlineOpcodeHandler.executeUndefineScalar(bytecode, pc, registers);
                            }

                            case Opcodes.MY_SCALAR -> {
                                // Lexical scalar assignment: rd = new RuntimeScalar(); rd.set(rs)
                                int rd = bytecode[pc++];
                                int rs = bytecode[pc++];
                                RuntimeScalar newScalar = new RuntimeScalar();
                                registers[rs].addToScalar(newScalar);
                                registers[rd] = newScalar;
                            }

                            // =================================================================
                            // VARIABLE ACCESS - GLOBAL
                            // =================================================================

                            case Opcodes.LOAD_GLOBAL_SCALAR -> {
                                // Load global scalar: rd = GlobalVariable.getGlobalVariable(name)
                                int rd = bytecode[pc++];
                                int nameIdx = bytecode[pc++];
                                String name = code.stringPool[nameIdx];
                                // Uses SAME GlobalVariable as compiled code
                                registers[rd] = GlobalVariable.getGlobalVariable(name);
                            }

                            case Opcodes.STORE_GLOBAL_SCALAR -> {
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
                            }

                            case Opcodes.LOCAL_SCALAR_SAVE_LEVEL -> {
                                // Superinstruction: save dynamic level BEFORE makeLocal, then localize.
                                // Atomically: levelReg = getLocalLevel(), rd = makeLocal(name).
                                // The pre-push level in levelReg is used by POP_LOCAL_LEVEL after the loop.
                                int rd = bytecode[pc++];
                                int levelReg = bytecode[pc++];
                                int nameIdx = bytecode[pc++];
                                String name = code.stringPool[nameIdx];

                                registers[levelReg] = new RuntimeScalar(DynamicVariableManager.getLocalLevel());
                                registers[rd] = GlobalRuntimeScalar.makeLocal(name);
                            }

                            case Opcodes.POP_LOCAL_LEVEL -> {
                                // Restore DynamicVariableManager to a previously saved local level.
                                // Matches JVM compiler's DynamicVariableManager.popToLocalLevel(savedLevel) call.
                                int rs = bytecode[pc++];
                                int savedLevel = ((RuntimeScalar) registers[rs]).getInt();
                                DynamicVariableManager.popToLocalLevel(savedLevel);
                            }

                            case Opcodes.SAVE_REGEX_STATE -> {
                                pc++;
                                regexStateStack.push(new RegexState());
                            }

                            case Opcodes.RESTORE_REGEX_STATE -> {
                                pc++;
                                if (!regexStateStack.isEmpty()) {
                                    regexStateStack.pop().restore();
                                }
                            }

                            case Opcodes.FOREACH_GLOBAL_NEXT_OR_EXIT -> {
                                // Superinstruction: foreach loop step for a global loop variable (e.g. $_).
                                // Combines: hasNext check, next() into varReg, aliasGlobalVariable, conditional jump.
                                // Do-while layout: if hasNext jump to bodyTarget, else fall through to exit.
                                int rd = bytecode[pc++];
                                int iterReg = bytecode[pc++];
                                int nameIdx = bytecode[pc++];
                                int bodyTarget = readInt(bytecode, pc);
                                pc += 1;

                                String name = code.stringPool[nameIdx];
                                RuntimeScalar iterScalar = (RuntimeScalar) registers[iterReg];
                                @SuppressWarnings("unchecked")
                                java.util.Iterator<RuntimeScalar> iterator =
                                        (java.util.Iterator<RuntimeScalar>) iterScalar.value;

                                if (iterator.hasNext()) {
                                    RuntimeScalar element = iterator.next();
                                    if (isImmutableProxy(element)) {
                                        element = ensureMutableScalar(element);
                                    }
                                    registers[rd] = element;
                                    GlobalVariable.aliasGlobalVariable(name, element);
                                    pc = bodyTarget;  // ABSOLUTE jump back to body start
                                } else {
                                    registers[rd] = new RuntimeScalar();
                                }
                            }

                            case Opcodes.STORE_GLOBAL_ARRAY -> {
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
                                            " is null when storing to @" + name + " at pc=" + (pc - 3) + "\n\nDisassembly:\n" + disasm);
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
                            }

                            case Opcodes.STORE_GLOBAL_HASH -> {
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
                            }

                            case Opcodes.LOAD_GLOBAL_ARRAY -> {
                                // Load global array: rd = GlobalVariable.getGlobalArray(name)
                                int rd = bytecode[pc++];
                                int nameIdx = bytecode[pc++];
                                String name = code.stringPool[nameIdx];
                                registers[rd] = GlobalVariable.getGlobalArray(name);
                            }

                            case Opcodes.LOAD_GLOBAL_HASH -> {
                                // Load global hash: rd = GlobalVariable.getGlobalHash(name)
                                int rd = bytecode[pc++];
                                int nameIdx = bytecode[pc++];
                                String name = code.stringPool[nameIdx];
                                registers[rd] = GlobalVariable.getGlobalHash(name);
                            }

                            case Opcodes.LOAD_GLOBAL_CODE -> {
                                // Load global code: rd = GlobalVariable.getGlobalCodeRef(name)
                                int rd = bytecode[pc++];
                                int nameIdx = bytecode[pc++];
                                String name = code.stringPool[nameIdx];
                                if (name.equals("__SUB__")) {
                                    // __SUB__ returns the current subroutine being executed
                                    registers[rd] = RuntimeCode.selfReferenceMaybeNull(code.__SUB__);
                                } else {
                                    registers[rd] = GlobalVariable.getGlobalCodeRef(name);
                                }
                            }

                            case Opcodes.STORE_GLOBAL_CODE -> {
                                // Store global code: GlobalVariable.globalCodeRefs.put(name, codeRef)
                                int nameIdx = bytecode[pc++];
                                int codeReg = bytecode[pc++];
                                String name = code.stringPool[nameIdx];
                                RuntimeScalar codeRef = (RuntimeScalar) registers[codeReg];
                                // Store the code reference in the global namespace
                                GlobalVariable.globalCodeRefs.put(name, codeRef);
                            }

                            case Opcodes.CREATE_CLOSURE -> {
                                // Create closure with captured variables
                                // Format: CREATE_CLOSURE rd template_idx num_captures reg1 reg2 ...
                                pc = OpcodeHandlerExtended.executeCreateClosure(bytecode, pc, registers, code);
                            }

                            case Opcodes.SET_SCALAR -> {
                                // Set scalar value: registers[rd] = registers[rs]
                                // Use addToScalar which properly handles special variables like $&
                                // addToScalar calls getValueAsScalar() for ScalarSpecialVariable
                                int rd = bytecode[pc++];
                                int rs = bytecode[pc++];
                                RuntimeBase rdVal = registers[rd];
                                RuntimeScalar rdScalar;
                                if (isImmutableProxy(rdVal)) {
                                    rdScalar = new RuntimeScalar();
                                    registers[rd] = rdScalar;
                                } else if (rdVal instanceof RuntimeScalar) {
                                    rdScalar = (RuntimeScalar) rdVal;
                                } else {
                                    rdScalar = rdVal.scalar();
                                }
                                registers[rs].addToScalar(rdScalar);
                            }

                            // =================================================================
                            // ARITHMETIC OPERATORS
                            // =================================================================

                            case Opcodes.ADD_SCALAR -> {
                                pc = InlineOpcodeHandler.executeAddScalar(bytecode, pc, registers);
                            }

                            case Opcodes.SUB_SCALAR -> {
                                pc = InlineOpcodeHandler.executeSubScalar(bytecode, pc, registers);
                            }

                            case Opcodes.MUL_SCALAR -> {
                                pc = InlineOpcodeHandler.executeMulScalar(bytecode, pc, registers);
                            }

                            case Opcodes.DIV_SCALAR -> {
                                pc = InlineOpcodeHandler.executeDivScalar(bytecode, pc, registers);
                            }

                            case Opcodes.MOD_SCALAR -> {
                                pc = InlineOpcodeHandler.executeModScalar(bytecode, pc, registers);
                            }

                            case Opcodes.POW_SCALAR -> {
                                pc = InlineOpcodeHandler.executePowScalar(bytecode, pc, registers);
                            }

                            case Opcodes.NEG_SCALAR -> {
                                pc = InlineOpcodeHandler.executeNegScalar(bytecode, pc, registers);
                            }

                            // Specialized unboxed operations (rare optimizations)
                            case Opcodes.ADD_SCALAR_INT -> {
                                pc = InlineOpcodeHandler.executeAddScalarInt(bytecode, pc, registers);
                            }

                            // =================================================================
                            // STRING OPERATORS
                            // =================================================================

                            case Opcodes.CONCAT -> {
                                pc = InlineOpcodeHandler.executeConcat(bytecode, pc, registers);
                            }

                            case Opcodes.REPEAT -> {
                                pc = InlineOpcodeHandler.executeRepeat(bytecode, pc, registers);
                            }

                            case Opcodes.LENGTH -> {
                                pc = InlineOpcodeHandler.executeLength(bytecode, pc, registers);
                            }

                            // =================================================================
                            // COMPARISON AND LOGICAL OPERATORS (opcodes 31-39) - Delegated
                            // =================================================================

                            case Opcodes.COMPARE_NUM, Opcodes.COMPARE_STR, Opcodes.EQ_NUM, Opcodes.NE_NUM,
                                 Opcodes.LT_NUM, Opcodes.GT_NUM, Opcodes.LE_NUM, Opcodes.GE_NUM, Opcodes.EQ_STR,
                                 Opcodes.NE_STR, Opcodes.NOT -> {
                                pc = executeComparisons(opcode, bytecode, pc, registers);
                            }

                            // =================================================================
                            // TYPE AND REFERENCE OPERATORS (opcodes 102-105) - Delegated
                            // =================================================================

                            case Opcodes.DEFINED, Opcodes.REF, Opcodes.BLESS, Opcodes.ISA, Opcodes.PROTOTYPE,
                                 Opcodes.QUOTE_REGEX -> {
                                pc = executeTypeOps(opcode, bytecode, pc, registers, code);
                            }

                            // =================================================================
                            // ITERATOR OPERATIONS - For efficient foreach loops
                            // =================================================================

                            case Opcodes.ITERATOR_CREATE -> {
                                // Create iterator: rd = rs.iterator()
                                // Format: ITERATOR_CREATE rd rs
                                pc = OpcodeHandlerExtended.executeIteratorCreate(bytecode, pc, registers);
                            }

                            case Opcodes.ITERATOR_HAS_NEXT -> {
                                // Check iterator: rd = iterator.hasNext()
                                // Format: ITERATOR_HAS_NEXT rd iterReg
                                pc = OpcodeHandlerExtended.executeIteratorHasNext(bytecode, pc, registers);
                            }

                            case Opcodes.ITERATOR_NEXT -> {
                                // Get next element: rd = iterator.next()
                                // Format: ITERATOR_NEXT rd iterReg
                                pc = OpcodeHandlerExtended.executeIteratorNext(bytecode, pc, registers);
                            }

                            case Opcodes.FOREACH_NEXT_OR_EXIT -> {
                                // Superinstruction for foreach loops (do-while layout).
                                // Combines: hasNext check, next() call, and conditional jump to body.
                                // Format: FOREACH_NEXT_OR_EXIT rd, iterReg, bodyTarget
                                // If hasNext: rd = iterator.next(), jump to bodyTarget (backward)
                                // Else: fall through to exit (iterator exhausted)
                                int rd = bytecode[pc++];
                                int iterReg = bytecode[pc++];
                                int bodyTarget = readInt(bytecode, pc);  // Absolute target address
                                pc += 1;  // Skip the int we just read

                                RuntimeScalar iterScalar = (RuntimeScalar) registers[iterReg];
                                @SuppressWarnings("unchecked")
                                java.util.Iterator<RuntimeScalar> iterator =
                                        (java.util.Iterator<RuntimeScalar>) iterScalar.value;

                                if (iterator.hasNext()) {
                                    // Get next element and jump back to body
                                    RuntimeScalar elem = iterator.next();
                                    registers[rd] = (isImmutableProxy(elem)) ? ensureMutableScalar(elem) : elem;
                                    pc = bodyTarget;  // ABSOLUTE jump back to body start
                                } else {
                                    registers[rd] = new RuntimeScalar();
                                }
                            }

                            // =================================================================
                            // COMPOUND ASSIGNMENT OPERATORS (with overload support)
                            // =================================================================

                            case Opcodes.SUBTRACT_ASSIGN -> {
                                // Compound assignment: rd -= rs
                                // Format: SUBTRACT_ASSIGN rd rs
                                pc = OpcodeHandlerExtended.executeSubtractAssign(bytecode, pc, registers);
                            }

                            case Opcodes.MULTIPLY_ASSIGN -> {
                                // Compound assignment: rd *= rs
                                // Format: MULTIPLY_ASSIGN rd rs
                                pc = OpcodeHandlerExtended.executeMultiplyAssign(bytecode, pc, registers);
                            }

                            case Opcodes.DIVIDE_ASSIGN -> {
                                // Compound assignment: rd /= rs
                                // Format: DIVIDE_ASSIGN rd rs
                                pc = OpcodeHandlerExtended.executeDivideAssign(bytecode, pc, registers);
                            }

                            case Opcodes.MODULUS_ASSIGN -> {
                                // Compound assignment: rd %= rs
                                // Format: MODULUS_ASSIGN rd rs
                                pc = OpcodeHandlerExtended.executeModulusAssign(bytecode, pc, registers);
                            }

                            case Opcodes.REPEAT_ASSIGN -> {
                                // Compound assignment: rd x= rs
                                // Format: REPEAT_ASSIGN rd rs
                                pc = OpcodeHandlerExtended.executeRepeatAssign(bytecode, pc, registers);
                            }

                            case Opcodes.POW_ASSIGN -> {
                                // Compound assignment: rd **= rs
                                // Format: POW_ASSIGN rd rs
                                pc = OpcodeHandlerExtended.executePowAssign(bytecode, pc, registers);
                            }

                            case Opcodes.LEFT_SHIFT_ASSIGN -> {
                                // Compound assignment: rd <<= rs
                                // Format: LEFT_SHIFT_ASSIGN rd rs
                                pc = OpcodeHandlerExtended.executeLeftShiftAssign(bytecode, pc, registers);
                            }

                            case Opcodes.RIGHT_SHIFT_ASSIGN -> {
                                pc = OpcodeHandlerExtended.executeRightShiftAssign(bytecode, pc, registers);
                            }

                            case Opcodes.INTEGER_LEFT_SHIFT_ASSIGN -> {
                                pc = InlineOpcodeHandler.executeIntegerLeftShiftAssign(bytecode, pc, registers);
                            }
                            case Opcodes.INTEGER_RIGHT_SHIFT_ASSIGN -> {
                                pc = InlineOpcodeHandler.executeIntegerRightShiftAssign(bytecode, pc, registers);
                            }
                            case Opcodes.INTEGER_DIV_ASSIGN -> {
                                pc = InlineOpcodeHandler.executeIntegerDivAssign(bytecode, pc, registers);
                            }
                            case Opcodes.INTEGER_MOD_ASSIGN -> {
                                pc = InlineOpcodeHandler.executeIntegerModAssign(bytecode, pc, registers);
                            }

                            case Opcodes.LOGICAL_AND_ASSIGN -> {
                                // Compound assignment: rd &&= rs (short-circuit)
                                // Format: LOGICAL_AND_ASSIGN rd rs
                                pc = OpcodeHandlerExtended.executeLogicalAndAssign(bytecode, pc, registers);
                            }

                            case Opcodes.LOGICAL_OR_ASSIGN -> {
                                // Compound assignment: rd ||= rs (short-circuit)
                                // Format: LOGICAL_OR_ASSIGN rd rs
                                pc = OpcodeHandlerExtended.executeLogicalOrAssign(bytecode, pc, registers);
                            }

                            case Opcodes.DEFINED_OR_ASSIGN -> {
                                // Compound assignment: rd //= rs (short-circuit)
                                // Format: DEFINED_OR_ASSIGN rd rs
                                pc = OpcodeHandlerExtended.executeDefinedOrAssign(bytecode, pc, registers);
                            }

                            // =================================================================
                            // SHIFT OPERATIONS
                            // =================================================================

                            case Opcodes.LEFT_SHIFT -> {
                                pc = InlineOpcodeHandler.executeLeftShift(bytecode, pc, registers);
                            }

                            case Opcodes.RIGHT_SHIFT -> {
                                pc = InlineOpcodeHandler.executeRightShift(bytecode, pc, registers);
                            }

                            case Opcodes.INTEGER_LEFT_SHIFT -> {
                                pc = InlineOpcodeHandler.executeIntegerLeftShift(bytecode, pc, registers);
                            }

                            case Opcodes.INTEGER_RIGHT_SHIFT -> {
                                pc = InlineOpcodeHandler.executeIntegerRightShift(bytecode, pc, registers);
                            }

                            case Opcodes.INTEGER_DIV -> {
                                pc = InlineOpcodeHandler.executeIntegerDiv(bytecode, pc, registers);
                            }

                            case Opcodes.INTEGER_MOD -> {
                                pc = InlineOpcodeHandler.executeIntegerMod(bytecode, pc, registers);
                            }

                            // =================================================================
                            // ARRAY OPERATIONS
                            // =================================================================

                            case Opcodes.ARRAY_GET -> {
                                pc = InlineOpcodeHandler.executeArrayGet(bytecode, pc, registers);
                            }

                            case Opcodes.ARRAY_SET -> {
                                pc = InlineOpcodeHandler.executeArraySet(bytecode, pc, registers);
                            }

                            case Opcodes.ARRAY_PUSH -> {
                                pc = InlineOpcodeHandler.executeArrayPush(bytecode, pc, registers);
                            }

                            case Opcodes.ARRAY_POP -> {
                                pc = InlineOpcodeHandler.executeArrayPop(bytecode, pc, registers);
                            }

                            case Opcodes.ARRAY_SHIFT -> {
                                pc = InlineOpcodeHandler.executeArrayShift(bytecode, pc, registers);
                            }

                            case Opcodes.ARRAY_UNSHIFT -> {
                                pc = InlineOpcodeHandler.executeArrayUnshift(bytecode, pc, registers);
                            }

                            case Opcodes.ARRAY_SIZE -> {
                                pc = InlineOpcodeHandler.executeArraySize(bytecode, pc, registers);
                            }

                            case Opcodes.SET_ARRAY_LAST_INDEX -> {
                                pc = InlineOpcodeHandler.executeSetArrayLastIndex(bytecode, pc, registers);
                            }

                            case Opcodes.CREATE_ARRAY -> {
                                pc = InlineOpcodeHandler.executeCreateArray(bytecode, pc, registers);
                            }

                            // =================================================================
                            // HASH OPERATIONS
                            // =================================================================

                            case Opcodes.HASH_GET -> {
                                pc = InlineOpcodeHandler.executeHashGet(bytecode, pc, registers);
                            }

                            case Opcodes.HASH_SET -> {
                                pc = InlineOpcodeHandler.executeHashSet(bytecode, pc, registers);
                            }

                            case Opcodes.HASH_EXISTS -> {
                                pc = InlineOpcodeHandler.executeHashExists(bytecode, pc, registers);
                            }

                            case Opcodes.HASH_DELETE -> {
                                pc = InlineOpcodeHandler.executeHashDelete(bytecode, pc, registers);
                            }

                            case Opcodes.ARRAY_EXISTS -> {
                                pc = InlineOpcodeHandler.executeArrayExists(bytecode, pc, registers);
                            }

                            case Opcodes.ARRAY_DELETE -> {
                                pc = InlineOpcodeHandler.executeArrayDelete(bytecode, pc, registers);
                            }

                            case Opcodes.HASH_KEYS -> {
                                pc = InlineOpcodeHandler.executeHashKeys(bytecode, pc, registers);
                            }

                            case Opcodes.HASH_VALUES -> {
                                pc = InlineOpcodeHandler.executeHashValues(bytecode, pc, registers);
                            }

                            // =================================================================
                            // SUBROUTINE CALLS
                            // =================================================================

                            case Opcodes.CALL_SUB -> {
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

                                RuntimeArray callArgs;
                                if (argsBase instanceof RuntimeArray) {
                                    callArgs = (RuntimeArray) argsBase;
                                } else if (argsBase instanceof RuntimeList) {
                                    callArgs = new RuntimeArray();
                                    argsBase.setArrayOfAlias(callArgs);
                                } else {
                                    callArgs = new RuntimeArray((RuntimeScalar) argsBase);
                                }

                                RuntimeList result = RuntimeCode.apply(codeRef, "", callArgs, context);

                                // Convert to scalar if called in scalar context
                                if (context == RuntimeContextType.SCALAR) {
                                    RuntimeBase scalarResult = result.scalar();
                                    registers[rd] = (isImmutableProxy(scalarResult)) ? ensureMutableScalar(scalarResult) : scalarResult;
                                } else {
                                    registers[rd] = result;
                                }

                                // Check for control flow (last/next/redo/goto/tail-call)
                                if (result.isNonLocalGoto()) {
                                    RuntimeControlFlowList flow = (RuntimeControlFlowList) result;
                                    // Check labeled block stack for a matching label
                                    boolean handled = false;
                                    for (int i = labeledBlockStack.size() - 1; i >= 0; i--) {
                                        int[] entry = labeledBlockStack.get(i);
                                        String blockLabel = code.stringPool[entry[0]];
                                        if (flow.matchesLabel(blockLabel)) {
                                            // Pop entries down to and including the match
                                            while (labeledBlockStack.size() > i) {
                                                labeledBlockStack.pop();
                                            }
                                            pc = entry[1]; // jump to block exit
                                            handled = true;
                                            break;
                                        }
                                    }
                                    if (!handled) {
                                        return result;
                                    }
                                }
                            }

                            case Opcodes.CALL_METHOD -> {
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

                                RuntimeArray callArgs;
                                if (argsBase instanceof RuntimeArray) {
                                    callArgs = (RuntimeArray) argsBase;
                                } else if (argsBase instanceof RuntimeList) {
                                    callArgs = new RuntimeArray();
                                    argsBase.setArrayOfAlias(callArgs);
                                } else {
                                    callArgs = new RuntimeArray((RuntimeScalar) argsBase);
                                }

                                RuntimeList result = RuntimeCode.call(invocant, method, currentSub, callArgs, context);

                                // Convert to scalar if called in scalar context
                                if (context == RuntimeContextType.SCALAR) {
                                    RuntimeBase scalarResult = result.scalar();
                                    registers[rd] = (isImmutableProxy(scalarResult)) ? ensureMutableScalar(scalarResult) : scalarResult;
                                } else {
                                    registers[rd] = result;
                                }

                                // Check for control flow (last/next/redo/goto/tail-call)
                                if (result.isNonLocalGoto()) {
                                    RuntimeControlFlowList flow = (RuntimeControlFlowList) result;
                                    boolean handled = false;
                                    for (int i = labeledBlockStack.size() - 1; i >= 0; i--) {
                                        int[] entry = labeledBlockStack.get(i);
                                        String blockLabel = code.stringPool[entry[0]];
                                        if (flow.matchesLabel(blockLabel)) {
                                            while (labeledBlockStack.size() > i) {
                                                labeledBlockStack.pop();
                                            }
                                            pc = entry[1];
                                            handled = true;
                                            break;
                                        }
                                    }
                                    if (!handled) {
                                        return result;
                                    }
                                }
                            }

                            // =================================================================
                            // CONTROL FLOW - SPECIAL (RuntimeControlFlowList)
                            // =================================================================

                            case Opcodes.CREATE_LAST -> {
                                pc = InlineOpcodeHandler.executeCreateLast(bytecode, pc, registers, code);
                            }

                            case Opcodes.CREATE_NEXT -> {
                                pc = InlineOpcodeHandler.executeCreateNext(bytecode, pc, registers, code);
                            }

                            case Opcodes.CREATE_REDO -> {
                                pc = InlineOpcodeHandler.executeCreateRedo(bytecode, pc, registers, code);
                            }

                            case Opcodes.CREATE_GOTO -> {
                                pc = InlineOpcodeHandler.executeCreateGoto(bytecode, pc, registers, code);
                            }

                            case Opcodes.IS_CONTROL_FLOW -> {
                                pc = InlineOpcodeHandler.executeIsControlFlow(bytecode, pc, registers);
                            }

                            // =================================================================
                            // MISCELLANEOUS
                            // =================================================================

                            case Opcodes.PRINT -> {
                                // Print to filehandle
                                // Format: PRINT contentReg filehandleReg
                                pc = OpcodeHandlerExtended.executePrint(bytecode, pc, registers);
                            }

                            case Opcodes.SAY -> {
                                // Say to filehandle
                                // Format: SAY contentReg filehandleReg
                                pc = OpcodeHandlerExtended.executeSay(bytecode, pc, registers);
                            }

                            // =================================================================
                            // SUPERINSTRUCTIONS - Eliminate ALIAS overhead
                            // =================================================================

                            case Opcodes.INC_REG -> {
                                pc = InlineOpcodeHandler.executeIncReg(bytecode, pc, registers);
                            }

                            case Opcodes.DEC_REG -> {
                                pc = InlineOpcodeHandler.executeDecReg(bytecode, pc, registers);
                            }

                            case Opcodes.ADD_ASSIGN -> {
                                pc = InlineOpcodeHandler.executeAddAssign(bytecode, pc, registers);
                            }

                            case Opcodes.ADD_ASSIGN_INT -> {
                                pc = InlineOpcodeHandler.executeAddAssignInt(bytecode, pc, registers);
                            }

                            case Opcodes.STRING_CONCAT_ASSIGN -> {
                                // String concatenation and assign: rd .= rs
                                // Format: STRING_CONCAT_ASSIGN rd rs
                                pc = OpcodeHandlerExtended.executeStringConcatAssign(bytecode, pc, registers);
                            }

                            case Opcodes.BITWISE_AND_ASSIGN -> {
                                // Bitwise AND assignment: rd &= rs
                                // Format: BITWISE_AND_ASSIGN rd rs
                                pc = OpcodeHandlerExtended.executeBitwiseAndAssign(bytecode, pc, registers);
                            }

                            case Opcodes.BITWISE_OR_ASSIGN -> {
                                // Bitwise OR assignment: rd |= rs
                                // Format: BITWISE_OR_ASSIGN rd rs
                                pc = OpcodeHandlerExtended.executeBitwiseOrAssign(bytecode, pc, registers);
                            }

                            case Opcodes.BITWISE_XOR_ASSIGN -> {
                                // Bitwise XOR assignment: rd ^= rs
                                // Format: BITWISE_XOR_ASSIGN rd rs
                                pc = OpcodeHandlerExtended.executeBitwiseXorAssign(bytecode, pc, registers);
                            }

                            case Opcodes.STRING_BITWISE_AND_ASSIGN -> {
                                // String bitwise AND assignment: rd &.= rs
                                // Format: STRING_BITWISE_AND_ASSIGN rd rs
                                pc = OpcodeHandlerExtended.executeStringBitwiseAndAssign(bytecode, pc, registers);
                            }

                            case Opcodes.STRING_BITWISE_OR_ASSIGN -> {
                                // String bitwise OR assignment: rd |.= rs
                                // Format: STRING_BITWISE_OR_ASSIGN rd rs
                                pc = OpcodeHandlerExtended.executeStringBitwiseOrAssign(bytecode, pc, registers);
                            }

                            case Opcodes.STRING_BITWISE_XOR_ASSIGN -> {
                                // String bitwise XOR assignment: rd ^.= rs
                                // Format: STRING_BITWISE_XOR_ASSIGN rd rs
                                pc = OpcodeHandlerExtended.executeStringBitwiseXorAssign(bytecode, pc, registers);
                            }

                            case Opcodes.BITWISE_AND_BINARY -> {
                                // Numeric bitwise AND: rd = rs1 binary& rs2
                                // Format: BITWISE_AND_BINARY rd rs1 rs2
                                pc = OpcodeHandlerExtended.executeBitwiseAndBinary(bytecode, pc, registers);
                            }

                            case Opcodes.BITWISE_OR_BINARY -> {
                                // Numeric bitwise OR: rd = rs1 binary| rs2
                                // Format: BITWISE_OR_BINARY rd rs1 rs2
                                pc = OpcodeHandlerExtended.executeBitwiseOrBinary(bytecode, pc, registers);
                            }

                            case Opcodes.BITWISE_XOR_BINARY -> {
                                // Numeric bitwise XOR: rd = rs1 binary^ rs2
                                // Format: BITWISE_XOR_BINARY rd rs1 rs2
                                pc = OpcodeHandlerExtended.executeBitwiseXorBinary(bytecode, pc, registers);
                            }

                            case Opcodes.STRING_BITWISE_AND -> {
                                // String bitwise AND: rd = rs1 &. rs2
                                // Format: STRING_BITWISE_AND rd rs1 rs2
                                pc = OpcodeHandlerExtended.executeStringBitwiseAnd(bytecode, pc, registers);
                            }

                            case Opcodes.STRING_BITWISE_OR -> {
                                // String bitwise OR: rd = rs1 |. rs2
                                // Format: STRING_BITWISE_OR rd rs1 rs2
                                pc = OpcodeHandlerExtended.executeStringBitwiseOr(bytecode, pc, registers);
                            }

                            case Opcodes.STRING_BITWISE_XOR -> {
                                // String bitwise XOR: rd = rs1 ^. rs2
                                // Format: STRING_BITWISE_XOR rd rs1 rs2
                                pc = OpcodeHandlerExtended.executeStringBitwiseXor(bytecode, pc, registers);
                            }

                            case Opcodes.XOR_LOGICAL -> {
                                pc = InlineOpcodeHandler.executeXorLogical(bytecode, pc, registers);
                            }

                            case Opcodes.BITWISE_NOT_BINARY -> {
                                // Numeric bitwise NOT: rd = binary~ rs
                                // Format: BITWISE_NOT_BINARY rd rs
                                pc = OpcodeHandlerExtended.executeBitwiseNotBinary(bytecode, pc, registers);
                            }

                            case Opcodes.BITWISE_NOT_STRING -> {
                                // String bitwise NOT: rd = ~. rs
                                // Format: BITWISE_NOT_STRING rd rs
                                pc = OpcodeHandlerExtended.executeBitwiseNotString(bytecode, pc, registers);
                            }

                            // File test and stat operations
                            case Opcodes.STAT -> {
                                pc = OpcodeHandlerExtended.executeStat(bytecode, pc, registers);
                            }

                            case Opcodes.LSTAT -> {
                                pc = OpcodeHandlerExtended.executeLstat(bytecode, pc, registers);
                            }

                            case Opcodes.STAT_LASTHANDLE -> {
                                pc = OpcodeHandlerExtended.executeStatLastHandle(bytecode, pc, registers);
                            }

                            case Opcodes.LSTAT_LASTHANDLE -> {
                                pc = OpcodeHandlerExtended.executeLstatLastHandle(bytecode, pc, registers);
                            }

                            // File test operations (opcodes 190-216) - delegated to handler
                            case Opcodes.FILETEST_R, Opcodes.FILETEST_W, Opcodes.FILETEST_X, Opcodes.FILETEST_O,
                                 Opcodes.FILETEST_R_REAL, Opcodes.FILETEST_W_REAL, Opcodes.FILETEST_X_REAL,
                                 Opcodes.FILETEST_O_REAL, Opcodes.FILETEST_E, Opcodes.FILETEST_Z, Opcodes.FILETEST_S,
                                 Opcodes.FILETEST_F, Opcodes.FILETEST_D, Opcodes.FILETEST_L, Opcodes.FILETEST_P,
                                 Opcodes.FILETEST_S_UPPER, Opcodes.FILETEST_B, Opcodes.FILETEST_C, Opcodes.FILETEST_T,
                                 Opcodes.FILETEST_U, Opcodes.FILETEST_G, Opcodes.FILETEST_K, Opcodes.FILETEST_T_UPPER,
                                 Opcodes.FILETEST_B_UPPER, Opcodes.FILETEST_M, Opcodes.FILETEST_A,
                                 Opcodes.FILETEST_C_UPPER -> {
                                pc = OpcodeHandlerFileTest.executeFileTest(bytecode, pc, registers, opcode);
                            }

                            case Opcodes.PUSH_LOCAL_VARIABLE -> {
                                pc = InlineOpcodeHandler.executePushLocalVariable(bytecode, pc, registers);
                            }

                            case Opcodes.STORE_GLOB -> {
                                pc = InlineOpcodeHandler.executeStoreGlob(bytecode, pc, registers);
                            }

                            case Opcodes.OPEN -> {
                                // Open file: rd = IOOperator.open(ctx, args...)
                                // Format: OPEN rd ctx argsReg
                                pc = OpcodeHandlerExtended.executeOpen(bytecode, pc, registers);
                            }

                            case Opcodes.READLINE -> {
                                // Read line from filehandle
                                // Format: READLINE rd fhReg ctx
                                pc = OpcodeHandlerExtended.executeReadline(bytecode, pc, registers);
                            }

                            case Opcodes.MATCH_REGEX -> {
                                // Match regex
                                // Format: MATCH_REGEX rd stringReg regexReg ctx
                                pc = OpcodeHandlerExtended.executeMatchRegex(bytecode, pc, registers);
                            }

                            case Opcodes.MATCH_REGEX_NOT -> {
                                // Negated regex match
                                // Format: MATCH_REGEX_NOT rd stringReg regexReg ctx
                                pc = OpcodeHandlerExtended.executeMatchRegexNot(bytecode, pc, registers);
                            }

                            case Opcodes.CHOMP -> {
                                // Chomp: rd = rs.chomp()
                                // Format: CHOMP rd rs
                                pc = OpcodeHandlerExtended.executeChomp(bytecode, pc, registers);
                            }

                            case Opcodes.WANTARRAY -> {
                                // Get wantarray context
                                // Format: WANTARRAY rd wantarrayReg
                                pc = OpcodeHandlerExtended.executeWantarray(bytecode, pc, registers);
                            }

                            case Opcodes.REQUIRE -> {
                                // Require module or version
                                // Format: REQUIRE rd rs
                                pc = OpcodeHandlerExtended.executeRequire(bytecode, pc, registers);
                            }

                            case Opcodes.POS -> {
                                // Get regex position
                                // Format: POS rd rs
                                pc = OpcodeHandlerExtended.executePos(bytecode, pc, registers);
                            }

                            case Opcodes.INDEX -> {
                                // Find substring position
                                // Format: INDEX rd strReg substrReg posReg
                                pc = OpcodeHandlerExtended.executeIndex(bytecode, pc, registers);
                            }

                            case Opcodes.RINDEX -> {
                                // Find substring position from end
                                // Format: RINDEX rd strReg substrReg posReg
                                pc = OpcodeHandlerExtended.executeRindex(bytecode, pc, registers);
                            }

                            case Opcodes.PRE_AUTOINCREMENT -> {
                                // Pre-increment: ++rd
                                // Format: PRE_AUTOINCREMENT rd
                                pc = OpcodeHandlerExtended.executePreAutoIncrement(bytecode, pc, registers);
                            }

                            case Opcodes.POST_AUTOINCREMENT -> {
                                // Post-increment: rd = rs++
                                // Format: POST_AUTOINCREMENT rd rs
                                pc = OpcodeHandlerExtended.executePostAutoIncrement(bytecode, pc, registers);
                            }

                            case Opcodes.PRE_AUTODECREMENT -> {
                                // Pre-decrement: --rd
                                // Format: PRE_AUTODECREMENT rd
                                pc = OpcodeHandlerExtended.executePreAutoDecrement(bytecode, pc, registers);
                            }

                            case Opcodes.POST_AUTODECREMENT -> {
                                // Post-decrement: rd = rs--
                                // Format: POST_AUTODECREMENT rd rs
                                pc = OpcodeHandlerExtended.executePostAutoDecrement(bytecode, pc, registers);
                            }

                            // =================================================================
                            // ERROR HANDLING
                            // =================================================================

                            case Opcodes.DIE -> {
                                pc = InlineOpcodeHandler.executeDie(bytecode, pc, registers, code);
                            }

                            case Opcodes.WARN -> {
                                pc = InlineOpcodeHandler.executeWarn(bytecode, pc, registers, code);
                            }

                            // =================================================================
                            // REFERENCE OPERATIONS
                            // =================================================================

                            case Opcodes.CREATE_REF -> {
                                pc = InlineOpcodeHandler.executeCreateRef(bytecode, pc, registers);
                            }

                            case Opcodes.DEREF -> {
                                pc = InlineOpcodeHandler.executeDeref(bytecode, pc, registers);
                            }

                            case Opcodes.GET_TYPE -> {
                                pc = InlineOpcodeHandler.executeGetType(bytecode, pc, registers);
                            }

                            // =================================================================
                            // EVAL BLOCK SUPPORT
                            // =================================================================

                            case Opcodes.EVAL_TRY -> {
                                // Start of eval block with exception handling
                                // Format: [EVAL_TRY] [catch_target_high] [catch_target_low]
                                // catch_target is absolute bytecode address (4 bytes)

                                int catchPc = readInt(bytecode, pc);  // Read 4-byte absolute address
                                pc += 1;  // Skip the 2 shorts we just read

                                // Push catch PC onto eval stack
                                evalCatchStack.push(catchPc);

                                // Clear $@ at start of eval block
                                GlobalVariable.setGlobalVariable("main::@", "");

                                // Continue execution - if exception occurs, outer catch handler
                                // will check evalCatchStack and jump to catchPc
                            }

                            case Opcodes.EVAL_END -> {
                                // End of successful eval block - clear $@ and pop catch stack
                                GlobalVariable.setGlobalVariable("main::@", "");

                                // Pop the catch PC from eval stack (we didn't need it)
                                if (!evalCatchStack.isEmpty()) {
                                    evalCatchStack.pop();
                                }
                            }

                            case Opcodes.EVAL_CATCH -> {
                                // Exception handler for eval block
                                // Format: [EVAL_CATCH] [rd]
                                // This is only reached when an exception is caught

                                int rd = bytecode[pc++];

                                // WarnDie.catchEval() should have already been called to set $@
                                // Just store undef as the eval result
                                registers[rd] = RuntimeScalarCache.scalarUndef;
                            }

                            // =================================================================
                            // LABELED BLOCK SUPPORT
                            // =================================================================

                            case Opcodes.PUSH_LABELED_BLOCK -> {
                                int labelIdx = bytecode[pc++];
                                int exitPc = readInt(bytecode, pc);
                                pc += 1;
                                labeledBlockStack.push(new int[]{labelIdx, exitPc});
                            }

                            case Opcodes.POP_LABELED_BLOCK -> {
                                if (!labeledBlockStack.isEmpty()) {
                                    labeledBlockStack.pop();
                                }
                            }

                            // =================================================================
                            // LIST OPERATIONS
                            // =================================================================

                            case Opcodes.LIST_TO_COUNT -> {
                                pc = InlineOpcodeHandler.executeListToCount(bytecode, pc, registers);
                            }

                            case Opcodes.LIST_TO_SCALAR -> {
                                pc = InlineOpcodeHandler.executeListToScalar(bytecode, pc, registers);
                            }

                            case Opcodes.SCALAR_TO_LIST -> {
                                pc = InlineOpcodeHandler.executeScalarToList(bytecode, pc, registers);
                            }

                            case Opcodes.CREATE_LIST -> {
                                pc = InlineOpcodeHandler.executeCreateList(bytecode, pc, registers);
                            }

                            // =================================================================
                            // STRING OPERATIONS
                            // =================================================================

                            case Opcodes.JOIN -> {
                                pc = InlineOpcodeHandler.executeJoin(bytecode, pc, registers);
                            }

                            case Opcodes.SELECT -> {
                                pc = InlineOpcodeHandler.executeSelect(bytecode, pc, registers);
                            }

                            case Opcodes.RANGE -> {
                                pc = InlineOpcodeHandler.executeRange(bytecode, pc, registers);
                            }

                            case Opcodes.CREATE_HASH -> {
                                pc = InlineOpcodeHandler.executeCreateHash(bytecode, pc, registers);
                            }

                            case Opcodes.RAND -> {
                                pc = InlineOpcodeHandler.executeRand(bytecode, pc, registers);
                            }

                            case Opcodes.MAP -> {
                                pc = InlineOpcodeHandler.executeMap(bytecode, pc, registers);
                            }

                            case Opcodes.GREP -> {
                                pc = InlineOpcodeHandler.executeGrep(bytecode, pc, registers);
                            }

                            case Opcodes.SORT -> {
                                pc = InlineOpcodeHandler.executeSort(bytecode, pc, registers, code);
                            }

                            case Opcodes.NEW_ARRAY -> {
                                pc = InlineOpcodeHandler.executeNewArray(bytecode, pc, registers);
                            }

                            case Opcodes.NEW_HASH -> {
                                pc = InlineOpcodeHandler.executeNewHash(bytecode, pc, registers);
                            }

                            case Opcodes.ARRAY_SET_FROM_LIST -> {
                                pc = InlineOpcodeHandler.executeArraySetFromList(bytecode, pc, registers);
                            }

                            case Opcodes.SET_FROM_LIST -> {
                                pc = InlineOpcodeHandler.executeSetFromList(bytecode, pc, registers);
                            }

                            case Opcodes.HASH_SET_FROM_LIST -> {
                                pc = InlineOpcodeHandler.executeHashSetFromList(bytecode, pc, registers);
                            }

                            // =================================================================
                            // PHASE 2: DIRECT OPCODES (114-154) - Range delegation
                            // =================================================================
                            // These operations were promoted from SLOW_OP for better performance.
                            // Organized in CONTIGUOUS groups for JVM tableswitch optimization.

                            // Group 1-2: Dereferencing and Slicing (114-121)
                            case Opcodes.DEREF_ARRAY, Opcodes.DEREF_HASH, Opcodes.DEREF_HASH_NONSTRICT,
                                 Opcodes.DEREF_ARRAY_NONSTRICT, Opcodes.ARRAY_SLICE, Opcodes.ARRAY_SLICE_SET,
                                 Opcodes.HASH_SLICE, Opcodes.HASH_SLICE_SET, Opcodes.HASH_SLICE_DELETE,
                                 Opcodes.HASH_KEYVALUE_SLICE, Opcodes.LIST_SLICE_FROM -> {
                                pc = executeSliceOps(opcode, bytecode, pc, registers, code);
                            }

                            // Group 3-4: Array/String/Exists/Delete (122-127)
                            case Opcodes.SPLICE, Opcodes.REVERSE, Opcodes.SPLIT, Opcodes.LENGTH_OP, Opcodes.EXISTS,
                                 Opcodes.DELETE -> {
                                pc = executeArrayStringOps(opcode, bytecode, pc, registers, code);
                            }

                            // Group 5: Closure/Scope (128-131)
                            case Opcodes.RETRIEVE_BEGIN_SCALAR, Opcodes.RETRIEVE_BEGIN_ARRAY,
                                 Opcodes.RETRIEVE_BEGIN_HASH, Opcodes.LOCAL_SCALAR, Opcodes.LOCAL_ARRAY,
                                 Opcodes.LOCAL_HASH -> {
                                pc = executeScopeOps(opcode, bytecode, pc, registers, code);
                            }

                            // Group 6-8: System Calls and IPC (132-150)
                            case Opcodes.CHOWN, Opcodes.WAITPID, Opcodes.FORK, Opcodes.GETPPID, Opcodes.GETPGRP,
                                 Opcodes.SETPGRP, Opcodes.GETPRIORITY, Opcodes.SETPRIORITY, Opcodes.GETSOCKOPT,
                                 Opcodes.SETSOCKOPT, Opcodes.SYSCALL, Opcodes.SEMGET, Opcodes.SEMOP, Opcodes.MSGGET,
                                 Opcodes.MSGSND, Opcodes.MSGRCV, Opcodes.SHMGET, Opcodes.SHMREAD, Opcodes.SHMWRITE -> {
                                pc = executeSystemOps(opcode, bytecode, pc, registers);
                            }

                            // Group 9: Special I/O (151-154), glob ops, strict deref
                            case Opcodes.TIME_OP -> {
                                int rd = bytecode[pc++];
                                registers[rd] = org.perlonjava.runtime.operators.Time.time();
                            }
                            case Opcodes.EVAL_STRING, Opcodes.SELECT_OP, Opcodes.LOAD_GLOB, Opcodes.SLEEP_OP,
                                 Opcodes.ALARM_OP, Opcodes.DEREF_GLOB, Opcodes.DEREF_GLOB_NONSTRICT,
                                 Opcodes.LOAD_GLOB_DYNAMIC, Opcodes.DEREF_SCALAR_STRICT,
                                 Opcodes.DEREF_SCALAR_NONSTRICT -> {
                                pc = executeSpecialIO(opcode, bytecode, pc, registers, code);
                            }

                            // =================================================================
                            // SLOW OPERATIONS (DEPRECATED)
                            // =================================================================

                            // DEPRECATED: SLOW_OP removed - all operations now use direct opcodes (114-154)

                            // =================================================================
                            // GENERATED BUILT-IN FUNCTION HANDLERS
                            // =================================================================
                            // Generated by dev/tools/generate_opcode_handlers.pl
                            // DO NOT EDIT MANUALLY - regenerate using the tool

                            // GENERATED_HANDLERS_START

                            // scalar_binary
                            case Opcodes.ATAN2, Opcodes.BINARY_AND, Opcodes.BINARY_OR, Opcodes.BINARY_XOR, Opcodes.EQ,
                                 Opcodes.NE, Opcodes.LT, Opcodes.LE, Opcodes.GT, Opcodes.GE, Opcodes.CMP, Opcodes.X -> {
                                pc = ScalarBinaryOpcodeHandler.execute(opcode, bytecode, pc, registers);
                            }

                            // scalar_unary
                            case Opcodes.INT, Opcodes.LOG, Opcodes.SQRT, Opcodes.COS, Opcodes.SIN, Opcodes.EXP,
                                 Opcodes.ABS, Opcodes.BINARY_NOT, Opcodes.INTEGER_BITWISE_NOT, Opcodes.ORD,
                                 Opcodes.ORD_BYTES, Opcodes.OCT, Opcodes.HEX, Opcodes.SRAND, Opcodes.CHR,
                                 Opcodes.CHR_BYTES, Opcodes.LENGTH_BYTES, Opcodes.QUOTEMETA, Opcodes.FC, Opcodes.LC,
                                 Opcodes.LCFIRST, Opcodes.UC, Opcodes.UCFIRST, Opcodes.SLEEP, Opcodes.TELL,
                                 Opcodes.RMDIR, Opcodes.CLOSEDIR, Opcodes.REWINDDIR, Opcodes.TELLDIR, Opcodes.CHDIR,
                                 Opcodes.EXIT -> {
                                pc = ScalarUnaryOpcodeHandler.execute(opcode, bytecode, pc, registers);
                            }
                            // GENERATED_HANDLERS_END

                            case Opcodes.TR_TRANSLITERATE -> {
                                pc = SlowOpcodeHandler.executeTransliterate(bytecode, pc, registers);
                            }

                            case Opcodes.STORE_SYMBOLIC_SCALAR -> {
                                pc = InlineOpcodeHandler.executeStoreSymbolicScalar(bytecode, pc, registers);
                            }

                            case Opcodes.LOAD_SYMBOLIC_SCALAR -> {
                                pc = InlineOpcodeHandler.executeLoadSymbolicScalar(bytecode, pc, registers);
                            }

                            case Opcodes.FILETEST_LASTHANDLE -> {
                                // File test on cached handle '_': rd = FileTestOperator.fileTestLastHandle(operator)
                                // Format: FILETEST_LASTHANDLE rd operator_string_idx
                                pc = SlowOpcodeHandler.executeFiletestLastHandle(bytecode, pc, registers, code);
                            }

                            case Opcodes.GLOB_SLOT_GET -> {
                                // Glob slot access: rd = glob.hashDerefGetNonStrict(key, pkg)
                                // Format: GLOB_SLOT_GET rd globReg keyReg
                                pc = SlowOpcodeHandler.executeGlobSlotGet(bytecode, pc, registers);
                            }

                            case Opcodes.SPRINTF -> {
                                // sprintf($format, @args): rd = SprintfOperator.sprintf(formatReg, argsListReg)
                                // Format: SPRINTF rd formatReg argsListReg
                                pc = OpcodeHandlerExtended.executeSprintf(bytecode, pc, registers);
                            }

                            case Opcodes.CHOP -> {
                                // chop($x): rd = StringOperators.chopScalar(scalarReg)
                                // Format: CHOP rd scalarReg
                                pc = OpcodeHandlerExtended.executeChop(bytecode, pc, registers);
                            }

                            case Opcodes.GET_REPLACEMENT_REGEX -> {
                                // Get replacement regex: rd = RuntimeRegex.getReplacementRegex(pattern, replacement, flags)
                                // Format: GET_REPLACEMENT_REGEX rd pattern_reg replacement_reg flags_reg
                                pc = OpcodeHandlerExtended.executeGetReplacementRegex(bytecode, pc, registers);
                            }

                            case Opcodes.SUBSTR_VAR -> {
                                // substr with variable args: rd = Operator.substr(ctx, args...)
                                // Format: SUBSTR_VAR rd argsListReg ctx
                                pc = OpcodeHandlerExtended.executeSubstrVar(bytecode, pc, registers);
                            }

                            case Opcodes.TIE -> {
                                pc = InlineOpcodeHandler.executeTie(bytecode, pc, registers);
                            }

                            case Opcodes.UNTIE -> {
                                pc = InlineOpcodeHandler.executeUntie(bytecode, pc, registers);
                            }

                            case Opcodes.TIED -> {
                                pc = InlineOpcodeHandler.executeTied(bytecode, pc, registers);
                            }

                            // Miscellaneous operators with context-sensitive signatures
                            case Opcodes.CHMOD, Opcodes.UNLINK, Opcodes.UTIME, Opcodes.RENAME, Opcodes.LINK,
                                 Opcodes.READLINK, Opcodes.UMASK, Opcodes.GETC, Opcodes.FILENO, Opcodes.QX,
                                 Opcodes.SYSTEM, Opcodes.CALLER, Opcodes.EACH, Opcodes.PACK, Opcodes.UNPACK,
                                 Opcodes.VEC, Opcodes.LOCALTIME, Opcodes.GMTIME, Opcodes.RESET, Opcodes.TIMES, Opcodes.CRYPT,
                                 Opcodes.CLOSE, Opcodes.BINMODE, Opcodes.SEEK, Opcodes.EOF_OP, Opcodes.SYSREAD,
                                 Opcodes.SYSWRITE, Opcodes.SYSOPEN, Opcodes.SOCKET, Opcodes.BIND, Opcodes.CONNECT,
                                 Opcodes.LISTEN, Opcodes.WRITE, Opcodes.FORMLINE, Opcodes.PRINTF, Opcodes.ACCEPT,
                                 Opcodes.SYSSEEK, Opcodes.TRUNCATE, Opcodes.READ, Opcodes.OPENDIR, Opcodes.READDIR,
                                 Opcodes.SEEKDIR -> {
                                pc = MiscOpcodeHandler.execute(opcode, bytecode, pc, registers);
                            }

                            case Opcodes.SET_PACKAGE -> {
                                // Non-scoped package declaration: package Foo;
                                // Update the runtime current-package tracker so caller() returns the right package.
                                int nameIdx = bytecode[pc++];
                                InterpreterState.currentPackage.get().set(code.stringPool[nameIdx]);
                            }

                            case Opcodes.PUSH_PACKAGE -> {
                                pc = InlineOpcodeHandler.executePushPackage(bytecode, pc, registers, code);
                            }

                            case Opcodes.FLIP_FLOP -> {
                                pc = InlineOpcodeHandler.executeFlipFlop(bytecode, pc, registers);
                            }

                            case Opcodes.LOCAL_GLOB -> {
                                pc = InlineOpcodeHandler.executeLocalGlob(bytecode, pc, registers, code);
                            }

                            case Opcodes.GET_LOCAL_LEVEL -> {
                                pc = InlineOpcodeHandler.executeGetLocalLevel(bytecode, pc, registers);
                            }

                            case Opcodes.POP_PACKAGE -> {
                                // Scoped package block exit — restore handled by POP_LOCAL_LEVEL.
                            }

                            case Opcodes.DO_FILE -> {
                                pc = InlineOpcodeHandler.executeDoFile(bytecode, pc, registers);
                            }

                            default -> {
                                int opcodeInt = opcode;
                                throw new RuntimeException(
                                        "Unknown opcode: " + opcodeInt +
                                                " at pc=" + (pc - 1) +
                                                " in " + code.sourceName + ":" + code.sourceLine
                                );
                            }
                        }
                    }

                    // Fell through end of bytecode - return empty list
                    return new RuntimeList();

                } catch (ClassCastException e) {
                    // Special handling for ClassCastException to show which opcode is failing
                    // Check if we're inside an eval block first
                    if (!evalCatchStack.isEmpty()) {
                        int catchPc = evalCatchStack.pop();
                        WarnDie.catchEval(e);
                        pc = catchPc;
                        continue outer;
                    }

                    // Not in eval - show detailed error with bytecode context
                    int errorPc = Math.max(0, pc - 1);

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

                    StackTraceElement[] st = e.getStackTrace();
                    String javaLine = (st.length > 0) ? " [java:" + st[0].getFileName() + ":" + st[0].getLineNumber() + "]" : "";
                    String errorMessage = "ClassCastException" + bcContext + ": " + e.getMessage() + javaLine;
                    throw new RuntimeException(formatInterpreterError(code, errorPc, new Exception(errorMessage)), e);
                } catch (Throwable e) {
                    // Check if we're inside an eval block
                    if (!evalCatchStack.isEmpty()) {
                        // Inside eval block - catch the exception
                        int catchPc = evalCatchStack.pop(); // Pop the catch handler

                        // Call WarnDie.catchEval() to set $@
                        WarnDie.catchEval(e);

                        pc = catchPc;
                        continue outer;
                    }

                    // Not in eval block - propagate exception
                    // If it's already a PerlDieException, re-throw as-is for proper formatting
                    if (e instanceof PerlDieException) {
                        throw (PerlDieException) e;
                    }

                    // Check if we're running inside an eval STRING context
                    // (sourceName starts with "(eval " when code is from eval STRING)
                    // In this case, don't wrap the exception - let the outer eval handler catch it
                    boolean insideEvalString = code.sourceName != null
                            && (code.sourceName.startsWith("(eval ") || code.sourceName.endsWith("(eval)"));
                    if (insideEvalString) {
                        // Re-throw as-is - will be caught by EvalStringHandler.evalString()
                        throw e;
                    }

                    // Wrap other exceptions with interpreter context including bytecode context
                    int debugPc = Math.max(0, pc - 3);
                    String opcodeInfo = " [opcodes at pc-3..pc: ";
                    for (int di = debugPc; di <= Math.min(pc + 2, bytecode.length - 1); di++) {
                        if (di == pc) opcodeInfo += ">>>";
                        opcodeInfo += bytecode[di] + " ";
                        if (di == pc) opcodeInfo += "<<< ";
                    }
                    opcodeInfo += "]";
                    String errorMessage = formatInterpreterError(code, pc, e) + opcodeInfo;
                    throw new RuntimeException(errorMessage, e);
                }
            } // end outer while (eval/die retry loop)
        } finally {
            // Outer finally: restore interpreter state saved at method entry.
            // Unwinds all `local` variables pushed during this frame, restores
            // the current package, and pops the InterpreterState call stack.
            DynamicVariableManager.popToLocalLevel(savedLocalLevel);
            InterpreterState.currentPackage.get().set(savedPackage);
            InterpreterState.pop();
        }
    }

    /**
     * Handle comparison and logical operations (opcodes 31-41).
     * Separated to keep main execute() under JIT compilation limit.
     *
     * @return Updated program counter
     */
    private static int executeComparisons(int opcode, int[] bytecode, int pc,
                                          RuntimeBase[] registers) {
        switch (opcode) {
            case Opcodes.COMPARE_NUM -> {
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

            case Opcodes.COMPARE_STR -> {
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

            case Opcodes.EQ_NUM -> {
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

            case Opcodes.LT_NUM -> {
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

            case Opcodes.GT_NUM -> {
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

            case Opcodes.LE_NUM -> {
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

            case Opcodes.GE_NUM -> {
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

            case Opcodes.NE_NUM -> {
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

            case Opcodes.EQ_STR -> {
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

            case Opcodes.NE_STR -> {
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

            case Opcodes.NOT -> {
                int rd = bytecode[pc++];
                int rs = bytecode[pc++];
                RuntimeScalar val = (registers[rs] instanceof RuntimeScalar)
                        ? (RuntimeScalar) registers[rs]
                        : registers[rs].scalar();
                registers[rd] = val.getBoolean() ?
                        RuntimeScalarCache.scalarFalse : RuntimeScalarCache.scalarTrue;
                return pc;
            }

            case Opcodes.AND -> {
                // AND is short-circuit and handled in compiler typically
                // If we get here, just do boolean and
                int rd = bytecode[pc++];
                int rs1 = bytecode[pc++];
                int rs2 = bytecode[pc++];
                RuntimeScalar v1 = registers[rs1].scalar();
                RuntimeScalar v2 = registers[rs2].scalar();
                registers[rd] = (v1.getBoolean() && v2.getBoolean()) ?
                        RuntimeScalarCache.scalarTrue : RuntimeScalarCache.scalarFalse;
                return pc;
            }

            case Opcodes.OR -> {
                // OR is short-circuit and handled in compiler typically
                // If we get here, just do boolean or
                int rd = bytecode[pc++];
                int rs1 = bytecode[pc++];
                int rs2 = bytecode[pc++];
                RuntimeScalar v1 = registers[rs1].scalar();
                RuntimeScalar v2 = registers[rs2].scalar();
                registers[rd] = (v1.getBoolean() || v2.getBoolean()) ?
                        RuntimeScalarCache.scalarTrue : RuntimeScalarCache.scalarFalse;
                return pc;
            }

            default -> throw new RuntimeException("Unknown comparison opcode: " + opcode);
        }
    }

    /**
     * Execute type and reference operations.
     * Handles: DEFINED, REF, BLESS, ISA, PROTOTYPE, QUOTE_REGEX
     */
    private static int executeTypeOps(int opcode, int[] bytecode, int pc,
                                      RuntimeBase[] registers, InterpretedCode code) {
        switch (opcode) {
            case Opcodes.DEFINED -> {
                int rd = bytecode[pc++];
                int rs = bytecode[pc++];
                RuntimeBase v = registers[rs];
                boolean defined = v != null && v.scalar().getDefinedBoolean();
                registers[rd] = defined ? RuntimeScalarCache.scalarTrue : RuntimeScalarCache.scalarFalse;
                return pc;
            }
            case Opcodes.REF -> {
                int rd = bytecode[pc++];
                int rs = bytecode[pc++];
                registers[rd] = ReferenceOperators.ref(registers[rs].scalar());
                return pc;
            }
            case Opcodes.BLESS -> {
                int rd = bytecode[pc++];
                int refReg = bytecode[pc++];
                int pkgReg = bytecode[pc++];
                RuntimeScalar ref = registers[refReg].scalar();
                RuntimeScalar pkg = registers[pkgReg].scalar();
                registers[rd] = ReferenceOperators.bless(ref, pkg);
                return pc;
            }
            case Opcodes.ISA -> {
                int rd = bytecode[pc++];
                int objReg = bytecode[pc++];
                int pkgReg = bytecode[pc++];
                RuntimeScalar obj = registers[objReg].scalar();
                RuntimeScalar pkg = registers[pkgReg].scalar();
                registers[rd] = ReferenceOperators.isa(obj, pkg);
                return pc;
            }
            case Opcodes.PROTOTYPE -> {
                int rd = bytecode[pc++];
                int rs = bytecode[pc++];
                int packageIdx = readInt(bytecode, pc);
                pc += 1;
                String packageName = (code.stringPool != null && packageIdx >= 0 && packageIdx < code.stringPool.length)
                        ? code.stringPool[packageIdx]
                        : "main";
                registers[rd] = RuntimeCode.prototype(registers[rs].scalar(), packageName);
                return pc;
            }
            case Opcodes.QUOTE_REGEX -> {
                int rd = bytecode[pc++];
                int patternReg = bytecode[pc++];
                int flagsReg = bytecode[pc++];
                registers[rd] = RuntimeRegex.getQuotedRegex(registers[patternReg].scalar(), registers[flagsReg].scalar());
                return pc;
            }
            default -> throw new RuntimeException("Unknown type opcode: " + opcode);
        }
    }

    /**
     * Execute slice operations (opcodes 114-121).
     * Handles: DEREF_ARRAY, DEREF_HASH, *_SLICE, *_SLICE_SET, *_SLICE_DELETE, LIST_SLICE_FROM
     * Direct dispatch to SlowOpcodeHandler methods (Phase 2 complete).
     */
    private static int executeSliceOps(int opcode, int[] bytecode, int pc,
                                       RuntimeBase[] registers, InterpretedCode code) {
        // Direct method calls - no SLOWOP_* constants needed!
        switch (opcode) {
            case Opcodes.DEREF_ARRAY -> {
                return SlowOpcodeHandler.executeDerefArray(bytecode, pc, registers);
            }
            case Opcodes.DEREF_HASH -> {
                return SlowOpcodeHandler.executeDerefHash(bytecode, pc, registers);
            }
            case Opcodes.DEREF_HASH_NONSTRICT -> {
                return SlowOpcodeHandler.executeDerefHashNonStrict(bytecode, pc, registers, code);
            }
            case Opcodes.DEREF_ARRAY_NONSTRICT -> {
                return SlowOpcodeHandler.executeDerefArrayNonStrict(bytecode, pc, registers, code);
            }
            case Opcodes.ARRAY_SLICE -> {
                return SlowOpcodeHandler.executeArraySlice(bytecode, pc, registers);
            }
            case Opcodes.ARRAY_SLICE_SET -> {
                return SlowOpcodeHandler.executeArraySliceSet(bytecode, pc, registers);
            }
            case Opcodes.HASH_SLICE -> {
                return SlowOpcodeHandler.executeHashSlice(bytecode, pc, registers);
            }
            case Opcodes.HASH_SLICE_SET -> {
                return SlowOpcodeHandler.executeHashSliceSet(bytecode, pc, registers);
            }
            case Opcodes.HASH_SLICE_DELETE -> {
                return SlowOpcodeHandler.executeHashSliceDelete(bytecode, pc, registers);
            }
            case Opcodes.HASH_KEYVALUE_SLICE -> {
                return SlowOpcodeHandler.executeHashKeyValueSlice(bytecode, pc, registers);
            }
            case Opcodes.LIST_SLICE_FROM -> {
                return SlowOpcodeHandler.executeListSliceFrom(bytecode, pc, registers);
            }
            default -> throw new RuntimeException("Unknown slice opcode: " + opcode);
        }
    }

    /**
     * Execute array/string operations (opcodes 122-127).
     * Handles: SPLICE, REVERSE, SPLIT, LENGTH_OP, EXISTS, DELETE
     */
    private static int executeArrayStringOps(int opcode, int[] bytecode, int pc,
                                             RuntimeBase[] registers, InterpretedCode code) {
        switch (opcode) {
            case Opcodes.SPLICE -> {
                return SlowOpcodeHandler.executeSplice(bytecode, pc, registers);
            }
            case Opcodes.REVERSE -> {
                return SlowOpcodeHandler.executeReverse(bytecode, pc, registers);
            }
            case Opcodes.SPLIT -> {
                return SlowOpcodeHandler.executeSplit(bytecode, pc, registers);
            }
            case Opcodes.LENGTH_OP -> {
                return SlowOpcodeHandler.executeLength(bytecode, pc, registers);
            }
            case Opcodes.EXISTS -> {
                return SlowOpcodeHandler.executeExists(bytecode, pc, registers);
            }
            case Opcodes.DELETE -> {
                return SlowOpcodeHandler.executeDelete(bytecode, pc, registers);
            }
            default -> throw new RuntimeException("Unknown array/string opcode: " + opcode);
        }
    }

    /**
     * Execute closure/scope operations (opcodes 128-131).
     * Handles: RETRIEVE_BEGIN_*, LOCAL_SCALAR
     */
    private static int executeScopeOps(int opcode, int[] bytecode, int pc,
                                       RuntimeBase[] registers, InterpretedCode code) {
        switch (opcode) {
            case Opcodes.RETRIEVE_BEGIN_SCALAR -> {
                return SlowOpcodeHandler.executeRetrieveBeginScalar(bytecode, pc, registers, code);
            }
            case Opcodes.RETRIEVE_BEGIN_ARRAY -> {
                return SlowOpcodeHandler.executeRetrieveBeginArray(bytecode, pc, registers, code);
            }
            case Opcodes.RETRIEVE_BEGIN_HASH -> {
                return SlowOpcodeHandler.executeRetrieveBeginHash(bytecode, pc, registers, code);
            }
            case Opcodes.LOCAL_SCALAR -> {
                return SlowOpcodeHandler.executeLocalScalar(bytecode, pc, registers, code);
            }
            case Opcodes.LOCAL_ARRAY -> {
                int rd = bytecode[pc++];
                int nameIdx = bytecode[pc++];
                String fullName = code.stringPool[nameIdx];

                RuntimeArray arr = GlobalVariable.getGlobalArray(fullName);
                DynamicVariableManager.pushLocalVariable(arr);
                registers[rd] = GlobalVariable.getGlobalArray(fullName);
                return pc;
            }
            case Opcodes.LOCAL_HASH -> {
                int rd = bytecode[pc++];
                int nameIdx = bytecode[pc++];
                String fullName = code.stringPool[nameIdx];

                RuntimeHash hash = GlobalVariable.getGlobalHash(fullName);
                DynamicVariableManager.pushLocalVariable(hash);
                registers[rd] = GlobalVariable.getGlobalHash(fullName);
                return pc;
            }
            default -> throw new RuntimeException("Unknown scope opcode: " + opcode);
        }
    }

    /**
     * Execute system call and IPC operations (opcodes 132-150).
     * Handles: CHOWN, WAITPID, FORK, GETPPID, *PGRP, *PRIORITY, *SOCKOPT,
     * SYSCALL, SEMGET, SEMOP, MSGGET, MSGSND, MSGRCV, SHMGET, SHMREAD, SHMWRITE
     */
    private static int executeSystemOps(int opcode, int[] bytecode, int pc,
                                        RuntimeBase[] registers) {
        switch (opcode) {
            case Opcodes.CHOWN -> {
                return MiscOpcodeHandler.execute(Opcodes.CHOWN, bytecode, pc, registers);
            }
            case Opcodes.WAITPID -> {
                return MiscOpcodeHandler.execute(Opcodes.WAITPID, bytecode, pc, registers);
            }
            case Opcodes.FORK -> {
                return SlowOpcodeHandler.executeFork(bytecode, pc, registers);
            }
            case Opcodes.GETPPID -> {
                return SlowOpcodeHandler.executeGetppid(bytecode, pc, registers);
            }
            case Opcodes.GETPGRP -> {
                return MiscOpcodeHandler.execute(Opcodes.GETPGRP, bytecode, pc, registers);
            }
            case Opcodes.SETPGRP -> {
                return MiscOpcodeHandler.execute(Opcodes.SETPGRP, bytecode, pc, registers);
            }
            case Opcodes.GETPRIORITY -> {
                return MiscOpcodeHandler.execute(Opcodes.GETPRIORITY, bytecode, pc, registers);
            }
            case Opcodes.SETPRIORITY -> {
                return MiscOpcodeHandler.execute(Opcodes.SETPRIORITY, bytecode, pc, registers);
            }
            case Opcodes.GETSOCKOPT -> {
                return MiscOpcodeHandler.execute(Opcodes.GETSOCKOPT, bytecode, pc, registers);
            }
            case Opcodes.SETSOCKOPT -> {
                return MiscOpcodeHandler.execute(Opcodes.SETSOCKOPT, bytecode, pc, registers);
            }
            case Opcodes.SYSCALL -> {
                return SlowOpcodeHandler.executeSyscall(bytecode, pc, registers);
            }
            case Opcodes.SEMGET -> {
                return SlowOpcodeHandler.executeSemget(bytecode, pc, registers);
            }
            case Opcodes.SEMOP -> {
                return SlowOpcodeHandler.executeSemop(bytecode, pc, registers);
            }
            case Opcodes.MSGGET -> {
                return SlowOpcodeHandler.executeMsgget(bytecode, pc, registers);
            }
            case Opcodes.MSGSND -> {
                return SlowOpcodeHandler.executeMsgsnd(bytecode, pc, registers);
            }
            case Opcodes.MSGRCV -> {
                return SlowOpcodeHandler.executeMsgrcv(bytecode, pc, registers);
            }
            case Opcodes.SHMGET -> {
                return SlowOpcodeHandler.executeShmget(bytecode, pc, registers);
            }
            case Opcodes.SHMREAD -> {
                return SlowOpcodeHandler.executeShmread(bytecode, pc, registers);
            }
            case Opcodes.SHMWRITE -> {
                return SlowOpcodeHandler.executeShmwrite(bytecode, pc, registers);
            }
            default -> throw new RuntimeException("Unknown system opcode: " + opcode);
        }
    }

    /**
     * Execute special I/O operations (opcodes 151-154).
     * Handles: EVAL_STRING, SELECT_OP, LOAD_GLOB, SLEEP_OP, DEREF_GLOB, LOAD_GLOB_DYNAMIC,
     * DEREF_SCALAR_STRICT, DEREF_SCALAR_NONSTRICT
     */
    private static int executeSpecialIO(int opcode, int[] bytecode, int pc,
                                        RuntimeBase[] registers, InterpretedCode code) {
        switch (opcode) {
            case Opcodes.EVAL_STRING -> {
                return SlowOpcodeHandler.executeEvalString(bytecode, pc, registers, code);
            }
            case Opcodes.SELECT_OP -> {
                return SlowOpcodeHandler.executeSelect(bytecode, pc, registers);
            }
            case Opcodes.LOAD_GLOB -> {
                return SlowOpcodeHandler.executeLoadGlob(bytecode, pc, registers, code);
            }
            case Opcodes.SLEEP_OP -> {
                return SlowOpcodeHandler.executeSleep(bytecode, pc, registers);
            }
            case Opcodes.ALARM_OP -> {
                return SlowOpcodeHandler.executeAlarm(bytecode, pc, registers);
            }
            case Opcodes.DEREF_GLOB -> {
                return SlowOpcodeHandler.executeDerefGlob(bytecode, pc, registers, code);
            }
            case Opcodes.DEREF_GLOB_NONSTRICT -> {
                return SlowOpcodeHandler.executeDerefGlobNonStrict(bytecode, pc, registers, code);
            }
            case Opcodes.LOAD_GLOB_DYNAMIC -> {
                return SlowOpcodeHandler.executeLoadGlobDynamic(bytecode, pc, registers, code);
            }
            case Opcodes.DEREF_SCALAR_STRICT -> {
                return SlowOpcodeHandler.executeDerefScalarStrict(bytecode, pc, registers);
            }
            case Opcodes.DEREF_SCALAR_NONSTRICT -> {
                return SlowOpcodeHandler.executeDerefScalarNonStrict(bytecode, pc, registers, code);
            }
            default -> throw new RuntimeException("Unknown special I/O opcode: " + opcode);
        }
    }

    /**
     * Read a 32-bit integer from bytecode (stored as 1 int slot).
     * With int[] storage a full int fits in a single slot.
     */
    private static int readInt(int[] bytecode, int pc) {
        return bytecode[pc];
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
