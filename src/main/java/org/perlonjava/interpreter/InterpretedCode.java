package org.perlonjava.interpreter;

import org.perlonjava.runtime.*;
import java.util.Map;

/**
 * Interpreted bytecode that extends RuntimeCode.
 *
 * This class represents Perl code that is interpreted rather than compiled to JVM bytecode.
 * It is COMPLETELY INDISTINGUISHABLE from compiled RuntimeCode to the rest of the system:
 * - Can be stored in global variables ($::func)
 * - Can be passed as code references
 * - Can capture variables (closures work both directions)
 * - Can be used in method dispatch, overload, @ISA, etc.
 *
 * The ONLY difference is the execution engine:
 * - Compiled RuntimeCode uses MethodHandle to invoke JVM bytecode
 * - InterpretedCode overrides apply() to dispatch to BytecodeInterpreter
 */
public class InterpretedCode extends RuntimeCode {
    // Bytecode and metadata
    public final byte[] bytecode;          // Instruction opcodes (compact)
    public final Object[] constants;       // Constant pool (RuntimeBase objects)
    public final String[] stringPool;      // String constants (variable names, etc.)
    public final int maxRegisters;         // Number of registers needed
    public final RuntimeBase[] capturedVars; // Closure support (captured from outer scope)
    public final Map<String, Integer> variableRegistry; // Variable name → register index (for eval STRING)

    // Debug information (optional)
    public final String sourceName;        // Source file name (for stack traces)
    public final int sourceLine;           // Source line number
    public final java.util.Map<Integer, Integer> pcToTokenIndex;  // Map bytecode PC to tokenIndex for error reporting

    /**
     * Constructor for InterpretedCode.
     *
     * @param bytecode      The bytecode instructions
     * @param constants     Constant pool (RuntimeBase objects)
     * @param stringPool    String constants (variable names, etc.)
     * @param maxRegisters  Number of registers needed for execution
     * @param capturedVars  Captured variables for closure support (may be null)
     * @param sourceName    Source file name for debugging
     * @param sourceLine    Source line number for debugging
     * @param pcToTokenIndex Map from bytecode PC to AST tokenIndex for error reporting
     * @param variableRegistry Variable name → register index mapping (for eval STRING)
     */
    public InterpretedCode(byte[] bytecode, Object[] constants, String[] stringPool,
                          int maxRegisters, RuntimeBase[] capturedVars,
                          String sourceName, int sourceLine,
                          java.util.Map<Integer, Integer> pcToTokenIndex,
                          Map<String, Integer> variableRegistry) {
        super(null, new java.util.ArrayList<>()); // Call RuntimeCode constructor with null prototype, empty attributes
        this.bytecode = bytecode;
        this.constants = constants;
        this.stringPool = stringPool;
        this.maxRegisters = maxRegisters;
        this.capturedVars = capturedVars;
        this.sourceName = sourceName;
        this.sourceLine = sourceLine;
        this.pcToTokenIndex = pcToTokenIndex;
        this.variableRegistry = variableRegistry;
    }

    // Legacy constructor for backward compatibility
    public InterpretedCode(byte[] bytecode, Object[] constants, String[] stringPool,
                          int maxRegisters, RuntimeBase[] capturedVars,
                          String sourceName, int sourceLine,
                          java.util.Map<Integer, Integer> pcToTokenIndex) {
        this(bytecode, constants, stringPool, maxRegisters, capturedVars,
             sourceName, sourceLine, pcToTokenIndex, null);
    }

    /**
     * Override RuntimeCode.apply() to dispatch to interpreter.
     *
     * This is the ONLY method that differs from compiled RuntimeCode.
     * The API signature is IDENTICAL, ensuring perfect compatibility.
     *
     * @param args        The arguments array (@_)
     * @param callContext The calling context (VOID/SCALAR/LIST)
     * @return RuntimeList containing the result (may be RuntimeControlFlowList)
     */
    @Override
    public RuntimeList apply(RuntimeArray args, int callContext) {
        // Dispatch to interpreter (not compiled bytecode)
        return BytecodeInterpreter.execute(this, args, callContext);
    }

