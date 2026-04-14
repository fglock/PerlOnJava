package org.perlonjava.backend.bytecode;

import java.util.BitSet;

import org.perlonjava.runtime.debugger.DebugHooks;
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
        // Prefer code.subName (set by set_subname) over passed subroutineName
        // This ensures caller() returns the name set by set_subname()
        String frameSubName = code.subName != null ? code.subName : (subroutineName != null ? subroutineName : "(eval)");
        // Get PC holder for direct updates (avoids ThreadLocal lookups in hot loop)
        int[] pcHolder = InterpreterState.push(code, framePackageName, frameSubName);

        // Get register array from cache (avoids allocation for non-recursive calls)
        RuntimeBase[] registers = code.getRegisters();

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
        // Use ArrayDeque instead of Stack for better performance (no synchronization)
        java.util.ArrayDeque<Integer> evalCatchStack = new java.util.ArrayDeque<>();

        // Parallel stack tracking DynamicVariableManager local level at eval entry.
        // When EVAL_TRY is executed, save the current local level.
        // On eval exit (both normal EVAL_END and exception catch), restore to this level
        // so that `local` variables inside the eval block are properly unwound.
        java.util.ArrayDeque<Integer> evalLocalLevelStack = new java.util.ArrayDeque<>();

        // Parallel stack tracking the first register allocated inside the eval body.
        // When an exception is caught, registers from this index to the end of the
        // register array are cleaned up (scope exit cleanup + mortal flush) so that
        // DESTROY fires for blessed objects that went out of scope during die.
        java.util.ArrayDeque<Integer> evalBaseRegStack = new java.util.ArrayDeque<>();

        // Labeled block stack for non-local last/next/redo handling.
        // When a function call returns a RuntimeControlFlowList, we check this stack
        // to see if the label matches an enclosing labeled block.
        // Uses ArrayList for O(1) indexed access when searching for labels
        java.util.ArrayList<int[]> labeledBlockStack = new java.util.ArrayList<>();
        // Each entry is [labelStringPoolIdx, exitPc]

        java.util.ArrayDeque<RegexState> regexStateStack = new java.util.ArrayDeque<>();

        // Optimization: only save/restore DynamicVariableManager state if the code uses localization.
        // This avoids overhead for simple subroutines that don't use `local`.
        boolean usesLocalization = code.usesLocalization;
        // Record DVM level so the finally block can clean up everything pushed
        // by this subroutine (local variables AND regex state snapshot).
        int savedLocalLevel = usesLocalization ? DynamicVariableManager.getLocalLevel() : 0;
        // Cache the currentPackage RuntimeScalar to avoid ThreadLocal lookups in hot loop
        RuntimeScalar currentPackageScalar = InterpreterState.currentPackage.get();
        String savedPackage = currentPackageScalar.toString();
        currentPackageScalar.set(framePackageName);
        if (usesLocalization) {
            RegexState.save();
        }
        // Track whether an exception is propagating out of this frame, so the
        // finally block can do scope-exit cleanup for blessed objects in my-variables.
        // Without this, DESTROY doesn't fire for objects in subroutines that are
        // unwound by die when there's no enclosing eval in the same frame.
        Throwable propagatingException = null;

        // First my-variable register index (skip reserved + captured vars).
        int firstMyVarReg = 3 + (code.capturedVars != null ? code.capturedVars.length : 0);

        // Track closures created by CREATE_CLOSURE in this frame.
        // At frame exit, we release captures for closures that were never stored
        // via set() (refCount stayed at 0). This handles eval STRING map/grep
        // block closures that over-capture all visible variables but are temporary.
        // This matches the JVM-compiled path where scopeExitCleanup releases
        // captures for CODE refs with refCount=0 (RuntimeScalar.java line ~2185).
        java.util.List<RuntimeCode> createdClosures = null;

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
                        // Uses cached pcHolder to avoid ThreadLocal lookups in hot loop.
                        pcHolder[0] = pc;
                        int opcode = bytecode[pc++];

                        switch (opcode) {
                            // =================================================================
                            // CONTROL FLOW
                            // =================================================================

                            case Opcodes.NOP -> {
                                // No operation
                            }

                            case Opcodes.MORTAL_FLUSH -> {
                                // Flush deferred mortal decrements (FREETMPS equivalent)
                                MortalList.flush();
                            }

                            case Opcodes.MORTAL_PUSH_MARK -> {
                                // Push mark before scope-exit cleanup (SAVETMPS equivalent)
                                MortalList.pushMark();
                            }

                            case Opcodes.MORTAL_POP_FLUSH -> {
                                // Pop mark and flush only entries added since it (scoped FREETMPS)
                                MortalList.popAndFlush();
                            }

                            case Opcodes.SCOPE_EXIT_CLEANUP -> {
                                // Scope-exit cleanup for a my-scalar register
                                int reg = bytecode[pc++];
                                if (registers[reg] instanceof RuntimeScalar rs) {
                                    RuntimeScalar.scopeExitCleanup(rs);
                                }
                                registers[reg] = null;
                            }

                            case Opcodes.SCOPE_EXIT_CLEANUP_HASH -> {
                                // Scope-exit cleanup for a my-hash register
                                int reg = bytecode[pc++];
                                if (registers[reg] instanceof RuntimeHash rh) {
                                    MortalList.scopeExitCleanupHash(rh);
                                }
                                registers[reg] = null;
                            }

                            case Opcodes.SCOPE_EXIT_CLEANUP_ARRAY -> {
                                // Scope-exit cleanup for a my-array register
                                int reg = bytecode[pc++];
                                if (registers[reg] instanceof RuntimeArray ra) {
                                    MortalList.scopeExitCleanupArray(ra);
                                }
                                registers[reg] = null;
                            }

                            case Opcodes.RETURN -> {
                                // Return from subroutine: return rd
                                int retReg = bytecode[pc++];
                                RuntimeBase retVal = registers[retReg];

                                if (retVal == null) {
                                    retVal = new RuntimeList();
                                }
                                RuntimeList retList = retVal.getList();
                                RuntimeCode.materializeSpecialVarsInResult(retList);

                                return retList;
                            }

                            case Opcodes.RETURN_NONLOCAL -> {
                                // Non-local return from map/grep block (explicit 'return' statement):
                                // wrap in RETURN marker so it propagates to enclosing subroutine
                                int retReg = bytecode[pc++];
                                RuntimeBase retVal = registers[retReg];

                                if (retVal == null) {
                                    retVal = new RuntimeList();
                                }
                                RuntimeList retList = retVal.getList();
                                RuntimeCode.materializeSpecialVarsInResult(retList);

                                return new RuntimeControlFlowList(retList, code.sourceName, code.sourceLine);
                            }

                            case Opcodes.GOTO -> {
                                // Unconditional jump: pc = offset
                                int offset = readInt(bytecode, pc);
                                pc = offset;  // Registers persist across jump (unlike stack-based!)
                            }

                            case Opcodes.GOTO_DYNAMIC -> {
                                // Dynamic goto: evaluate register to get label name, look up PC
                                int rs = bytecode[pc++];
                                RuntimeScalar target = (RuntimeScalar) registers[rs];
                                // Dereference if target is a reference to CODE (e.g., goto \&sub)
                                if (target.type == RuntimeScalarType.REFERENCE) {
                                    RuntimeScalar deref = (RuntimeScalar) target.value;
                                    if (deref.type == RuntimeScalarType.CODE) {
                                        target = deref;
                                    }
                                }
                                // If target is a CODE reference, treat as goto &sub (tail call)
                                if (target.type == RuntimeScalarType.CODE) {
                                    // Create a TAILCALL marker - pass current @_ (register 1)
                                    RuntimeArray currentArgs = (registers[1] instanceof RuntimeArray)
                                            ? (RuntimeArray) registers[1]
                                            : new RuntimeArray();
                                    RuntimeControlFlowList marker = new RuntimeControlFlowList(
                                            target, currentArgs, code.sourceName, code.sourceLine);
                                    return marker;
                                }
                                String labelName = target.toString();
                                if (labelName.isEmpty()) {
                                    // Bare `goto` without label - runtime error like Perl 5
                                    throw new PerlCompilerException("goto must have label");
                                }
                                if (code.gotoLabelPcs != null) {
                                    Integer targetPc = code.gotoLabelPcs.get(labelName);
                                    if (targetPc != null) {
                                        pc = targetPc;
                                        break;
                                    }
                                }
                                // Label not found locally - create GOTO marker and propagate
                                RuntimeControlFlowList marker = new RuntimeControlFlowList(
                                        ControlFlowType.GOTO, labelName, code.sourceName, code.sourceLine);
                                return marker;
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
                                    String disasm = Disassemble.disassemble(code);
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
                                int closureRd = bytecode[pc]; // peek at destination register
                                pc = OpcodeHandlerExtended.executeCreateClosure(bytecode, pc, registers, code);
                                // Track closure for frame-exit capture release.
                                // The interpreter's BytecodeCompiler captures ALL visible
                                // variables for closures (for eval STRING compatibility),
                                // inflating captureCount on variables the closure doesn't
                                // actually use. When the closure is temporary (map/grep
                                // block), releaseCaptures must fire to decrement captureCount.
                                RuntimeBase closureVal = registers[closureRd];
                                if (closureVal instanceof RuntimeScalar crs
                                        && crs.value instanceof RuntimeCode ic
                                        && ic.capturedScalars != null) {
                                    if (createdClosures == null) {
                                        createdClosures = new java.util.ArrayList<>();
                                    }
                                    createdClosures.add(ic);
                                }
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

                            case Opcodes.DEFINED, Opcodes.DEFINED_CODE, Opcodes.DEFINED_GLOB, Opcodes.REF, Opcodes.BLESS, Opcodes.ISA, Opcodes.SMARTMATCH, Opcodes.PROTOTYPE,
                                 Opcodes.QUOTE_REGEX, Opcodes.QUOTE_REGEX_O -> {
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

                            case Opcodes.HASH_GET_FOR_LOCAL -> {
                                // Like HASH_GET but always returns a RuntimeHashProxyEntry.
                                // Used by local $hash{key} so the proxy can re-resolve
                                // the key in the parent hash on restore (survives %hash = (...)).
                                int rd = bytecode[pc++];
                                int hashReg = bytecode[pc++];
                                int keyReg = bytecode[pc++];
                                RuntimeHash hash = (RuntimeHash) registers[hashReg];
                                RuntimeScalar key = (RuntimeScalar) registers[keyReg];
                                registers[rd] = hash.getForLocal(key);
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

                            case Opcodes.HASH_DELETE_LOCAL -> {
                                pc = InlineOpcodeHandler.executeHashDeleteLocal(bytecode, pc, registers);
                            }

                            case Opcodes.ARRAY_DELETE_LOCAL -> {
                                pc = InlineOpcodeHandler.executeArrayDeleteLocal(bytecode, pc, registers);
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

                            case Opcodes.CALL_SUB, Opcodes.CALL_SUB_SHARE_ARGS -> {
                                // Call subroutine: rd = coderef->(args)
                                // CALL_SUB_SHARE_ARGS: &func (no parens) shares caller's @_ by alias
                                // May return RuntimeControlFlowList!
                                // pcHolder[0] contains the PC of this opcode (set before opcode read)
                                boolean shareArgs = (opcode == Opcodes.CALL_SUB_SHARE_ARGS);
                                // pcHolder[0] contains the PC of this opcode (set before opcode read)
                                int callSitePc = pcHolder[0];
                                int rd = bytecode[pc++];
                                int coderefReg = bytecode[pc++];
                                int argsReg = bytecode[pc++];
                                int context = bytecode[pc++];

                                // Resolve RUNTIME context from register 2 (wantarray).
                                // When a subroutine body is compiled by the interpreter,
                                // the calling context is not known at compile time, so
                                // RUNTIME is baked into the bytecode. At execution time,
                                // resolve it from the actual calling context in register 2.
                                if (context == RuntimeContextType.RUNTIME) {
                                    context = ((RuntimeScalar) registers[2]).getInt();
                                }

                                // Auto-convert coderef to scalar if needed
                                RuntimeBase codeRefBase = registers[coderefReg];
                                RuntimeScalar codeRef = (codeRefBase instanceof RuntimeScalar)
                                        ? (RuntimeScalar) codeRefBase
                                        : codeRefBase.scalar();

                                // Dereference symbolic code references using current package
                                // This matches the JVM backend's call to codeDerefNonStrict()
                                // Only call for STRING/BYTE_STRING types (symbolic references)
                                // For CODE, REFERENCE, etc. let RuntimeCode.apply() handle errors
                                // Use cached RuntimeScalar to avoid ThreadLocal lookup
                                if (codeRef.type == RuntimeScalarType.STRING || codeRef.type == RuntimeScalarType.BYTE_STRING) {
                                    codeRef = codeRef.codeDerefNonStrict(currentPackageScalar.toString());
                                }

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

                                // Push lazy call site info to CallerStack for caller() to see the correct location
                                // The actual line number computation is deferred until caller() is called
                                // Capture variables needed for lazy resolution
                                final String lazyPkg = currentPackageScalar.toString();
                                final int lazyPc = callSitePc;
                                CallerStack.pushLazy(lazyPkg, () -> getCallSiteInfo(code, lazyPc, lazyPkg));
                                RuntimeList result;
                                try {
                                    // Fast path for InterpretedCode: call execute() directly,
                                    // bypassing RuntimeCode.apply() indirection chain
                                    if (codeRef.type == RuntimeScalarType.CODE && codeRef.value instanceof InterpretedCode interpCode) {
                                        // Direct call to interpreter - skip RuntimeCode.apply overhead
                                        // Push args to argsStack for getCallerArgs() support (used by List::Util::any/all/etc.)
                                        RuntimeCode.pushArgs(callArgs);
                                        try {
                                            // Pass null for subroutineName to enable frame caching
                                            result = BytecodeInterpreter.execute(interpCode, callArgs, context, null);
                                        } finally {
                                            RuntimeCode.popArgs();
                                        }
                                    } else {
                                        // Slow path for JVM-compiled code, symbolic references, etc.
                                        // For &func (shareArgs), use the apply overload that shares @_
                                        if (shareArgs) {
                                            result = RuntimeCode.apply(codeRef, callArgs, context);
                                        } else {
                                            result = RuntimeCode.apply(codeRef, "", callArgs, context);
                                        }
                                    }

                                    // Handle TAILCALL with trampoline loop (same as JVM backend)
                                    while (result.isNonLocalGoto()) {
                                        RuntimeControlFlowList flow = (RuntimeControlFlowList) result;
                                        if (flow.getControlFlowType() == ControlFlowType.TAILCALL) {
                                            // Extract codeRef and args, call target
                                            codeRef = flow.getTailCallCodeRef();
                                            callArgs = flow.getTailCallArgs();
                                            // Use fast path for InterpretedCode
                                            if (codeRef.type == RuntimeScalarType.CODE && codeRef.value instanceof InterpretedCode interpCode) {
                                                // Push args for tail call too
                                                RuntimeCode.pushArgs(callArgs);
                                                try {
                                                    result = BytecodeInterpreter.execute(interpCode, callArgs, context, null);
                                                } finally {
                                                    RuntimeCode.popArgs();
                                                }
                                            } else {
                                                result = RuntimeCode.apply(codeRef, "tailcall", callArgs, context);
                                            }
                                            // Loop to handle chained tail calls
                                        } else {
                                            // Not TAILCALL - check labeled blocks or propagate
                                            break;
                                        }
                                    }
                                } finally {
                                    CallerStack.pop();
                                }

                                // Convert to scalar if called in scalar context
                                if (context == RuntimeContextType.SCALAR) {
                                    RuntimeBase scalarResult = result.scalar();
                                    registers[rd] = (isImmutableProxy(scalarResult)) ? ensureMutableScalar(scalarResult) : scalarResult;
                                } else {
                                    registers[rd] = result;
                                }

                                // Check for control flow (last/next/redo/goto) - TAILCALL already handled above
                                if (result.isNonLocalGoto()) {
                                    RuntimeControlFlowList flow = (RuntimeControlFlowList) result;

                                    // Handle RETURN markers: consume at non-map/grep boundaries, propagate in map/grep
                                    if (flow.getControlFlowType() == ControlFlowType.RETURN) {
                                        if (!code.isMapGrepBlock) {
                                            // Consume: unwrap and return the value from this subroutine
                                            RuntimeBase retVal = flow.getReturnValue();
                                            return retVal != null ? retVal.getList() : new RuntimeList();
                                        }
                                        return result;  // Propagate in map/grep blocks
                                    }

                                    // Check labeled block stack for a matching label
                                    boolean handled = false;
                                    for (int i = labeledBlockStack.size() - 1; i >= 0; i--) {
                                        int[] entry = labeledBlockStack.get(i);
                                        String blockLabel = code.stringPool[entry[0]];
                                        if (flow.matchesLabel(blockLabel)) {
                                            // Pop entries down to and including the match
                                            while (labeledBlockStack.size() > i) {
                                                labeledBlockStack.removeLast();
                                            }
                                            pc = entry[1]; // jump to block exit
                                            handled = true;
                                            break;
                                        }
                                    }
                                    if (!handled) {
                                        // GOTO/TAILCALL markers inside eval should be caught
                                        // (same as JVM backend's EmitEval: ordinal > 2 means not LAST/NEXT/REDO)
                                        ControlFlowType cfType = flow.getControlFlowType();
                                        if ((cfType == ControlFlowType.GOTO || cfType == ControlFlowType.TAILCALL)
                                                && !evalCatchStack.isEmpty()) {
                                            // Set $@ to the error message
                                            String errorMsg = flow.marker.buildErrorMessage();
                                            GlobalVariable.setGlobalVariable("main::@", errorMsg);
                                            // Restore local variables pushed inside the eval block
                                            if (!evalLocalLevelStack.isEmpty()) {
                                                int savedLevel = evalLocalLevelStack.pop();
                                                DynamicVariableManager.popToLocalLevel(savedLevel);
                                            }
                                            // Jump to eval catch handler
                                            pc = evalCatchStack.pop();
                                            RuntimeCode.evalDepth--;
                                            break;
                                        }
                                        return result;
                                    }
                                }
                            }

                            case Opcodes.CALL_METHOD -> {
                                // Call method: rd = RuntimeCode.call(invocant, method, currentSub, args, context)
                                // May return RuntimeControlFlowList!
                                // pcHolder[0] contains the PC of this opcode (set before opcode read)
                                int callSitePc = pcHolder[0];
                                int rd = bytecode[pc++];
                                int invocantReg = bytecode[pc++];
                                int methodReg = bytecode[pc++];
                                int currentSubReg = bytecode[pc++];
                                int argsReg = bytecode[pc++];
                                int context = bytecode[pc++];

                                // Resolve RUNTIME context from register 2 (wantarray)
                                if (context == RuntimeContextType.RUNTIME) {
                                    context = ((RuntimeScalar) registers[2]).getInt();
                                }

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

                                // Push lazy call site info to CallerStack for caller() to see the correct location
                                // Capture variables needed for lazy resolution
                                final String lazyPkg = currentPackageScalar.toString();
                                final int lazyPc = callSitePc;
                                CallerStack.pushLazy(lazyPkg, () -> getCallSiteInfo(code, lazyPc, lazyPkg));
                                RuntimeList result;
                                try {
                                    result = RuntimeCode.call(invocant, method, currentSub, callArgs, context);

                                    // Handle TAILCALL with trampoline loop (same as JVM backend)
                                    while (result.isNonLocalGoto()) {
                                        RuntimeControlFlowList flow = (RuntimeControlFlowList) result;
                                        if (flow.getControlFlowType() == ControlFlowType.TAILCALL) {
                                            // Extract codeRef and args, call target
                                            RuntimeScalar codeRef = flow.getTailCallCodeRef();
                                            callArgs = flow.getTailCallArgs();
                                            result = RuntimeCode.apply(codeRef, "tailcall", callArgs, context);
                                            // Loop to handle chained tail calls
                                        } else {
                                            // Not TAILCALL - check labeled blocks or propagate
                                            break;
                                        }
                                    }
                                } finally {
                                    CallerStack.pop();
                                }

                                // Convert to scalar if called in scalar context
                                if (context == RuntimeContextType.SCALAR) {
                                    RuntimeBase scalarResult = result.scalar();
                                    registers[rd] = (isImmutableProxy(scalarResult)) ? ensureMutableScalar(scalarResult) : scalarResult;
                                } else {
                                    registers[rd] = result;
                                }

                                // Check for control flow (last/next/redo/goto) - TAILCALL already handled above
                                if (result.isNonLocalGoto()) {
                                    RuntimeControlFlowList flow = (RuntimeControlFlowList) result;

                                    // Handle RETURN markers: consume at non-map/grep boundaries, propagate in map/grep
                                    if (flow.getControlFlowType() == ControlFlowType.RETURN) {
                                        if (!code.isMapGrepBlock) {
                                            RuntimeBase retVal = flow.getReturnValue();
                                            return retVal != null ? retVal.getList() : new RuntimeList();
                                        }
                                        return result;
                                    }

                                    boolean handled = false;
                                    for (int i = labeledBlockStack.size() - 1; i >= 0; i--) {
                                        int[] entry = labeledBlockStack.get(i);
                                        String blockLabel = code.stringPool[entry[0]];
                                        if (flow.matchesLabel(blockLabel)) {
                                            while (labeledBlockStack.size() > i) {
                                                labeledBlockStack.removeLast();
                                            }
                                            pc = entry[1];
                                            handled = true;
                                            break;
                                        }
                                    }
                                    if (!handled) {
                                        // GOTO/TAILCALL markers inside eval should be caught
                                        ControlFlowType cfType = flow.getControlFlowType();
                                        if ((cfType == ControlFlowType.GOTO || cfType == ControlFlowType.TAILCALL)
                                                && !evalCatchStack.isEmpty()) {
                                            String errorMsg = flow.marker.buildErrorMessage();
                                            GlobalVariable.setGlobalVariable("main::@", errorMsg);
                                            // Restore local variables pushed inside the eval block
                                            if (!evalLocalLevelStack.isEmpty()) {
                                                int savedLevel = evalLocalLevelStack.pop();
                                                DynamicVariableManager.popToLocalLevel(savedLevel);
                                            }
                                            pc = evalCatchStack.pop();
                                            RuntimeCode.evalDepth--;
                                            break;
                                        }
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

                            case Opcodes.CREATE_LAST_DYNAMIC, Opcodes.CREATE_NEXT_DYNAMIC, Opcodes.CREATE_REDO_DYNAMIC -> {
                                int rd = bytecode[pc++];
                                int labelReg = bytecode[pc++];
                                String label = ((RuntimeScalar) registers[labelReg]).toString();
                                ControlFlowType type = opcode == Opcodes.CREATE_LAST_DYNAMIC ? ControlFlowType.LAST
                                        : opcode == Opcodes.CREATE_NEXT_DYNAMIC ? ControlFlowType.NEXT
                                        : ControlFlowType.REDO;
                                registers[rd] = new RuntimeControlFlowList(type, label, code.sourceName, code.sourceLine);
                            }

                            case Opcodes.CREATE_GOTO -> {
                                pc = InlineOpcodeHandler.executeCreateGoto(bytecode, pc, registers, code);
                            }

                            case Opcodes.GOTO_TAILCALL -> {
                                // Create TAILCALL marker for goto &sub
                                // Format: GOTO_TAILCALL rd coderef_reg args_reg context evalScopeIdx
                                int rd = bytecode[pc++];
                                int coderefReg = bytecode[pc++];
                                int argsReg = bytecode[pc++];
                                int context = bytecode[pc++];  // unused in marker, but consumed
                                int evalScopeIdx = bytecode[pc++]; // -1 = not in eval

                                // Get coderef
                                RuntimeBase codeRefBase = registers[coderefReg];
                                RuntimeScalar codeRef = (codeRefBase instanceof RuntimeScalar)
                                        ? (RuntimeScalar) codeRefBase
                                        : codeRefBase.scalar();

                                // Dereference symbolic code references
                                // Use cached RuntimeScalar to avoid ThreadLocal lookup
                                if (codeRef.type == RuntimeScalarType.STRING || codeRef.type == RuntimeScalarType.BYTE_STRING) {
                                    codeRef = codeRef.codeDerefNonStrict(currentPackageScalar.toString());
                                }

                                // Get args
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

                                // Create TAILCALL marker with eval scope for runtime check
                                String evalScope = (evalScopeIdx >= 0) ? code.stringPool[evalScopeIdx] : null;
                                registers[rd] = new RuntimeControlFlowList(codeRef, callArgs, code.sourceName, 0, evalScope);
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
                                // Format: [EVAL_TRY] [catch_target(4 bytes)] [firstBodyReg]
                                // catch_target is absolute bytecode address

                                int catchPc = readInt(bytecode, pc);  // Read 4-byte absolute address
                                pc += 1;  // Skip the int we just read

                                int firstBodyReg = bytecode[pc++];  // First register in eval body

                                // Push catch PC onto eval stack
                                evalCatchStack.push(catchPc);

                                // Save first body register for scope cleanup on exception
                                evalBaseRegStack.push(firstBodyReg);

                                // Save local level so we can restore local variables on eval exit
                                evalLocalLevelStack.push(DynamicVariableManager.getLocalLevel());

                                // Track eval depth for $^S
                                RuntimeCode.evalDepth++;

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

                                // Pop the base register (not needed on success path)
                                if (!evalBaseRegStack.isEmpty()) {
                                    evalBaseRegStack.pop();
                                }

                                // Restore local variables that were pushed inside the eval block
                                // e.g., `eval { local @_ = @_ }` should restore @_ on eval exit
                                if (!evalLocalLevelStack.isEmpty()) {
                                    int savedLevel = evalLocalLevelStack.pop();
                                    DynamicVariableManager.popToLocalLevel(savedLevel);
                                }

                                // Track eval depth for $^S
                                RuntimeCode.evalDepth--;
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
                                labeledBlockStack.add(new int[]{labelIdx, exitPc});
                            }

                            case Opcodes.POP_LABELED_BLOCK -> {
                                if (!labeledBlockStack.isEmpty()) {
                                    labeledBlockStack.removeLast();
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

                            case Opcodes.SCALAR_IF_WANTARRAY -> {
                                pc = InlineOpcodeHandler.executeScalarIfWantarray(bytecode, pc, registers, callContext);
                            }

                            // =================================================================
                            // STRING OPERATIONS
                            // =================================================================

                            case Opcodes.JOIN -> {
                                pc = InlineOpcodeHandler.executeJoin(bytecode, pc, registers);
                            }

                            case Opcodes.JOIN_NO_OVERLOAD -> {
                                pc = InlineOpcodeHandler.executeJoinNoOverload(bytecode, pc, registers);
                            }

                            case Opcodes.CONCAT_NO_OVERLOAD -> {
                                pc = InlineOpcodeHandler.executeConcatNoOverload(bytecode, pc, registers);
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
                                 Opcodes.LOCAL_HASH, Opcodes.STATE_INIT_SCALAR, Opcodes.STATE_INIT_ARRAY,
                                 Opcodes.STATE_INIT_HASH -> {
                                pc = executeScopeOps(opcode, bytecode, pc, registers, code);
                            }

                            // Group 6-8: System Calls and IPC (132-150)
                            case Opcodes.CHOWN, Opcodes.WAITPID, Opcodes.FORK, Opcodes.GETPPID, Opcodes.GETPGRP,
                                 Opcodes.SETPGRP, Opcodes.GETPRIORITY, Opcodes.SETPRIORITY, Opcodes.GETSOCKOPT,
                                 Opcodes.SETSOCKOPT, Opcodes.SYSCALL, Opcodes.SEMGET, Opcodes.SEMOP, Opcodes.MSGGET,
                                 Opcodes.MSGSND, Opcodes.MSGRCV, Opcodes.SHMGET, Opcodes.SHMREAD, Opcodes.SHMWRITE,
                                 Opcodes.SYMLINK, Opcodes.CHROOT, Opcodes.MKDIR,
                                 Opcodes.MSGCTL, Opcodes.SHMCTL, Opcodes.SEMCTL,
                                 Opcodes.EXEC, Opcodes.FCNTL, Opcodes.IOCTL,
                                 Opcodes.GETPWENT, Opcodes.SETPWENT, Opcodes.ENDPWENT,
                                 Opcodes.GETLOGIN, Opcodes.GETPWNAM, Opcodes.GETPWUID,
                                 Opcodes.GETGRNAM, Opcodes.GETGRGID, Opcodes.GETGRENT,
                                 Opcodes.SETGRENT, Opcodes.ENDGRENT,
                                 Opcodes.GETHOSTBYADDR, Opcodes.GETSERVBYNAME,
                                 Opcodes.GETSERVBYPORT, Opcodes.GETPROTOBYNAME,
                                 Opcodes.GETPROTOBYNUMBER, Opcodes.ENDHOSTENT,
                                 Opcodes.ENDNETENT, Opcodes.ENDPROTOENT,
                                 Opcodes.ENDSERVENT, Opcodes.GETHOSTENT,
                                 Opcodes.GETNETBYADDR, Opcodes.GETNETBYNAME,
                                 Opcodes.GETNETENT, Opcodes.GETPROTOENT,
                                 Opcodes.GETSERVENT, Opcodes.SETHOSTENT,
                                 Opcodes.SETNETENT, Opcodes.SETPROTOENT,
                                 Opcodes.SETSERVENT -> {
                                pc = executeSystemOps(opcode, bytecode, pc, registers);
                            }

                            // Group 9: Special I/O (151-154), glob ops, strict deref
                            case Opcodes.TIME_OP -> {
                                int rd = bytecode[pc++];
                                registers[rd] = org.perlonjava.runtime.operators.Time.time();
                            }
                            case Opcodes.WAIT_OP -> {
                                int rd = bytecode[pc++];
                                registers[rd] = org.perlonjava.runtime.operators.WaitpidOperator.waitForChild();
                            }
                            case Opcodes.EVAL_STRING, Opcodes.SELECT_OP, Opcodes.LOAD_GLOB, Opcodes.SLEEP_OP,
                                 Opcodes.ALARM_OP, Opcodes.DEREF_GLOB, Opcodes.DEREF_GLOB_NONSTRICT,
                                 Opcodes.LOAD_GLOB_DYNAMIC, Opcodes.DEREF_SCALAR_STRICT,
                                 Opcodes.DEREF_SCALAR_NONSTRICT, Opcodes.CODE_DEREF_NONSTRICT -> {
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
                                 Opcodes.ABS, Opcodes.BINARY_NOT, Opcodes.BITWISE_NOT, Opcodes.INTEGER_BITWISE_NOT, Opcodes.ORD,
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

                            case Opcodes.SUBSTR_VAR_NO_WARN -> {
                                // substr with variable args, no warning: rd = Operator.substrNoWarn(ctx, args...)
                                // Format: SUBSTR_VAR_NO_WARN rd argsListReg ctx
                                pc = OpcodeHandlerExtended.executeSubstrVarNoWarn(bytecode, pc, registers);
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
                                 Opcodes.SYSTEM, Opcodes.KILL, Opcodes.CALLER, Opcodes.EACH, Opcodes.PACK, Opcodes.UNPACK,
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
                                // Uses cached RuntimeScalar reference to avoid ThreadLocal lookup
                                int nameIdx = bytecode[pc++];
                                currentPackageScalar.set(code.stringPool[nameIdx]);
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

                            case Opcodes.LOCAL_GLOB_DYNAMIC -> {
                                pc = InlineOpcodeHandler.executeLocalGlobDynamic(bytecode, pc, registers);
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

                            // =================================================================
                            // DEFER SUPPORT
                            // =================================================================

                            case Opcodes.PUSH_DEFER -> {
                                pc = InlineOpcodeHandler.executePushDefer(bytecode, pc, registers);
                            }

                            // =================================================================
                            // DEBUGGER SUPPORT
                            // =================================================================

                            case Opcodes.DEBUG -> {
                                // Debug hook at statement boundary
                                // Format: DEBUG file_string_idx line_number site_index
                                int fileIdx = bytecode[pc++];
                                int line = bytecode[pc++];
                                int siteIndex = bytecode[pc++];
                                String file = code.stringPool[fileIdx];
                                DebugHooks.debug(file, line, code, registers, siteIndex);
                            }

                            // =================================================================
                            // SUPEROPERATORS - Combined instruction sequences for performance
                            // =================================================================

                            case Opcodes.HASH_DEREF_FETCH -> {
                                // Combined: DEREF_HASH + LOAD_STRING + HASH_GET
                                // Format: HASH_DEREF_FETCH rd hashref_reg key_string_idx
                                // Equivalent to: $hashref->{key}
                                int rd = bytecode[pc++];
                                int hashrefReg = bytecode[pc++];
                                int keyIdx = bytecode[pc++];

                                RuntimeBase hashrefBase = registers[hashrefReg];

                                // Dereference to get the hash
                                RuntimeHash hash;
                                if (hashrefBase instanceof RuntimeHash) {
                                    hash = (RuntimeHash) hashrefBase;
                                } else {
                                    hash = hashrefBase.scalar().hashDeref();
                                }

                                // Get the element using string key from pool
                                String key = code.stringPool[keyIdx];
                                registers[rd] = hash.get(key);
                            }

                            case Opcodes.ARRAY_DEREF_FETCH -> {
                                // Combined: DEREF_ARRAY + LOAD_INT + ARRAY_GET
                                // Format: ARRAY_DEREF_FETCH rd arrayref_reg index_immediate
                                // Equivalent to: $arrayref->[n]
                                int rd = bytecode[pc++];
                                int arrayrefReg = bytecode[pc++];
                                int index = readInt(bytecode, pc);
                                pc += 1;

                                RuntimeBase arrayrefBase = registers[arrayrefReg];

                                // Dereference to get the array
                                RuntimeArray array;
                                if (arrayrefBase instanceof RuntimeArray) {
                                    array = (RuntimeArray) arrayrefBase;
                                } else {
                                    array = arrayrefBase.scalar().arrayDeref();
                                }

                                // Get the element at index
                                registers[rd] = array.get(index);
                            }

                            case Opcodes.HASH_DEREF_FETCH_NONSTRICT -> {
                                // Combined: DEREF_HASH_NONSTRICT + LOAD_STRING + HASH_GET
                                // Format: HASH_DEREF_FETCH_NONSTRICT rd hashref_reg key_string_idx pkg_string_idx
                                // Equivalent to: $hashref->{key} without strict refs
                                int rd = bytecode[pc++];
                                int hashrefReg = bytecode[pc++];
                                int keyIdx = bytecode[pc++];
                                int pkgIdx = bytecode[pc++];

                                RuntimeBase hashrefBase = registers[hashrefReg];

                                // Dereference to get the hash (non-strict allows symbolic refs)
                                RuntimeHash hash;
                                if (hashrefBase instanceof RuntimeHash) {
                                    hash = (RuntimeHash) hashrefBase;
                                } else {
                                    hash = hashrefBase.scalar().hashDerefNonStrict(code.stringPool[pkgIdx]);
                                }

                                // Get the element using string key from pool
                                String key = code.stringPool[keyIdx];
                                registers[rd] = hash.get(key);
                            }

                            case Opcodes.HASH_DEREF_FETCH_FOR_LOCAL -> {
                                // Like HASH_DEREF_FETCH but returns a RuntimeHashProxyEntry for local() context.
                                // Format: HASH_DEREF_FETCH_FOR_LOCAL rd hashref_reg key_string_idx
                                int rd = bytecode[pc++];
                                int hashrefReg = bytecode[pc++];
                                int keyIdx = bytecode[pc++];

                                RuntimeBase hashrefBase = registers[hashrefReg];

                                RuntimeHash hash;
                                if (hashrefBase instanceof RuntimeHash) {
                                    hash = (RuntimeHash) hashrefBase;
                                } else {
                                    hash = hashrefBase.scalar().hashDeref();
                                }

                                String key = code.stringPool[keyIdx];
                                registers[rd] = hash.getForLocal(key);
                            }

                            case Opcodes.HASH_DEREF_FETCH_NONSTRICT_FOR_LOCAL -> {
                                // Like HASH_DEREF_FETCH_NONSTRICT but returns a RuntimeHashProxyEntry for local() context.
                                // Format: HASH_DEREF_FETCH_NONSTRICT_FOR_LOCAL rd hashref_reg key_string_idx pkg_string_idx
                                int rd = bytecode[pc++];
                                int hashrefReg = bytecode[pc++];
                                int keyIdx = bytecode[pc++];
                                int pkgIdx = bytecode[pc++];

                                RuntimeBase hashrefBase = registers[hashrefReg];

                                RuntimeHash hash;
                                if (hashrefBase instanceof RuntimeHash) {
                                    hash = (RuntimeHash) hashrefBase;
                                } else {
                                    hash = hashrefBase.scalar().hashDerefNonStrict(code.stringPool[pkgIdx]);
                                }

                                String key = code.stringPool[keyIdx];
                                registers[rd] = hash.getForLocal(key);
                            }

                            case Opcodes.ARRAY_DEREF_FETCH_NONSTRICT -> {
                                // Combined: DEREF_ARRAY_NONSTRICT + LOAD_INT + ARRAY_GET
                                // Format: ARRAY_DEREF_FETCH_NONSTRICT rd arrayref_reg index_immediate pkg_string_idx
                                // Equivalent to: $arrayref->[n] without strict refs
                                int rd = bytecode[pc++];
                                int arrayrefReg = bytecode[pc++];
                                int index = readInt(bytecode, pc);
                                pc += 1;
                                int pkgIdx = bytecode[pc++];

                                RuntimeBase arrayrefBase = registers[arrayrefReg];

                                // Dereference to get the array (non-strict allows symbolic refs)
                                RuntimeArray array;
                                if (arrayrefBase instanceof RuntimeArray) {
                                    array = (RuntimeArray) arrayrefBase;
                                } else {
                                    array = arrayrefBase.scalar().arrayDerefNonStrict(code.stringPool[pkgIdx]);
                                }

                                // Get the element at index
                                registers[rd] = array.get(index);
                            }

                            // =================================================================
                            // KV-SLICE DELETE OPERATIONS (390-392)
                            // =================================================================

                            case Opcodes.ARRAY_SLICE_DELETE -> {
                                pc = SlowOpcodeHandler.executeArraySliceDelete(bytecode, pc, registers);
                            }

                            case Opcodes.HASH_SLICE_DELETE_LOCAL -> {
                                pc = SlowOpcodeHandler.executeHashSliceDeleteLocal(bytecode, pc, registers);
                            }

                            case Opcodes.ARRAY_SLICE_DELETE_LOCAL -> {
                                pc = SlowOpcodeHandler.executeArraySliceDeleteLocal(bytecode, pc, registers);
                            }

                            case Opcodes.HASH_KV_SLICE_DELETE -> {
                                pc = SlowOpcodeHandler.executeHashKVSliceDelete(bytecode, pc, registers);
                            }

                            case Opcodes.ARRAY_KV_SLICE_DELETE -> {
                                pc = SlowOpcodeHandler.executeArrayKVSliceDelete(bytecode, pc, registers);
                            }

                            case Opcodes.DISPATCH_VAR_ATTRS -> {
                                pc = SlowOpcodeHandler.executeDispatchVarAttrs(bytecode, pc, registers, code.constants);
                            }

                            case Opcodes.VIVIFY_LVALUE -> {
                                // Vivify an lvalue proxy so the entry exists in the parent container.
                                // For plain scalars this is a no-op.
                                int reg = bytecode[pc++];
                                RuntimeBase val = registers[reg];
                                if (val instanceof RuntimeScalar rs) {
                                    rs.vivifyLvalue();
                                }
                            }

                            case Opcodes.LIST_SLICE -> {
                                // List slice: rd = list.getSlice(indices)
                                // Used for (list)[indices] syntax
                                int rd = bytecode[pc++];
                                int listReg = bytecode[pc++];
                                int indicesReg = bytecode[pc++];
                                RuntimeList list = registers[listReg].getList();
                                RuntimeList indices = registers[indicesReg].getList();
                                registers[rd] = list.getSlice(indices);
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
                        // Restore local variables pushed inside the eval block
                        if (!evalLocalLevelStack.isEmpty()) {
                            int savedLevel = evalLocalLevelStack.pop();
                            DynamicVariableManager.popToLocalLevel(savedLevel);
                        }
                        RuntimeCode.evalDepth--;
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
                    propagatingException = e;
                    throw new RuntimeException(formatInterpreterError(code, errorPc, new Exception(errorMessage)), e);
                } catch (PerlExitException e) {
                    // exit() should NEVER be caught by eval{} - always propagate
                    propagatingException = e;
                    throw e;
                } catch (Throwable e) {
                    // Check if we're inside an eval block
                    if (!evalCatchStack.isEmpty()) {
                        // Inside eval block - catch the exception
                        int catchPc = evalCatchStack.pop(); // Pop the catch handler

                        // Scope exit cleanup for lexical variables allocated inside the eval body.
                        // When die throws a PerlDieException, the SCOPE_EXIT_CLEANUP opcodes
                        // between the throw site and the eval boundary are skipped. This loop
                        // ensures DESTROY fires for blessed objects that went out of scope.
                        if (!evalBaseRegStack.isEmpty()) {
                            int baseReg = evalBaseRegStack.pop();
                            boolean needsFlush = false;
                            for (int i = baseReg; i < registers.length; i++) {
                                RuntimeBase reg = registers[i];
                                if (reg == null) continue;
                                if (reg instanceof RuntimeScalar rs) {
                                    RuntimeScalar.scopeExitCleanup(rs);
                                    needsFlush = true;
                                } else if (reg instanceof RuntimeHash rh) {
                                    MortalList.scopeExitCleanupHash(rh);
                                    needsFlush = true;
                                } else if (reg instanceof RuntimeArray ra) {
                                    MortalList.scopeExitCleanupArray(ra);
                                    needsFlush = true;
                                }
                                registers[i] = null;
                            }
                            if (needsFlush) {
                                MortalList.flush();
                            }
                        }

                        // Restore local variables pushed inside the eval block
                        if (!evalLocalLevelStack.isEmpty()) {
                            int savedLevel = evalLocalLevelStack.pop();
                            DynamicVariableManager.popToLocalLevel(savedLevel);
                        }

                        // Track eval depth for $^S
                        RuntimeCode.evalDepth--;

                        // Call WarnDie.catchEval() to set $@
                        WarnDie.catchEval(e);

                        pc = catchPc;
                        continue outer;
                    }

                    // Not in eval block - propagate exception
                    // Re-throw RuntimeExceptions as-is (includes PerlDieException)
                    propagatingException = e;
                    if (e instanceof RuntimeException re) {
                        throw re;
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
            // Release captures for interpreter closures created in this frame
            // that were never stored via set() (refCount stayed at 0).
            // This handles eval STRING map/grep block closures that over-capture
            // all visible variables but are temporary and should release captures.
            // Closures stored via set() have refCount > 0 and are skipped.
            // This matches the JVM-compiled path where scopeExitCleanup releases
            // captures for CODE refs with refCount=0 (see RuntimeScalar.java
            // scopeExitCleanup special case for CODE refs).
            if (createdClosures != null) {
                for (RuntimeCode closure : createdClosures) {
                    if (closure.capturedScalars != null
                            && closure.refCount == 0
                            && closure.stashRefCount <= 0) {
                        closure.releaseCaptures();
                    }
                }
            }

            // Scope-exit cleanup for my-variables when an exception propagates out
            // of this subroutine frame without being caught by an eval.
            // This ensures DESTROY fires for blessed objects going out of scope
            // during die unwinding (e.g. TxnScopeGuard in a sub called from eval).
            if (propagatingException != null) {
                // Only clean up registers that are actual "my" variables.
                // Temporary registers may alias hash/array elements (via HASH_GET,
                // HASH_DEREF_FETCH, etc.) and calling scopeExitCleanup on them
                // would incorrectly decrement refCounts, causing premature DESTROY.
                BitSet myVars = code.myVarRegisters;
                boolean needsFlush = false;
                for (int i = myVars.nextSetBit(firstMyVarReg); i >= 0; i = myVars.nextSetBit(i + 1)) {
                    RuntimeBase reg = registers[i];
                    if (reg == null) continue;
                    if (reg instanceof RuntimeScalar rs) {
                        RuntimeScalar.scopeExitCleanup(rs);
                        needsFlush = true;
                    } else if (reg instanceof RuntimeHash rh) {
                        MortalList.scopeExitCleanupHash(rh);
                        needsFlush = true;
                    } else if (reg instanceof RuntimeArray ra) {
                        MortalList.scopeExitCleanupArray(ra);
                        needsFlush = true;
                    }
                    registers[i] = null;
                }
                if (needsFlush) {
                    MortalList.flush();
                }
            }

            // Outer finally: restore interpreter state saved at method entry.
            // Unwinds all `local` variables pushed during this frame, restores
            // the current package, and pops the InterpreterState call stack.
            if (usesLocalization) {
                DynamicVariableManager.popToLocalLevel(savedLocalLevel);
            }
            currentPackageScalar.set(savedPackage);
            InterpreterState.pop();
            // Release cached registers for reuse
            code.releaseRegisters();
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
                registers[rd] = CompareOperators.eq(s1, s2);
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
                registers[rd] = CompareOperators.ne(s1, s2);
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
     * Handles: DEFINED, DEFINED_GLOB, REF, BLESS, ISA, PROTOTYPE, QUOTE_REGEX
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
            case Opcodes.DEFINED_CODE -> {
                // defined(&name) - check stash via definedGlobalCodeRefAsScalar
                // This does a fresh stash lookup, matching the JVM backend and Perl 5
                // behavior where eval("defined(&name)") checks the stash, not cached CVs.
                int rd = bytecode[pc++];
                int nameIdx = bytecode[pc++];
                String name = code.stringPool[nameIdx];
                registers[rd] = GlobalVariable.definedGlobalCodeRefAsScalar(name);
                return pc;
            }
            case Opcodes.DEFINED_GLOB -> {
                // defined *$var - check if glob is defined without throwing strict refs
                // Format: DEFINED_GLOB rd scalar_reg pkg_string_idx
                int rd = bytecode[pc++];
                int scalarReg = bytecode[pc++];
                int pkgIdx = bytecode[pc++];
                String pkg = code.stringPool[pkgIdx];
                RuntimeScalar scalar = registers[scalarReg].scalar();
                registers[rd] = GlobalVariable.definedGlob(scalar, pkg);
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
            case Opcodes.SMARTMATCH -> {
                int rd = bytecode[pc++];
                int rs1 = bytecode[pc++];
                int rs2 = bytecode[pc++];
                registers[rd] = CompareOperators.smartmatch(registers[rs1].scalar(), registers[rs2].scalar());
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
            case Opcodes.QUOTE_REGEX_O -> {
                int rd = bytecode[pc++];
                int patternReg = bytecode[pc++];
                int flagsReg = bytecode[pc++];
                int callsiteId = bytecode[pc++];
                registers[rd] = RuntimeRegex.getQuotedRegex(registers[patternReg].scalar(), registers[flagsReg].scalar(), callsiteId);
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

                GlobalRuntimeArray.makeLocal(fullName);
                registers[rd] = GlobalVariable.getGlobalArray(fullName);
                return pc;
            }
            case Opcodes.LOCAL_HASH -> {
                int rd = bytecode[pc++];
                int nameIdx = bytecode[pc++];
                String fullName = code.stringPool[nameIdx];

                GlobalRuntimeHash.makeLocal(fullName);
                registers[rd] = GlobalVariable.getGlobalHash(fullName);
                return pc;
            }
            case Opcodes.STATE_INIT_SCALAR -> {
                // Format: STATE_INIT_SCALAR rd value_reg name_idx persist_id
                // Retrieves the persistent state variable (non-destructive) and
                // conditionally assigns value only on first initialization
                int rd = bytecode[pc++];
                int valueReg = bytecode[pc++];
                int nameIdx = bytecode[pc++];
                int persistId = bytecode[pc++];
                String varName = code.stringPool[nameIdx];
                // Use undef codeRef for top-level state (interpreter fallback context)
                RuntimeScalar codeRef = new RuntimeScalar();
                // Retrieve without removing (unlike RETRIEVE_BEGIN_SCALAR)
                RuntimeScalar stateVar = StateVariable.retrieveStateScalar(codeRef, varName, persistId);
                registers[rd] = stateVar;
                RuntimeScalar isInit = StateVariable.isInitializedStateVariable(codeRef, varName, persistId);
                if (!isInit.getBoolean()) {
                    stateVar.set((RuntimeScalar) registers[valueReg]);
                    StateVariable.markInitializedStateVariable(codeRef, varName, persistId);
                }
                return pc;
            }
            case Opcodes.STATE_INIT_ARRAY -> {
                int rd = bytecode[pc++];
                int valueReg = bytecode[pc++];
                int nameIdx = bytecode[pc++];
                int persistId = bytecode[pc++];
                String varName = code.stringPool[nameIdx];
                RuntimeScalar codeRef = new RuntimeScalar();
                RuntimeArray stateArr = StateVariable.retrieveStateArray(codeRef, varName, persistId);
                registers[rd] = stateArr;
                RuntimeScalar isInit = StateVariable.isInitializedStateVariable(codeRef, varName, persistId);
                if (!isInit.getBoolean()) {
                    stateArr.setFromList(((RuntimeBase) registers[valueReg]).getList());
                    StateVariable.markInitializedStateVariable(codeRef, varName, persistId);
                }
                return pc;
            }
            case Opcodes.STATE_INIT_HASH -> {
                int rd = bytecode[pc++];
                int valueReg = bytecode[pc++];
                int nameIdx = bytecode[pc++];
                int persistId = bytecode[pc++];
                String varName = code.stringPool[nameIdx];
                RuntimeScalar codeRef = new RuntimeScalar();
                RuntimeHash stateHash = StateVariable.retrieveStateHash(codeRef, varName, persistId);
                registers[rd] = stateHash;
                RuntimeScalar isInit = StateVariable.isInitializedStateVariable(codeRef, varName, persistId);
                if (!isInit.getBoolean()) {
                    stateHash.setFromList(((RuntimeBase) registers[valueReg]).getList());
                    StateVariable.markInitializedStateVariable(codeRef, varName, persistId);
                }
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
            case Opcodes.SYMLINK -> {
                return MiscOpcodeHandler.execute(Opcodes.SYMLINK, bytecode, pc, registers);
            }
            case Opcodes.CHROOT -> {
                return MiscOpcodeHandler.execute(Opcodes.CHROOT, bytecode, pc, registers);
            }
            case Opcodes.MKDIR -> {
                return MiscOpcodeHandler.execute(Opcodes.MKDIR, bytecode, pc, registers);
            }
            case Opcodes.MSGCTL -> {
                return MiscOpcodeHandler.execute(Opcodes.MSGCTL, bytecode, pc, registers);
            }
            case Opcodes.SHMCTL -> {
                return MiscOpcodeHandler.execute(Opcodes.SHMCTL, bytecode, pc, registers);
            }
            case Opcodes.SEMCTL -> {
                return MiscOpcodeHandler.execute(Opcodes.SEMCTL, bytecode, pc, registers);
            }
            case Opcodes.EXEC -> {
                return MiscOpcodeHandler.execute(Opcodes.EXEC, bytecode, pc, registers);
            }
            case Opcodes.FCNTL -> {
                return MiscOpcodeHandler.execute(Opcodes.FCNTL, bytecode, pc, registers);
            }
            case Opcodes.IOCTL -> {
                return MiscOpcodeHandler.execute(Opcodes.IOCTL, bytecode, pc, registers);
            }
            case Opcodes.GETPWENT -> {
                return MiscOpcodeHandler.execute(Opcodes.GETPWENT, bytecode, pc, registers);
            }
            case Opcodes.SETPWENT -> {
                return MiscOpcodeHandler.execute(Opcodes.SETPWENT, bytecode, pc, registers);
            }
            case Opcodes.ENDPWENT -> {
                return MiscOpcodeHandler.execute(Opcodes.ENDPWENT, bytecode, pc, registers);
            }
            case Opcodes.GETLOGIN -> {
                return MiscOpcodeHandler.execute(Opcodes.GETLOGIN, bytecode, pc, registers);
            }
            case Opcodes.GETPWNAM -> {
                return MiscOpcodeHandler.execute(Opcodes.GETPWNAM, bytecode, pc, registers);
            }
            case Opcodes.GETPWUID -> {
                return MiscOpcodeHandler.execute(Opcodes.GETPWUID, bytecode, pc, registers);
            }
            case Opcodes.GETGRNAM -> {
                return MiscOpcodeHandler.execute(Opcodes.GETGRNAM, bytecode, pc, registers);
            }
            case Opcodes.GETGRGID -> {
                return MiscOpcodeHandler.execute(Opcodes.GETGRGID, bytecode, pc, registers);
            }
            case Opcodes.GETGRENT -> {
                return MiscOpcodeHandler.execute(Opcodes.GETGRENT, bytecode, pc, registers);
            }
            case Opcodes.SETGRENT -> {
                return MiscOpcodeHandler.execute(Opcodes.SETGRENT, bytecode, pc, registers);
            }
            case Opcodes.ENDGRENT -> {
                return MiscOpcodeHandler.execute(Opcodes.ENDGRENT, bytecode, pc, registers);
            }
            case Opcodes.GETHOSTBYADDR -> {
                return MiscOpcodeHandler.execute(Opcodes.GETHOSTBYADDR, bytecode, pc, registers);
            }
            case Opcodes.GETSERVBYNAME -> {
                return MiscOpcodeHandler.execute(Opcodes.GETSERVBYNAME, bytecode, pc, registers);
            }
            case Opcodes.GETSERVBYPORT -> {
                return MiscOpcodeHandler.execute(Opcodes.GETSERVBYPORT, bytecode, pc, registers);
            }
            case Opcodes.GETPROTOBYNAME -> {
                return MiscOpcodeHandler.execute(Opcodes.GETPROTOBYNAME, bytecode, pc, registers);
            }
            case Opcodes.GETPROTOBYNUMBER -> {
                return MiscOpcodeHandler.execute(Opcodes.GETPROTOBYNUMBER, bytecode, pc, registers);
            }
            case Opcodes.ENDHOSTENT -> {
                return MiscOpcodeHandler.execute(Opcodes.ENDHOSTENT, bytecode, pc, registers);
            }
            case Opcodes.ENDNETENT -> {
                return MiscOpcodeHandler.execute(Opcodes.ENDNETENT, bytecode, pc, registers);
            }
            case Opcodes.ENDPROTOENT -> {
                return MiscOpcodeHandler.execute(Opcodes.ENDPROTOENT, bytecode, pc, registers);
            }
            case Opcodes.ENDSERVENT -> {
                return MiscOpcodeHandler.execute(Opcodes.ENDSERVENT, bytecode, pc, registers);
            }
            case Opcodes.GETHOSTENT -> {
                return MiscOpcodeHandler.execute(Opcodes.GETHOSTENT, bytecode, pc, registers);
            }
            case Opcodes.GETNETBYADDR -> {
                return MiscOpcodeHandler.execute(Opcodes.GETNETBYADDR, bytecode, pc, registers);
            }
            case Opcodes.GETNETBYNAME -> {
                return MiscOpcodeHandler.execute(Opcodes.GETNETBYNAME, bytecode, pc, registers);
            }
            case Opcodes.GETNETENT -> {
                return MiscOpcodeHandler.execute(Opcodes.GETNETENT, bytecode, pc, registers);
            }
            case Opcodes.GETPROTOENT -> {
                return MiscOpcodeHandler.execute(Opcodes.GETPROTOENT, bytecode, pc, registers);
            }
            case Opcodes.GETSERVENT -> {
                return MiscOpcodeHandler.execute(Opcodes.GETSERVENT, bytecode, pc, registers);
            }
            case Opcodes.SETHOSTENT -> {
                return MiscOpcodeHandler.execute(Opcodes.SETHOSTENT, bytecode, pc, registers);
            }
            case Opcodes.SETNETENT -> {
                return MiscOpcodeHandler.execute(Opcodes.SETNETENT, bytecode, pc, registers);
            }
            case Opcodes.SETPROTOENT -> {
                return MiscOpcodeHandler.execute(Opcodes.SETPROTOENT, bytecode, pc, registers);
            }
            case Opcodes.SETSERVENT -> {
                return MiscOpcodeHandler.execute(Opcodes.SETSERVENT, bytecode, pc, registers);
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
            case Opcodes.CODE_DEREF_NONSTRICT -> {
                return SlowOpcodeHandler.executeCodeDerefNonStrict(bytecode, pc, registers, code);
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
     * Get call site information (package, filename, line) for the given bytecode PC.
     * Used to push caller context before subroutine/method calls.
     *
     * @param code      The InterpretedCode containing the bytecode
     * @param callPc    The PC of the call instruction (opcode position, before operands)
     * @param currentPkg The current package name
     * @return CallerStack.CallerInfo with package, filename, and line number
     */
    private static CallerStack.CallerInfo getCallSiteInfo(InterpretedCode code, int callPc, String currentPkg) {
        String filename = code.sourceName;
        int lineNumber = code.sourceLine;

        // Try to get token index from pcToTokenIndex map
        if (code.pcToTokenIndex != null && !code.pcToTokenIndex.isEmpty()) {
            var entry = code.pcToTokenIndex.floorEntry(callPc);
            if (entry != null && code.errorUtil != null) {
                int tokenIndex = entry.getValue();
                ErrorMessageUtil.SourceLocation loc = code.errorUtil.getSourceLocationAccurate(tokenIndex);
                filename = loc.fileName();
                lineNumber = loc.lineNumber();
            }
        }

        return new CallerStack.CallerInfo(currentPkg, filename, lineNumber);
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
