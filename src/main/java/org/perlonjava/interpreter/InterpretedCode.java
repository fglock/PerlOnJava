package org.perlonjava.interpreter;

import org.perlonjava.runtime.*;
import java.util.Map;
import java.util.TreeMap;

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
    public final short[] bytecode;         // Instruction stream (opcodes + operands as shorts)
    public final Object[] constants;       // Constant pool (RuntimeBase objects)
    public final String[] stringPool;      // String constants (variable names, etc.)
    public final int maxRegisters;         // Number of registers needed
    public final RuntimeBase[] capturedVars; // Closure support (captured from outer scope)
    public final Map<String, Integer> variableRegistry; // Variable name → register index (for eval STRING)

    // Debug information (optional)
    public final String sourceName;        // Source file name (for stack traces)
    public final int sourceLine;           // Source line number
    public final TreeMap<Integer, Integer> pcToTokenIndex;  // Map bytecode PC to tokenIndex for error reporting (TreeMap for floorEntry lookup)
    public final ErrorMessageUtil errorUtil; // For converting token index to line numbers

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
     * @param errorUtil     Error message utility for line number lookup
     */
    public InterpretedCode(short[] bytecode, Object[] constants, String[] stringPool,
                          int maxRegisters, RuntimeBase[] capturedVars,
                          String sourceName, int sourceLine,
                          TreeMap<Integer, Integer> pcToTokenIndex,
                          Map<String, Integer> variableRegistry,
                          ErrorMessageUtil errorUtil) {
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
        this.errorUtil = errorUtil;
    }

    // Legacy constructor for backward compatibility
    public InterpretedCode(short[] bytecode, Object[] constants, String[] stringPool,
                          int maxRegisters, RuntimeBase[] capturedVars,
                          String sourceName, int sourceLine,
                          java.util.Map<Integer, Integer> pcToTokenIndex) {
        this(bytecode, constants, stringPool, maxRegisters, capturedVars,
             sourceName, sourceLine,
             pcToTokenIndex instanceof TreeMap ? (TreeMap<Integer, Integer>)pcToTokenIndex : new TreeMap<>(pcToTokenIndex),
             null, null);
    }

    // Legacy constructor with variableRegistry but no errorUtil
    public InterpretedCode(short[] bytecode, Object[] constants, String[] stringPool,
                          int maxRegisters, RuntimeBase[] capturedVars,
                          String sourceName, int sourceLine,
                          java.util.Map<Integer, Integer> pcToTokenIndex,
                          Map<String, Integer> variableRegistry) {
        this(bytecode, constants, stringPool, maxRegisters, capturedVars,
             sourceName, sourceLine,
             pcToTokenIndex instanceof TreeMap ? (TreeMap<Integer, Integer>)pcToTokenIndex : new TreeMap<>(pcToTokenIndex),
             variableRegistry, null);
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
            this.variableRegistry,  // Preserve variable registry
            this.errorUtil  // Preserve error util
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
        sb.append("Bytecode length: ").append(bytecode.length).append(" shorts\n\n");

        int pc = 0;
        while (pc < bytecode.length) {
            int startPc = pc;
            short opcode = bytecode[pc++];
            sb.append(String.format("%4d: ", startPc));

            switch (opcode) {
                case Opcodes.NOP:
                    sb.append("NOP\n");
                    break;
                case Opcodes.RETURN:
                    int retReg = bytecode[pc++];
                    sb.append("RETURN r").append(retReg).append("\n");
                    break;
                case Opcodes.GOTO:
                    sb.append("GOTO ").append(readInt(bytecode, pc)).append("\n");
                    pc += 2;
                    break;
                case Opcodes.GOTO_IF_FALSE:
                    int condReg = bytecode[pc++];
                    int target = readInt(bytecode, pc);
                    pc += 2;
                    sb.append("GOTO_IF_FALSE r").append(condReg).append(" -> ").append(target).append("\n");
                    break;
                case Opcodes.GOTO_IF_TRUE:
                    condReg = bytecode[pc++];
                    target = readInt(bytecode, pc);
                    pc += 2;
                    sb.append("GOTO_IF_TRUE r").append(condReg).append(" -> ").append(target).append("\n");
                    break;
                case Opcodes.MOVE:
                    int dest = bytecode[pc++];
                    int src = bytecode[pc++];
                    sb.append("MOVE r").append(dest).append(" = r").append(src).append("\n");
                    break;
                case Opcodes.LOAD_CONST:
                    int rd = bytecode[pc++];
                    int constIdx = bytecode[pc++];
                    sb.append("LOAD_CONST r").append(rd).append(" = constants[").append(constIdx).append("]");
                    if (constants != null && constIdx < constants.length) {
                        Object obj = constants[constIdx];
                        sb.append(" (");
                        if (obj instanceof RuntimeScalar) {
                            RuntimeScalar scalar = (RuntimeScalar) obj;
                            sb.append("RuntimeScalar{type=").append(scalar.type).append(", value=").append(scalar.value.getClass().getSimpleName()).append("}");
                        } else if (obj instanceof org.perlonjava.runtime.PerlRange) {
                            // Special handling for PerlRange to avoid expanding large ranges
                            org.perlonjava.runtime.PerlRange range = (org.perlonjava.runtime.PerlRange) obj;
                            sb.append("PerlRange{").append(range.getStart().toString()).append("..")
                              .append(range.getEnd().toString()).append("}");
                        } else {
                            // For other objects, show class name and limit string length
                            String objStr = obj.toString();
                            if (objStr.length() > 100) {
                                sb.append(obj.getClass().getSimpleName()).append("{...}");
                            } else {
                                sb.append(objStr);
                            }
                        }
                        sb.append(")");
                    }
                    sb.append("\n");
                    break;
                case Opcodes.LOAD_INT:
                    rd = bytecode[pc++];
                    int value = readInt(bytecode, pc);
                    pc += 2;
                    sb.append("LOAD_INT r").append(rd).append(" = ").append(value).append("\n");
                    break;
                case Opcodes.LOAD_STRING:
                    rd = bytecode[pc++];
                    int strIdx = bytecode[pc++];
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
                    rd = bytecode[pc++];
                    sb.append("LOAD_UNDEF r").append(rd).append("\n");
                    break;
                case Opcodes.LOAD_GLOBAL_SCALAR:
                    rd = bytecode[pc++];
                    int nameIdx = bytecode[pc++];
                    sb.append("LOAD_GLOBAL_SCALAR r").append(rd).append(" = $").append(stringPool[nameIdx]).append("\n");
                    break;
                case Opcodes.LOAD_GLOBAL_ARRAY:
                    rd = bytecode[pc++];
                    nameIdx = bytecode[pc++];
                    sb.append("LOAD_GLOBAL_ARRAY r").append(rd).append(" = @").append(stringPool[nameIdx]).append("\n");
                    break;
                case Opcodes.STORE_GLOBAL_ARRAY:
                    nameIdx = bytecode[pc++];
                    int storeArraySrcReg = bytecode[pc++];
                    sb.append("STORE_GLOBAL_ARRAY @").append(stringPool[nameIdx]).append(" = r").append(storeArraySrcReg).append("\n");
                    break;
                case Opcodes.LOAD_GLOBAL_HASH:
                    rd = bytecode[pc++];
                    nameIdx = bytecode[pc++];
                    sb.append("LOAD_GLOBAL_HASH r").append(rd).append(" = %").append(stringPool[nameIdx]).append("\n");
                    break;
                case Opcodes.STORE_GLOBAL_HASH:
                    nameIdx = bytecode[pc++];
                    int storeHashSrcReg = bytecode[pc++];
                    sb.append("STORE_GLOBAL_HASH %").append(stringPool[nameIdx]).append(" = r").append(storeHashSrcReg).append("\n");
                    break;
                case Opcodes.LOAD_GLOBAL_CODE:
                    rd = bytecode[pc++];
                    nameIdx = bytecode[pc++];
                    sb.append("LOAD_GLOBAL_CODE r").append(rd).append(" = &").append(stringPool[nameIdx]).append("\n");
                    break;
                case Opcodes.STORE_GLOBAL_SCALAR:
                    nameIdx = bytecode[pc++];
                    int srcReg = bytecode[pc++];
                    sb.append("STORE_GLOBAL_SCALAR $").append(stringPool[nameIdx]).append(" = r").append(srcReg).append("\n");
                    break;
                case Opcodes.ADD_SCALAR:
                    rd = bytecode[pc++];
                    int rs1 = bytecode[pc++];
                    int rs2 = bytecode[pc++];
                    sb.append("ADD_SCALAR r").append(rd).append(" = r").append(rs1).append(" + r").append(rs2).append("\n");
                    break;
                case Opcodes.SUB_SCALAR:
                    rd = bytecode[pc++];
                    rs1 = bytecode[pc++];
                    rs2 = bytecode[pc++];
                    sb.append("SUB_SCALAR r").append(rd).append(" = r").append(rs1).append(" - r").append(rs2).append("\n");
                    break;
                case Opcodes.MUL_SCALAR:
                    rd = bytecode[pc++];
                    rs1 = bytecode[pc++];
                    rs2 = bytecode[pc++];
                    sb.append("MUL_SCALAR r").append(rd).append(" = r").append(rs1).append(" * r").append(rs2).append("\n");
                    break;
                case Opcodes.DIV_SCALAR:
                    rd = bytecode[pc++];
                    rs1 = bytecode[pc++];
                    rs2 = bytecode[pc++];
                    sb.append("DIV_SCALAR r").append(rd).append(" = r").append(rs1).append(" / r").append(rs2).append("\n");
                    break;
                case Opcodes.MOD_SCALAR:
                    rd = bytecode[pc++];
                    rs1 = bytecode[pc++];
                    rs2 = bytecode[pc++];
                    sb.append("MOD_SCALAR r").append(rd).append(" = r").append(rs1).append(" % r").append(rs2).append("\n");
                    break;
                case Opcodes.POW_SCALAR:
                    rd = bytecode[pc++];
                    rs1 = bytecode[pc++];
                    rs2 = bytecode[pc++];
                    sb.append("POW_SCALAR r").append(rd).append(" = r").append(rs1).append(" ** r").append(rs2).append("\n");
                    break;
                case Opcodes.NEG_SCALAR:
                    rd = bytecode[pc++];
                    int rsNeg = bytecode[pc++];
                    sb.append("NEG_SCALAR r").append(rd).append(" = -r").append(rsNeg).append("\n");
                    break;
                case Opcodes.ADD_SCALAR_INT:
                    rd = bytecode[pc++];
                    int rs = bytecode[pc++];
                    int imm = readInt(bytecode, pc);
                    pc += 2;
                    sb.append("ADD_SCALAR_INT r").append(rd).append(" = r").append(rs).append(" + ").append(imm).append("\n");
                    break;
                case Opcodes.LT_NUM:
                    rd = bytecode[pc++];
                    rs1 = bytecode[pc++];
                    rs2 = bytecode[pc++];
                    sb.append("LT_NUM r").append(rd).append(" = r").append(rs1).append(" < r").append(rs2).append("\n");
                    break;
                case Opcodes.GT_NUM:
                    rd = bytecode[pc++];
                    rs1 = bytecode[pc++];
                    rs2 = bytecode[pc++];
                    sb.append("GT_NUM r").append(rd).append(" = r").append(rs1).append(" > r").append(rs2).append("\n");
                    break;
                case Opcodes.LE_NUM:
                    rd = bytecode[pc++];
                    rs1 = bytecode[pc++];
                    rs2 = bytecode[pc++];
                    sb.append("LE_NUM r").append(rd).append(" = r").append(rs1).append(" <= r").append(rs2).append("\n");
                    break;
                case Opcodes.GE_NUM:
                    rd = bytecode[pc++];
                    rs1 = bytecode[pc++];
                    rs2 = bytecode[pc++];
                    sb.append("GE_NUM r").append(rd).append(" = r").append(rs1).append(" >= r").append(rs2).append("\n");
                    break;
                case Opcodes.NE_NUM:
                    rd = bytecode[pc++];
                    rs1 = bytecode[pc++];
                    rs2 = bytecode[pc++];
                    sb.append("NE_NUM r").append(rd).append(" = r").append(rs1).append(" != r").append(rs2).append("\n");
                    break;
                case Opcodes.INC_REG:
                    rd = bytecode[pc++];
                    sb.append("INC_REG r").append(rd).append("++\n");
                    break;
                case Opcodes.DEC_REG:
                    rd = bytecode[pc++];
                    sb.append("DEC_REG r").append(rd).append("--\n");
                    break;
                case Opcodes.ADD_ASSIGN:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("ADD_ASSIGN r").append(rd).append(" += r").append(rs).append("\n");
                    break;
                case Opcodes.ADD_ASSIGN_INT:
                    rd = bytecode[pc++];
                    imm = readInt(bytecode, pc);
                    pc += 2;
                    sb.append("ADD_ASSIGN_INT r").append(rd).append(" += ").append(imm).append("\n");
                    break;
                case Opcodes.STRING_CONCAT_ASSIGN:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("STRING_CONCAT_ASSIGN r").append(rd).append(" .= r").append(rs).append("\n");
                    break;
                case Opcodes.PUSH_LOCAL_VARIABLE:
                    rs = bytecode[pc++];
                    sb.append("PUSH_LOCAL_VARIABLE r").append(rs).append("\n");
                    break;
                case Opcodes.STORE_GLOB:
                    int globReg = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("STORE_GLOB r").append(globReg).append(" = r").append(rs).append("\n");
                    break;
                case Opcodes.OPEN:
                    rd = bytecode[pc++];
                    int openCtx = bytecode[pc++];
                    int openArgs = bytecode[pc++];
                    sb.append("OPEN r").append(rd).append(" = open(ctx=").append(openCtx).append(", r").append(openArgs).append(")\n");
                    break;
                case Opcodes.READLINE:
                    rd = bytecode[pc++];
                    int fhReg = bytecode[pc++];
                    int readCtx = bytecode[pc++];
                    sb.append("READLINE r").append(rd).append(" = readline(r").append(fhReg).append(", ctx=").append(readCtx).append(")\n");
                    break;
                case Opcodes.MATCH_REGEX:
                    rd = bytecode[pc++];
                    int strReg = bytecode[pc++];
                    int regReg = bytecode[pc++];
                    int matchCtx = bytecode[pc++];
                    sb.append("MATCH_REGEX r").append(rd).append(" = r").append(strReg).append(" =~ r").append(regReg).append(" (ctx=").append(matchCtx).append(")\n");
                    break;
                case Opcodes.CHOMP:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("CHOMP r").append(rd).append(" = chomp(r").append(rs).append(")\n");
                    break;
                case Opcodes.PRE_AUTOINCREMENT:
                    rd = bytecode[pc++];
                    sb.append("PRE_AUTOINCREMENT ++r").append(rd).append("\n");
                    break;
                case Opcodes.POST_AUTOINCREMENT:
                    rd = bytecode[pc++];
                    sb.append("POST_AUTOINCREMENT r").append(rd).append("++\n");
                    break;
                case Opcodes.PRE_AUTODECREMENT:
                    rd = bytecode[pc++];
                    sb.append("PRE_AUTODECREMENT --r").append(rd).append("\n");
                    break;
                case Opcodes.POST_AUTODECREMENT:
                    rd = bytecode[pc++];
                    sb.append("POST_AUTODECREMENT r").append(rd).append("--\n");
                    break;
                case Opcodes.PRINT: {
                    int contentReg = bytecode[pc++];
                    int filehandleReg = bytecode[pc++];
                    sb.append("PRINT r").append(contentReg).append(", fh=r").append(filehandleReg).append("\n");
                    break;
                }
                case Opcodes.SAY: {
                    int contentReg = bytecode[pc++];
                    int filehandleReg = bytecode[pc++];
                    sb.append("SAY r").append(contentReg).append(", fh=r").append(filehandleReg).append("\n");
                    break;
                }
                case Opcodes.CREATE_REF:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("CREATE_REF r").append(rd).append(" = \\r").append(rs).append("\n");
                    break;
                case Opcodes.DEREF:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("DEREF r").append(rd).append(" = ${r").append(rs).append("}\n");
                    break;
                case Opcodes.GET_TYPE:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("GET_TYPE r").append(rd).append(" = type(r").append(rs).append(")\n");
                    break;
                case Opcodes.DIE:
                    rs = bytecode[pc++];
                    sb.append("DIE r").append(rs).append("\n");
                    break;
                case Opcodes.WARN:
                    rs = bytecode[pc++];
                    sb.append("WARN r").append(rs).append("\n");
                    break;
                case Opcodes.EVAL_TRY: {
                    // Read 4-byte absolute catch target
                    int high = bytecode[pc++] & 0xFFFF;
                    int low = bytecode[pc++] & 0xFFFF;
                    int catchPc = (high << 16) | low;
                    sb.append("EVAL_TRY catch_at=").append(catchPc).append("\n");
                    break;
                }
                case Opcodes.EVAL_END:
                    sb.append("EVAL_END\n");
                    break;
                case Opcodes.EVAL_CATCH:
                    rd = bytecode[pc++];
                    sb.append("EVAL_CATCH r").append(rd).append("\n");
                    break;
                case Opcodes.ARRAY_GET:
                    rd = bytecode[pc++];
                    int arrayReg = bytecode[pc++];
                    int indexReg = bytecode[pc++];
                    sb.append("ARRAY_GET r").append(rd).append(" = r").append(arrayReg).append("[r").append(indexReg).append("]\n");
                    break;
                case Opcodes.ARRAY_SIZE:
                    rd = bytecode[pc++];
                    arrayReg = bytecode[pc++];
                    sb.append("ARRAY_SIZE r").append(rd).append(" = size(r").append(arrayReg).append(")\n");
                    break;
                case Opcodes.CREATE_ARRAY:
                    rd = bytecode[pc++];
                    int listSourceReg = bytecode[pc++];
                    sb.append("CREATE_ARRAY r").append(rd).append(" = array(r").append(listSourceReg).append(")\n");
                    break;
                case Opcodes.HASH_GET:
                    rd = bytecode[pc++];
                    int hashGetReg = bytecode[pc++];
                    int keyGetReg = bytecode[pc++];
                    sb.append("HASH_GET r").append(rd).append(" = r").append(hashGetReg).append("{r").append(keyGetReg).append("}\n");
                    break;
                case Opcodes.HASH_SET:
                    int hashSetReg = bytecode[pc++];
                    int keySetReg = bytecode[pc++];
                    int valueSetReg = bytecode[pc++];
                    sb.append("HASH_SET r").append(hashSetReg).append("{r").append(keySetReg).append("} = r").append(valueSetReg).append("\n");
                    break;
                case Opcodes.HASH_EXISTS:
                    rd = bytecode[pc++];
                    int hashExistsReg = bytecode[pc++];
                    int keyExistsReg = bytecode[pc++];
                    sb.append("HASH_EXISTS r").append(rd).append(" = exists r").append(hashExistsReg).append("{r").append(keyExistsReg).append("}\n");
                    break;
                case Opcodes.HASH_DELETE:
                    rd = bytecode[pc++];
                    int hashDeleteReg = bytecode[pc++];
                    int keyDeleteReg = bytecode[pc++];
                    sb.append("HASH_DELETE r").append(rd).append(" = delete r").append(hashDeleteReg).append("{r").append(keyDeleteReg).append("}\n");
                    break;
                case Opcodes.HASH_KEYS:
                    rd = bytecode[pc++];
                    int hashKeysReg = bytecode[pc++];
                    sb.append("HASH_KEYS r").append(rd).append(" = keys(r").append(hashKeysReg).append(")\n");
                    break;
                case Opcodes.HASH_VALUES:
                    rd = bytecode[pc++];
                    int hashValuesReg = bytecode[pc++];
                    sb.append("HASH_VALUES r").append(rd).append(" = values(r").append(hashValuesReg).append(")\n");
                    break;
                case Opcodes.CREATE_LIST: {
                    rd = bytecode[pc++];
                    int listCount = bytecode[pc++];
                    sb.append("CREATE_LIST r").append(rd).append(" = [");
                    for (int i = 0; i < listCount; i++) {
                        if (i > 0) sb.append(", ");
                        int listRs = bytecode[pc++];
                        sb.append("r").append(listRs);
                    }
                    sb.append("]\n");
                    break;
                }
                case Opcodes.CALL_SUB:
                    rd = bytecode[pc++];
                    int coderefReg = bytecode[pc++];
                    int argsReg = bytecode[pc++];
                    int ctx = bytecode[pc++];
                    sb.append("CALL_SUB r").append(rd).append(" = r").append(coderefReg)
                      .append("->(r").append(argsReg).append(", ctx=").append(ctx).append(")\n");
                    break;
                case Opcodes.CALL_METHOD:
                    rd = bytecode[pc++];
                    int invocantReg = bytecode[pc++];
                    int methodReg = bytecode[pc++];
                    int currentSubReg = bytecode[pc++];
                    argsReg = bytecode[pc++];
                    ctx = bytecode[pc++];
                    sb.append("CALL_METHOD r").append(rd).append(" = r").append(invocantReg)
                      .append("->r").append(methodReg)
                      .append("(r").append(argsReg).append(", sub=r").append(currentSubReg)
                      .append(", ctx=").append(ctx).append(")\n");
                    break;
                case Opcodes.JOIN:
                    rd = bytecode[pc++];
                    int separatorReg = bytecode[pc++];
                    int listReg = bytecode[pc++];
                    sb.append("JOIN r").append(rd).append(" = join(r").append(separatorReg)
                      .append(", r").append(listReg).append(")\n");
                    break;
                case Opcodes.SELECT:
                    rd = bytecode[pc++];
                    listReg = bytecode[pc++];
                    sb.append("SELECT r").append(rd).append(" = select(r").append(listReg).append(")\n");
                    break;
                case Opcodes.RANGE:
                    rd = bytecode[pc++];
                    int startReg = bytecode[pc++];
                    int endReg = bytecode[pc++];
                    sb.append("RANGE r").append(rd).append(" = r").append(startReg).append("..r").append(endReg).append("\n");
                    break;
                case Opcodes.CREATE_HASH:
                    rd = bytecode[pc++];
                    int hashListReg = bytecode[pc++];
                    sb.append("CREATE_HASH r").append(rd).append(" = hash_ref(r").append(hashListReg).append(")\n");
                    break;
                case Opcodes.RAND:
                    rd = bytecode[pc++];
                    int maxReg = bytecode[pc++];
                    sb.append("RAND r").append(rd).append(" = rand(r").append(maxReg).append(")\n");
                    break;
                case Opcodes.MAP:
                    rd = bytecode[pc++];
                    rs1 = bytecode[pc++];  // list register
                    rs2 = bytecode[pc++];  // closure register
                    int mapCtx = bytecode[pc++];  // context
                    sb.append("MAP r").append(rd).append(" = map(r").append(rs1)
                      .append(", r").append(rs2).append(", ctx=").append(mapCtx).append(")\n");
                    break;
                case Opcodes.GREP:
                    rd = bytecode[pc++];
                    rs1 = bytecode[pc++];  // list register
                    rs2 = bytecode[pc++];  // closure register
                    int grepCtx = bytecode[pc++];  // context
                    sb.append("GREP r").append(rd).append(" = grep(r").append(rs1)
                      .append(", r").append(rs2).append(", ctx=").append(grepCtx).append(")\n");
                    break;
                case Opcodes.SORT:
                    rd = bytecode[pc++];
                    rs1 = bytecode[pc++];  // list register
                    rs2 = bytecode[pc++];  // closure register
                    int pkgIdx = readInt(bytecode, pc);
                    pc += 2;
                    sb.append("SORT r").append(rd).append(" = sort(r").append(rs1)
                      .append(", r").append(rs2).append(", pkg=").append(stringPool[pkgIdx]).append(")\n");
                    break;
                case Opcodes.NEW_ARRAY:
                    rd = bytecode[pc++];
                    sb.append("NEW_ARRAY r").append(rd).append(" = new RuntimeArray()\n");
                    break;
                case Opcodes.NEW_HASH:
                    rd = bytecode[pc++];
                    sb.append("NEW_HASH r").append(rd).append(" = new RuntimeHash()\n");
                    break;
                case Opcodes.ARRAY_SET_FROM_LIST:
                    rs1 = bytecode[pc++];  // array register
                    rs2 = bytecode[pc++];  // list register
                    sb.append("ARRAY_SET_FROM_LIST r").append(rs1).append(".setFromList(r").append(rs2).append(")\n");
                    break;
                case Opcodes.HASH_SET_FROM_LIST:
                    rs1 = bytecode[pc++];  // hash register
                    rs2 = bytecode[pc++];  // list register
                    sb.append("HASH_SET_FROM_LIST r").append(rs1).append(".setFromList(r").append(rs2).append(")\n");
                    break;
                case Opcodes.STORE_GLOBAL_CODE:
                    int codeNameIdx = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("STORE_GLOBAL_CODE '").append(stringPool[codeNameIdx]).append("' = r").append(rs).append("\n");
                    break;
                case Opcodes.CREATE_CLOSURE:
                    rd = bytecode[pc++];
                    int templateIdx = bytecode[pc++];
                    int numCaptures = bytecode[pc++];
                    sb.append("CREATE_CLOSURE r").append(rd).append(" = closure(template[").append(templateIdx).append("], captures=[");
                    for (int i = 0; i < numCaptures; i++) {
                        if (i > 0) sb.append(", ");
                        int captureReg = bytecode[pc++];
                        sb.append("r").append(captureReg);
                    }
                    sb.append("])\n");
                    break;
                case Opcodes.SET_SCALAR:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("SET_SCALAR r").append(rd).append(".set(r").append(rs).append(")\n");
                    break;
                case Opcodes.NOT:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("NOT r").append(rd).append(" = !r").append(rs).append("\n");
                    break;
                case Opcodes.DEFINED:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("DEFINED r").append(rd).append(" = defined(r").append(rs).append(")\n");
                    break;
                case Opcodes.REF:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("REF r").append(rd).append(" = ref(r").append(rs).append(")\n");
                    break;
                case Opcodes.BLESS:
                    rd = bytecode[pc++];
                    int refReg = bytecode[pc++];
                    int packageReg = bytecode[pc++];
                    sb.append("BLESS r").append(rd).append(" = bless(r").append(refReg)
                      .append(", r").append(packageReg).append(")\n");
                    break;
                case Opcodes.ISA:
                    rd = bytecode[pc++];
                    int objReg = bytecode[pc++];
                    int pkgReg = bytecode[pc++];
                    sb.append("ISA r").append(rd).append(" = isa(r").append(objReg)
                      .append(", r").append(pkgReg).append(")\n");
                    break;
                case Opcodes.PROTOTYPE:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    int packageIdx = readInt(bytecode, pc);
                    pc += 2;
                    String packageName = (stringPool != null && packageIdx < stringPool.length) ?
                        stringPool[packageIdx] : "<unknown>";
                    sb.append("PROTOTYPE r").append(rd).append(" = prototype(r").append(rs)
                      .append(", \"").append(packageName).append("\")\n");
                    break;
                case Opcodes.QUOTE_REGEX:
                    rd = bytecode[pc++];
                    int patternReg = bytecode[pc++];
                    int flagsReg = bytecode[pc++];
                    sb.append("QUOTE_REGEX r").append(rd).append(" = qr{r").append(patternReg)
                      .append("}r").append(flagsReg).append("\n");
                    break;
                case Opcodes.ITERATOR_CREATE:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("ITERATOR_CREATE r").append(rd).append(" = r").append(rs).append(".iterator()\n");
                    break;
                case Opcodes.ITERATOR_HAS_NEXT:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("ITERATOR_HAS_NEXT r").append(rd).append(" = r").append(rs).append(".hasNext()\n");
                    break;
                case Opcodes.ITERATOR_NEXT:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("ITERATOR_NEXT r").append(rd).append(" = r").append(rs).append(".next()\n");
                    break;
                case Opcodes.FOREACH_NEXT_OR_EXIT:
                    rd = bytecode[pc++];
                    int iterReg = bytecode[pc++];
                    int exitTarget = readInt(bytecode, pc);  // Absolute target address
                    pc += 2;
                    sb.append("FOREACH_NEXT_OR_EXIT r").append(rd)
                      .append(" = r").append(iterReg).append(".next() or goto ")
                      .append(exitTarget).append("\n");
                    break;
                case Opcodes.SUBTRACT_ASSIGN:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("SUBTRACT_ASSIGN r").append(rd).append(" -= r").append(rs).append("\n");
                    break;
                case Opcodes.MULTIPLY_ASSIGN:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("MULTIPLY_ASSIGN r").append(rd).append(" *= r").append(rs).append("\n");
                    break;
                case Opcodes.DIVIDE_ASSIGN:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("DIVIDE_ASSIGN r").append(rd).append(" /= r").append(rs).append("\n");
                    break;
                case Opcodes.MODULUS_ASSIGN:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("MODULUS_ASSIGN r").append(rd).append(" %= r").append(rs).append("\n");
                    break;
                case Opcodes.LIST_TO_SCALAR:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("LIST_TO_SCALAR r").append(rd).append(" = last_element(r").append(rs).append(")\n");
                    break;
                case Opcodes.SCALAR_TO_LIST:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("SCALAR_TO_LIST r").append(rd).append(" = to_list(r").append(rs).append(")\n");
                    break;
                case Opcodes.EVAL_STRING:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("EVAL_STRING r").append(rd).append(" = eval(r").append(rs).append(")\n");
                    break;
                case Opcodes.SELECT_OP:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("SELECT_OP r").append(rd).append(" = select(r").append(rs).append(")\n");
                    break;
                case Opcodes.LOAD_GLOB:
                    rd = bytecode[pc++];
                    nameIdx = bytecode[pc++];
                    sb.append("LOAD_GLOB r").append(rd).append(" = *").append(stringPool[nameIdx]).append("\n");
                    break;
                case Opcodes.SLEEP_OP:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("SLEEP_OP r").append(rd).append(" = sleep(r").append(rs).append(")\n");
                    break;
                // DEPRECATED: SLOW_OP case removed - opcode 87 is no longer emitted
                // All operations now use direct opcodes (114-154)
                default:
                    sb.append("UNKNOWN(").append(opcode & 0xFF).append(")\n");
                    break;
            }
        }
        return sb.toString();
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
     * Builder class for constructing InterpretedCode instances.
     */
    public static class Builder {
        private short[] bytecode;
        private Object[] constants = new Object[0];
        private String[] stringPool = new String[0];
        private int maxRegisters = 10;
        private RuntimeBase[] capturedVars = null;
        private String sourceName = "<eval>";
        private int sourceLine = 1;

        public Builder bytecode(short[] bytecode) {
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