    /**
     * Override RuntimeCode.apply() with subroutine name.
     *
     * @param subroutineName The subroutine name (for stack traces)
     * @param args          The arguments array (@_)
     * @param callContext   The calling context
     * @return RuntimeList containing the result
     */
    @Override
    public RuntimeList apply(String subroutineName, RuntimeArray args, int callContext) {
        // Dispatch to interpreter with subroutine name for stack traces
        return BytecodeInterpreter.execute(this, args, callContext, subroutineName);
    }

    /**
     * Override RuntimeCode.defined() to return true for InterpretedCode.
     * InterpretedCode doesn't use methodHandle, so the parent defined() check fails.
     * But InterpretedCode instances are always "defined" - they contain executable bytecode.
     */
    @Override
    public boolean defined() {
        return true;
    }

    /**
     * Create an InterpretedCode with captured variables (for closures).
     *
     * @param capturedVars The variables to capture from outer scope
     * @return A new InterpretedCode with captured variables
     */
    public InterpretedCode withCapturedVars(RuntimeBase[] capturedVars) {
        return new InterpretedCode(
            this.bytecode,
            this.constants,
            this.stringPool,
            this.maxRegisters,
            capturedVars,  // New captured vars
            this.sourceName,
            this.sourceLine,
            this.pcToTokenIndex,  // Preserve token index map
            this.variableRegistry  // Preserve variable registry
        );
    }

    /**
     * Register this InterpretedCode as a global named subroutine.
     * This allows compiled code to call interpreted closures seamlessly.
     *
     * @param name Subroutine name (e.g., "main::closure_123")
     * @return RuntimeScalar CODE reference to this InterpretedCode
     */
    public RuntimeScalar registerAsNamedSub(String name) {
        // Extract package and sub name
        int lastColonIndex = name.lastIndexOf("::");
        if (lastColonIndex > 0) {
            this.packageName = name.substring(0, lastColonIndex);
            this.subName = name.substring(lastColonIndex + 2);
        } else {
            this.packageName = "main";
            this.subName = name;
        }

        // Register in global code refs (creates or gets existing RuntimeScalar)
        // Then set its value to this InterpretedCode
        RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef(name);
        codeRef.set(new RuntimeScalar(this));

        return codeRef;
    }

    /**
     * Get a human-readable representation for debugging.
     */
    @Override
    public String toString() {
        return "InterpretedCode{" +
               "sourceName='" + sourceName + '\'' +
               ", sourceLine=" + sourceLine +
               ", bytecode.length=" + bytecode.length +
               ", maxRegisters=" + maxRegisters +
               ", hasCapturedVars=" + (capturedVars != null && capturedVars.length > 0) +
               '}';
    }

    /**
     * Disassemble bytecode for debugging and optimization analysis.
     */
    public String disassemble() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Bytecode Disassembly ===\n");
        sb.append("Source: ").append(sourceName).append(":").append(sourceLine).append("\n");
        sb.append("Registers: ").append(maxRegisters).append("\n");
        sb.append("Bytecode length: ").append(bytecode.length).append(" bytes\n\n");

        int pc = 0;
        while (pc < bytecode.length) {
            int startPc = pc;
            byte opcode = bytecode[pc++];
            sb.append(String.format("%4d: ", startPc));

            switch (opcode) {
                case Opcodes.NOP:
                    sb.append("NOP\n");
                    break;
                case Opcodes.RETURN:
                    sb.append("RETURN r").append(bytecode[pc++] & 0xFF).append("\n");
                    break;
                case Opcodes.GOTO:
                    sb.append("GOTO ").append(readInt(bytecode, pc)).append("\n");
                    pc += 4;
                    break;
                case Opcodes.GOTO_IF_FALSE:
                    int condReg = bytecode[pc++] & 0xFF;
                    int target = readInt(bytecode, pc);
                    pc += 4;
                    sb.append("GOTO_IF_FALSE r").append(condReg).append(" -> ").append(target).append("\n");
                    break;
                case Opcodes.GOTO_IF_TRUE:
                    condReg = bytecode[pc++] & 0xFF;
                    target = readInt(bytecode, pc);
                    pc += 4;
                    sb.append("GOTO_IF_TRUE r").append(condReg).append(" -> ").append(target).append("\n");
                    break;
                case Opcodes.MOVE:
                    int dest = bytecode[pc++] & 0xFF;
                    int src = bytecode[pc++] & 0xFF;
                    sb.append("MOVE r").append(dest).append(" = r").append(src).append("\n");
                    break;
                case Opcodes.LOAD_CONST:
                    int rd = bytecode[pc++] & 0xFF;
                    int constIdx = bytecode[pc++] & 0xFF;
                    sb.append("LOAD_CONST r").append(rd).append(" = constants[").append(constIdx).append("]");
                    if (constants != null && constIdx < constants.length) {
                        Object obj = constants[constIdx];
                        sb.append(" (");
                        if (obj instanceof RuntimeScalar) {
                            RuntimeScalar scalar = (RuntimeScalar) obj;
                            sb.append("RuntimeScalar{type=").append(scalar.type).append(", value=").append(scalar.value.getClass().getSimpleName()).append("}");
                        } else {
                            sb.append(obj);
                        }
                        sb.append(")");
                    }
                    sb.append("\n");
                    break;
                case Opcodes.LOAD_INT:
                    rd = bytecode[pc++] & 0xFF;
                    int value = readInt(bytecode, pc);
                    pc += 4;
                    sb.append("LOAD_INT r").append(rd).append(" = ").append(value).append("\n");
                    break;
                case Opcodes.LOAD_STRING:
                    rd = bytecode[pc++] & 0xFF;
                    int strIdx = bytecode[pc++] & 0xFF;
                    sb.append("LOAD_STRING r").append(rd).append(" = \"");
                    if (stringPool != null && strIdx < stringPool.length) {
                        String str = stringPool[strIdx];
                        // Escape special characters for readability
                        str = str.replace("\\", "\\\\")
                                 .replace("\n", "\\n")
                                 .replace("\r", "\\r")
                                 .replace("\t", "\\t")
                                 .replace("\"", "\\\"");
                        sb.append(str);
                    }
                    sb.append("\"\n");
                    break;
                case Opcodes.LOAD_UNDEF:
                    rd = bytecode[pc++] & 0xFF;
                    sb.append("LOAD_UNDEF r").append(rd).append("\n");
                    break;
                case Opcodes.LOAD_GLOBAL_SCALAR:
                    rd = bytecode[pc++] & 0xFF;
                    int nameIdx = bytecode[pc++] & 0xFF;
                    sb.append("LOAD_GLOBAL_SCALAR r").append(rd).append(" = $").append(stringPool[nameIdx]).append("\n");
                    break;
                case Opcodes.STORE_GLOBAL_SCALAR:
                    nameIdx = bytecode[pc++] & 0xFF;
                    int srcReg = bytecode[pc++] & 0xFF;
                    sb.append("STORE_GLOBAL_SCALAR $").append(stringPool[nameIdx]).append(" = r").append(srcReg).append("\n");
                    break;
                case Opcodes.ADD_SCALAR:
                    rd = bytecode[pc++] & 0xFF;
                    int rs1 = bytecode[pc++] & 0xFF;
                    int rs2 = bytecode[pc++] & 0xFF;
                    sb.append("ADD_SCALAR r").append(rd).append(" = r").append(rs1).append(" + r").append(rs2).append("\n");
                    break;
                case Opcodes.ADD_SCALAR_INT:
                    rd = bytecode[pc++] & 0xFF;
                    int rs = bytecode[pc++] & 0xFF;
                    int imm = readInt(bytecode, pc);
                    pc += 4;
                    sb.append("ADD_SCALAR_INT r").append(rd).append(" = r").append(rs).append(" + ").append(imm).append("\n");
                    break;
                case Opcodes.LT_NUM:
                    rd = bytecode[pc++] & 0xFF;
                    rs1 = bytecode[pc++] & 0xFF;
                    rs2 = bytecode[pc++] & 0xFF;
                    sb.append("LT_NUM r").append(rd).append(" = r").append(rs1).append(" < r").append(rs2).append("\n");
                    break;
                case Opcodes.GT_NUM:
                    rd = bytecode[pc++] & 0xFF;
                    rs1 = bytecode[pc++] & 0xFF;
                    rs2 = bytecode[pc++] & 0xFF;
                    sb.append("GT_NUM r").append(rd).append(" = r").append(rs1).append(" > r").append(rs2).append("\n");
                    break;
                case Opcodes.NE_NUM:
                    rd = bytecode[pc++] & 0xFF;
                    rs1 = bytecode[pc++] & 0xFF;
                    rs2 = bytecode[pc++] & 0xFF;
                    sb.append("NE_NUM r").append(rd).append(" = r").append(rs1).append(" != r").append(rs2).append("\n");
                    break;
                case Opcodes.INC_REG:
                    rd = bytecode[pc++] & 0xFF;
                    sb.append("INC_REG r").append(rd).append("++\n");
                    break;
                case Opcodes.DEC_REG:
                    rd = bytecode[pc++] & 0xFF;
                    sb.append("DEC_REG r").append(rd).append("--\n");
                    break;
                case Opcodes.ADD_ASSIGN:
                    rd = bytecode[pc++] & 0xFF;
                    rs = bytecode[pc++] & 0xFF;
                    sb.append("ADD_ASSIGN r").append(rd).append(" += r").append(rs).append("\n");
                    break;
                case Opcodes.ADD_ASSIGN_INT:
                    rd = bytecode[pc++] & 0xFF;
                    imm = readInt(bytecode, pc);
                    pc += 4;
                    sb.append("ADD_ASSIGN_INT r").append(rd).append(" += ").append(imm).append("\n");
                    break;
                case Opcodes.PRE_AUTOINCREMENT:
                    rd = bytecode[pc++] & 0xFF;
                    sb.append("PRE_AUTOINCREMENT ++r").append(rd).append("\n");
                    break;
                case Opcodes.POST_AUTOINCREMENT:
                    rd = bytecode[pc++] & 0xFF;
                    sb.append("POST_AUTOINCREMENT r").append(rd).append("++\n");
                    break;
                case Opcodes.PRE_AUTODECREMENT:
                    rd = bytecode[pc++] & 0xFF;
                    sb.append("PRE_AUTODECREMENT --r").append(rd).append("\n");
                    break;
                case Opcodes.POST_AUTODECREMENT:
                    rd = bytecode[pc++] & 0xFF;
                    sb.append("POST_AUTODECREMENT r").append(rd).append("--\n");
                    break;
                case Opcodes.PRINT: {
                    int contentReg = bytecode[pc++] & 0xFF;
                    int filehandleReg = bytecode[pc++] & 0xFF;
                    sb.append("PRINT r").append(contentReg).append(", fh=r").append(filehandleReg).append("\n");
                    break;
                }
                case Opcodes.SAY: {
                    int contentReg = bytecode[pc++] & 0xFF;
                    int filehandleReg = bytecode[pc++] & 0xFF;
                    sb.append("SAY r").append(contentReg).append(", fh=r").append(filehandleReg).append("\n");
                    break;
                }
                case Opcodes.CREATE_REF:
                    rd = bytecode[pc++] & 0xFF;
                    rs = bytecode[pc++] & 0xFF;
                    sb.append("CREATE_REF r").append(rd).append(" = \\r").append(rs).append("\n");
                    break;
                case Opcodes.DEREF:
                    rd = bytecode[pc++] & 0xFF;
                    rs = bytecode[pc++] & 0xFF;
                    sb.append("DEREF r").append(rd).append(" = ${r").append(rs).append("}\n");
                    break;
                case Opcodes.GET_TYPE:
                    rd = bytecode[pc++] & 0xFF;
                    rs = bytecode[pc++] & 0xFF;
                    sb.append("GET_TYPE r").append(rd).append(" = type(r").append(rs).append(")\n");
                    break;
                case Opcodes.DIE:
                    rs = bytecode[pc++] & 0xFF;
                    sb.append("DIE r").append(rs).append("\n");
                    break;
                case Opcodes.WARN:
                    rs = bytecode[pc++] & 0xFF;
                    sb.append("WARN r").append(rs).append("\n");
                    break;
                case Opcodes.EVAL_TRY: {
                    int catchOffsetHigh = bytecode[pc++] & 0xFF;
                    int catchOffsetLow = bytecode[pc++] & 0xFF;
                    int catchOffset = (catchOffsetHigh << 8) | catchOffsetLow;
                    int tryPc = pc - 3;
                    int catchPc = tryPc + catchOffset;
                    sb.append("EVAL_TRY catch_at=").append(catchPc).append("\n");
                    break;
                }
                case Opcodes.EVAL_END:
                    sb.append("EVAL_END\n");
                    break;
                case Opcodes.EVAL_CATCH:
                    rd = bytecode[pc++] & 0xFF;
                    sb.append("EVAL_CATCH r").append(rd).append("\n");
                    break;
                case Opcodes.ARRAY_GET:
                    rd = bytecode[pc++] & 0xFF;
                    int arrayReg = bytecode[pc++] & 0xFF;
                    int indexReg = bytecode[pc++] & 0xFF;
                    sb.append("ARRAY_GET r").append(rd).append(" = r").append(arrayReg).append("[r").append(indexReg).append("]\n");
                    break;
                case Opcodes.ARRAY_SIZE:
                    rd = bytecode[pc++] & 0xFF;
                    arrayReg = bytecode[pc++] & 0xFF;
                    sb.append("ARRAY_SIZE r").append(rd).append(" = size(r").append(arrayReg).append(")\n");
                    break;
                case Opcodes.CREATE_ARRAY:
                    rd = bytecode[pc++] & 0xFF;
                    int listSourceReg = bytecode[pc++] & 0xFF;
                    sb.append("CREATE_ARRAY r").append(rd).append(" = array(r").append(listSourceReg).append(")\n");
                    break;
                case Opcodes.HASH_GET:
                    rd = bytecode[pc++] & 0xFF;
                    int hashGetReg = bytecode[pc++] & 0xFF;
                    int keyGetReg = bytecode[pc++] & 0xFF;
                    sb.append("HASH_GET r").append(rd).append(" = r").append(hashGetReg).append("{r").append(keyGetReg).append("}\n");
                    break;
                case Opcodes.HASH_SET:
                    int hashSetReg = bytecode[pc++] & 0xFF;
                    int keySetReg = bytecode[pc++] & 0xFF;
                    int valueSetReg = bytecode[pc++] & 0xFF;
                    sb.append("HASH_SET r").append(hashSetReg).append("{r").append(keySetReg).append("} = r").append(valueSetReg).append("\n");
                    break;
                case Opcodes.HASH_EXISTS:
                    rd = bytecode[pc++] & 0xFF;
                    int hashExistsReg = bytecode[pc++] & 0xFF;
                    int keyExistsReg = bytecode[pc++] & 0xFF;
                    sb.append("HASH_EXISTS r").append(rd).append(" = exists r").append(hashExistsReg).append("{r").append(keyExistsReg).append("}\n");
                    break;
                case Opcodes.HASH_DELETE:
                    rd = bytecode[pc++] & 0xFF;
                    int hashDeleteReg = bytecode[pc++] & 0xFF;
                    int keyDeleteReg = bytecode[pc++] & 0xFF;
                    sb.append("HASH_DELETE r").append(rd).append(" = delete r").append(hashDeleteReg).append("{r").append(keyDeleteReg).append("}\n");
                    break;
                case Opcodes.HASH_KEYS:
                    rd = bytecode[pc++] & 0xFF;
                    int hashKeysReg = bytecode[pc++] & 0xFF;
                    sb.append("HASH_KEYS r").append(rd).append(" = keys(r").append(hashKeysReg).append(")\n");
                    break;
                case Opcodes.HASH_VALUES:
                    rd = bytecode[pc++] & 0xFF;
                    int hashValuesReg = bytecode[pc++] & 0xFF;
                    sb.append("HASH_VALUES r").append(rd).append(" = values(r").append(hashValuesReg).append(")\n");
                    break;
                case Opcodes.CREATE_LIST: {
                    rd = bytecode[pc++] & 0xFF;
                    int listCount = bytecode[pc++] & 0xFF;
                    sb.append("CREATE_LIST r").append(rd).append(" = [");
                    for (int i = 0; i < listCount; i++) {
                        if (i > 0) sb.append(", ");
                        int listRs = bytecode[pc++] & 0xFF;
                        sb.append("r").append(listRs);
                    }
                    sb.append("]\n");
                    break;
                }
                case Opcodes.CALL_SUB:
                    rd = bytecode[pc++] & 0xFF;
                    int coderefReg = bytecode[pc++] & 0xFF;
                    int argsReg = bytecode[pc++] & 0xFF;
                    int ctx = bytecode[pc++] & 0xFF;
                    sb.append("CALL_SUB r").append(rd).append(" = r").append(coderefReg)
                      .append("->(r").append(argsReg).append(", ctx=").append(ctx).append(")\n");
                    break;
                case Opcodes.JOIN:
                    rd = bytecode[pc++] & 0xFF;
                    int separatorReg = bytecode[pc++] & 0xFF;
                    int listReg = bytecode[pc++] & 0xFF;
                    sb.append("JOIN r").append(rd).append(" = join(r").append(separatorReg)
                      .append(", r").append(listReg).append(")\n");
                    break;
                case Opcodes.SELECT:
                    rd = bytecode[pc++] & 0xFF;
                    listReg = bytecode[pc++] & 0xFF;
                    sb.append("SELECT r").append(rd).append(" = select(r").append(listReg).append(")\n");
                    break;
                case Opcodes.RANGE:
                    rd = bytecode[pc++] & 0xFF;
                    int startReg = bytecode[pc++] & 0xFF;
                    int endReg = bytecode[pc++] & 0xFF;
                    sb.append("RANGE r").append(rd).append(" = r").append(startReg).append("..r").append(endReg).append("\n");
                    break;
                case Opcodes.CREATE_HASH:
                    rd = bytecode[pc++] & 0xFF;
                    int hashListReg = bytecode[pc++] & 0xFF;
                    sb.append("CREATE_HASH r").append(rd).append(" = hash_ref(r").append(hashListReg).append(")\n");
                    break;
                case Opcodes.RAND:
                    rd = bytecode[pc++] & 0xFF;
                    int maxReg = bytecode[pc++] & 0xFF;
                    sb.append("RAND r").append(rd).append(" = rand(r").append(maxReg).append(")\n");
                    break;
                case Opcodes.MAP:
                    rd = bytecode[pc++] & 0xFF;
                    rs1 = bytecode[pc++] & 0xFF;  // list register
                    rs2 = bytecode[pc++] & 0xFF;  // closure register
                    int mapCtx = bytecode[pc++] & 0xFF;  // context
                    sb.append("MAP r").append(rd).append(" = map(r").append(rs1)
                      .append(", r").append(rs2).append(", ctx=").append(mapCtx).append(")\n");
                    break;
                case Opcodes.NEW_ARRAY:
                    rd = bytecode[pc++] & 0xFF;
                    sb.append("NEW_ARRAY r").append(rd).append(" = new RuntimeArray()\n");
                    break;
                case Opcodes.NEW_HASH:
                    rd = bytecode[pc++] & 0xFF;
                    sb.append("NEW_HASH r").append(rd).append(" = new RuntimeHash()\n");
                    break;
                case Opcodes.ARRAY_SET_FROM_LIST:
                    rs1 = bytecode[pc++] & 0xFF;  // array register
                    rs2 = bytecode[pc++] & 0xFF;  // list register
                    sb.append("ARRAY_SET_FROM_LIST r").append(rs1).append(".setFromList(r").append(rs2).append(")\n");
                    break;
                case Opcodes.HASH_SET_FROM_LIST:
                    rs1 = bytecode[pc++] & 0xFF;  // hash register
                    rs2 = bytecode[pc++] & 0xFF;  // list register
                    sb.append("HASH_SET_FROM_LIST r").append(rs1).append(".setFromList(r").append(rs2).append(")\n");
                    break;
                case Opcodes.STORE_GLOBAL_CODE:
                    int codeNameIdx = bytecode[pc++] & 0xFF;
                    rs = bytecode[pc++] & 0xFF;
                    sb.append("STORE_GLOBAL_CODE '").append(stringPool[codeNameIdx]).append("' = r").append(rs).append("\n");
                    break;
                case Opcodes.CREATE_CLOSURE:
                    rd = bytecode[pc++] & 0xFF;
                    int templateIdx = bytecode[pc++] & 0xFF;
                    int numCaptures = bytecode[pc++] & 0xFF;
                    sb.append("CREATE_CLOSURE r").append(rd).append(" = closure(template[").append(templateIdx).append("], captures=[");
                    for (int i = 0; i < numCaptures; i++) {
                        if (i > 0) sb.append(", ");
                        int captureReg = bytecode[pc++] & 0xFF;
                        sb.append("r").append(captureReg);
                    }
                    sb.append("])\n");
                    break;
                case Opcodes.NOT:
                    rd = bytecode[pc++] & 0xFF;
                    rs = bytecode[pc++] & 0xFF;
                    sb.append("NOT r").append(rd).append(" = !r").append(rs).append("\n");
                    break;
                case Opcodes.SLOW_OP: {
                    int slowOpId = bytecode[pc++] & 0xFF;
                    String opName = SlowOpcodeHandler.getSlowOpName(slowOpId);
                    sb.append("SLOW_OP ").append(opName).append(" (id=").append(slowOpId).append(")");

                    // Decode operands for known SLOW_OPs
                    switch (slowOpId) {
                        case Opcodes.SLOWOP_EVAL_STRING:
                            // Format: [rd] [rs_string]
                            rd = bytecode[pc++] & 0xFF;
                            rs = bytecode[pc++] & 0xFF;
                            sb.append(" r").append(rd).append(" = eval(r").append(rs).append(")");
                            break;
                        case Opcodes.SLOWOP_SELECT:
                            // Format: [rd] [rs_list]
                            rd = bytecode[pc++] & 0xFF;
                            rs = bytecode[pc++] & 0xFF;
                            sb.append(" r").append(rd).append(" = select(r").append(rs).append(")");
                            break;
                        case Opcodes.SLOWOP_LOAD_GLOB:
                            // Format: [rd] [name_idx]
                            rd = bytecode[pc++] & 0xFF;
                            int globNameIdx = bytecode[pc++] & 0xFF;
                            String globName = stringPool[globNameIdx];
                            sb.append(" r").append(rd).append(" = *").append(globName);
                            break;
                        case Opcodes.SLOWOP_RETRIEVE_BEGIN_SCALAR:
                        case Opcodes.SLOWOP_RETRIEVE_BEGIN_ARRAY:
                        case Opcodes.SLOWOP_RETRIEVE_BEGIN_HASH:
                            // Format: [rd] [name_idx] [begin_id]
                            rd = bytecode[pc++] & 0xFF;
                            int varNameIdx = bytecode[pc++] & 0xFF;
                            int beginId = bytecode[pc++] & 0xFF;
                            String varName = stringPool[varNameIdx];
                            sb.append(" r").append(rd).append(" = ").append(varName)
                              .append(" (BEGIN_").append(beginId).append(")");
                            break;
                        case Opcodes.SLOWOP_LOCAL_SCALAR:
                            // Format: [rd] [name_idx]
                            rd = bytecode[pc++] & 0xFF;
                            int localNameIdx = bytecode[pc++] & 0xFF;
                            String localVarName = stringPool[localNameIdx];
                            sb.append(" r").append(rd).append(" = local ").append(localVarName);
                            break;
                        default:
                            sb.append(" (operands not decoded)");
                            break;
                    }
                    sb.append("\n");
                    break;
                }
                default:
                    sb.append("UNKNOWN(").append(opcode & 0xFF).append(")\n");
                    break;
            }
        }
        return sb.toString();
    }

    private static int readInt(byte[] bytecode, int pc) {
        return ((bytecode[pc] & 0xFF) << 24) |
               ((bytecode[pc + 1] & 0xFF) << 16) |
               ((bytecode[pc + 2] & 0xFF) << 8) |
               (bytecode[pc + 3] & 0xFF);
    }

    /**
     * Builder class for constructing InterpretedCode instances.
     */
    public static class Builder {
        private byte[] bytecode;
        private Object[] constants = new Object[0];
        private String[] stringPool = new String[0];
        private int maxRegisters = 10;
        private RuntimeBase[] capturedVars = null;
        private String sourceName = "<eval>";
        private int sourceLine = 1;

        public Builder bytecode(byte[] bytecode) {
            this.bytecode = bytecode;
            return this;
        }

        public Builder constants(Object[] constants) {
            this.constants = constants;
            return this;
        }

        public Builder stringPool(String[] stringPool) {
            this.stringPool = stringPool;
            return this;
        }

        public Builder maxRegisters(int maxRegisters) {
            this.maxRegisters = maxRegisters;
            return this;
        }

        public Builder capturedVars(RuntimeBase[] capturedVars) {
            this.capturedVars = capturedVars;
            return this;
        }

        public Builder sourceName(String sourceName) {
            this.sourceName = sourceName;
            return this;
        }

        public Builder sourceLine(int sourceLine) {
            this.sourceLine = sourceLine;
            return this;
        }

        public InterpretedCode build() {
            if (bytecode == null) {
                throw new IllegalStateException("Bytecode is required");
            }
            return new InterpretedCode(bytecode, constants, stringPool, maxRegisters,
                                      capturedVars, sourceName, sourceLine, null, null);
        }
    }
}
