package org.perlonjava.backend.bytecode;

import org.perlonjava.runtime.runtimetypes.*;

import java.util.BitSet;
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
    public final int[] bytecode;           // Instruction stream (opcodes + operands as ints)
    public final Object[] constants;       // Constant pool (RuntimeBase objects)
    public final String[] stringPool;      // String constants (variable names, etc.)
    public final int maxRegisters;         // Number of registers needed
    public final RuntimeBase[] capturedVars; // Closure support (captured from outer scope)
    public final Map<String, Integer> variableRegistry; // Variable name → register index (for eval STRING)

    // Lexical pragma state (for eval STRING to inherit)
    public final int strictOptions;        // Strict flags at compile time
    public final int featureFlags;         // Feature flags at compile time
    public final BitSet warningFlags;      // Warning flags at compile time
    public final String compilePackage;    // Package at compile time (for eval STRING name resolution)

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
     * @param strictOptions Strict flags at compile time (for eval STRING inheritance)
     * @param featureFlags  Feature flags at compile time (for eval STRING inheritance)
     * @param warningFlags  Warning flags at compile time (for eval STRING inheritance)
     */
    public InterpretedCode(int[] bytecode, Object[] constants, String[] stringPool,
                          int maxRegisters, RuntimeBase[] capturedVars,
                          String sourceName, int sourceLine,
                          TreeMap<Integer, Integer> pcToTokenIndex,
                          Map<String, Integer> variableRegistry,
                          ErrorMessageUtil errorUtil,
                          int strictOptions, int featureFlags, BitSet warningFlags) {
        this(bytecode, constants, stringPool, maxRegisters, capturedVars,
             sourceName, sourceLine, pcToTokenIndex, variableRegistry, errorUtil,
             strictOptions, featureFlags, warningFlags, "main");
    }

    public InterpretedCode(int[] bytecode, Object[] constants, String[] stringPool,
                          int maxRegisters, RuntimeBase[] capturedVars,
                          String sourceName, int sourceLine,
                          TreeMap<Integer, Integer> pcToTokenIndex,
                          Map<String, Integer> variableRegistry,
                          ErrorMessageUtil errorUtil,
                          int strictOptions, int featureFlags, BitSet warningFlags,
                          String compilePackage) {
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
        this.strictOptions = strictOptions;
        this.featureFlags = featureFlags;
        this.warningFlags = warningFlags;
        this.compilePackage = compilePackage;
    }

    // Legacy constructor for backward compatibility
    public InterpretedCode(int[] bytecode, Object[] constants, String[] stringPool,
                          int maxRegisters, RuntimeBase[] capturedVars,
                          String sourceName, int sourceLine,
                          java.util.Map<Integer, Integer> pcToTokenIndex) {
        this(bytecode, constants, stringPool, maxRegisters, capturedVars,
             sourceName, sourceLine,
             pcToTokenIndex instanceof TreeMap ? (TreeMap<Integer, Integer>)pcToTokenIndex : new TreeMap<>(pcToTokenIndex),
             null, null, 0, 0, new BitSet());
    }

    // Legacy constructor with variableRegistry but no errorUtil
    public InterpretedCode(int[] bytecode, Object[] constants, String[] stringPool,
                          int maxRegisters, RuntimeBase[] capturedVars,
                          String sourceName, int sourceLine,
                          java.util.Map<Integer, Integer> pcToTokenIndex,
                          Map<String, Integer> variableRegistry) {
        this(bytecode, constants, stringPool, maxRegisters, capturedVars,
             sourceName, sourceLine,
             pcToTokenIndex instanceof TreeMap ? (TreeMap<Integer, Integer>)pcToTokenIndex : new TreeMap<>(pcToTokenIndex),
             variableRegistry, null, 0, 0, new BitSet());
    }

    /**
     * Override RuntimeCode.apply() to dispatch to interpreter.
     *
     * <p>This is the ONLY method that differs from compiled RuntimeCode.
     * The API signature is IDENTICAL, ensuring perfect compatibility.
     *
     * <p>Regex state save/restore is handled inside {@code BytecodeInterpreter.execute()}
     * (via {@code savedRegexState}/finally), not here.
     *
     * @param args        The arguments array (@_)
     * @param callContext The calling context (VOID/SCALAR/LIST)
     * @return RuntimeList containing the result (may be RuntimeControlFlowList)
     */
    @Override
    public RuntimeList apply(RuntimeArray args, int callContext) {
        return BytecodeInterpreter.execute(this, args, callContext);
    }

    @Override
    public RuntimeList apply(String subroutineName, RuntimeArray args, int callContext) {
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
            this.errorUtil,  // Preserve error util
            this.strictOptions,  // Preserve strict flags
            this.featureFlags,  // Preserve feature flags
            this.warningFlags  // Preserve warning flags
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
            int opcode = bytecode[pc++];
            int rs1;
            int rs2;
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
                    pc += 1;
                    break;
                case Opcodes.LAST:
                    sb.append("LAST ").append(readInt(bytecode, pc)).append("\n");
                    pc += 1;
                    break;
                case Opcodes.NEXT:
                    sb.append("NEXT ").append(readInt(bytecode, pc)).append("\n");
                    pc += 1;
                    break;
                case Opcodes.REDO:
                    sb.append("REDO ").append(readInt(bytecode, pc)).append("\n");
                    pc += 1;
                    break;
                case Opcodes.GOTO_IF_FALSE:
                    int condReg = bytecode[pc++];
                    int target = readInt(bytecode, pc);
                    pc += 1;
                    sb.append("GOTO_IF_FALSE r").append(condReg).append(" -> ").append(target).append("\n");
                    break;
                case Opcodes.GOTO_IF_TRUE:
                    condReg = bytecode[pc++];
                    target = readInt(bytecode, pc);
                    pc += 1;
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
                        } else if (obj instanceof PerlRange) {
                            // Special handling for PerlRange to avoid expanding large ranges
                            PerlRange range = (PerlRange) obj;
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
                    pc += 1;
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
                case Opcodes.LOAD_VSTRING:
                    rd = bytecode[pc++];
                    strIdx = bytecode[pc++];
                    sb.append("LOAD_VSTRING r").append(rd).append(" = v\"");
                    if (stringPool != null && strIdx < stringPool.length) {
                        sb.append(stringPool[strIdx]);
                    }
                    sb.append("\"\n");
                    break;
                case Opcodes.GLOB_OP: {
                    int globRd = bytecode[pc++];
                    int globId = bytecode[pc++];
                    int globPattern = bytecode[pc++];
                    int globCtx = bytecode[pc++];
                    sb.append("GLOB_OP r").append(globRd).append(" = glob(id=").append(globId)
                      .append(", r").append(globPattern).append(", ctx=").append(globCtx).append(")\n");
                    break;
                }
                case Opcodes.LOAD_UNDEF:
                    rd = bytecode[pc++];
                    sb.append("LOAD_UNDEF r").append(rd).append("\n");
                    break;
                case Opcodes.LOAD_GLOBAL_SCALAR:
                    rd = bytecode[pc++];
                    int nameIdx = bytecode[pc++];
                    sb.append("LOAD_GLOBAL_SCALAR r").append(rd).append(" = $");
                    if (stringPool != null && nameIdx >= 0 && nameIdx < stringPool.length) {
                        sb.append(stringPool[nameIdx]);
                    } else {
                        sb.append("<bad_string_idx:").append(nameIdx).append(">");
                    }
                    sb.append("\n");
                    break;
                case Opcodes.LOAD_GLOBAL_ARRAY:
                    rd = bytecode[pc++];
                    nameIdx = bytecode[pc++];
                    sb.append("LOAD_GLOBAL_ARRAY r").append(rd).append(" = @");
                    if (stringPool != null && nameIdx >= 0 && nameIdx < stringPool.length) {
                        sb.append(stringPool[nameIdx]);
                    } else {
                        sb.append("<bad_string_idx:").append(nameIdx).append(">");
                    }
                    sb.append("\n");
                    break;
                case Opcodes.STORE_GLOBAL_ARRAY:
                    nameIdx = bytecode[pc++];
                    int storeArraySrcReg = bytecode[pc++];
                    sb.append("STORE_GLOBAL_ARRAY @");
                    if (stringPool != null && nameIdx >= 0 && nameIdx < stringPool.length) {
                        sb.append(stringPool[nameIdx]);
                    } else {
                        sb.append("<bad_string_idx:").append(nameIdx).append(">");
                    }
                    sb.append(" = r").append(storeArraySrcReg).append("\n");
                    break;
                case Opcodes.LOAD_GLOBAL_HASH:
                    rd = bytecode[pc++];
                    nameIdx = bytecode[pc++];
                    sb.append("LOAD_GLOBAL_HASH r").append(rd).append(" = %");
                    if (stringPool != null && nameIdx >= 0 && nameIdx < stringPool.length) {
                        sb.append(stringPool[nameIdx]);
                    } else {
                        sb.append("<bad_string_idx:").append(nameIdx).append(">");
                    }
                    sb.append("\n");
                    break;
                case Opcodes.STORE_GLOBAL_HASH:
                    nameIdx = bytecode[pc++];
                    int storeHashSrcReg = bytecode[pc++];
                    sb.append("STORE_GLOBAL_HASH %");
                    if (stringPool != null && nameIdx >= 0 && nameIdx < stringPool.length) {
                        sb.append(stringPool[nameIdx]);
                    } else {
                        sb.append("<bad_string_idx:").append(nameIdx).append(">");
                    }
                    sb.append(" = r").append(storeHashSrcReg).append("\n");
                    break;
                case Opcodes.LOAD_GLOBAL_CODE:
                    rd = bytecode[pc++];
                    nameIdx = bytecode[pc++];
                    sb.append("LOAD_GLOBAL_CODE r").append(rd).append(" = &");
                    if (stringPool != null && nameIdx >= 0 && nameIdx < stringPool.length) {
                        sb.append(stringPool[nameIdx]);
                    } else {
                        sb.append("<bad_string_idx:").append(nameIdx).append(">");
                    }
                    sb.append("\n");
                    break;
                case Opcodes.STORE_GLOBAL_SCALAR:
                    nameIdx = bytecode[pc++];
                    int srcReg = bytecode[pc++];
                    sb.append("STORE_GLOBAL_SCALAR $");
                    if (stringPool != null && nameIdx >= 0 && nameIdx < stringPool.length) {
                        sb.append(stringPool[nameIdx]);
                    } else {
                        sb.append("<bad_string_idx:").append(nameIdx).append(">");
                    }
                    sb.append(" = r").append(srcReg).append("\n");
                    break;
                case Opcodes.HASH_KEYVALUE_SLICE: {
                    rd = bytecode[pc++];
                    int kvRs1 = bytecode[pc++];  // hash register
                    int kvRs2 = bytecode[pc++];  // keys list register
                    sb.append("HASH_KEYVALUE_SLICE r").append(rd)
                            .append(" = r").append(kvRs1)
                            .append(".getKeyValueSlice(r").append(kvRs2).append(")\n");
                    break;
                }
                case Opcodes.ADD_SCALAR:
                    rd = bytecode[pc++];
                    int addRs1 = bytecode[pc++];
                    int addRs2 = bytecode[pc++];
                    sb.append("ADD_SCALAR r").append(rd).append(" = r").append(addRs1).append(" + r").append(addRs2).append("\n");
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
                    pc += 1;
                    sb.append("ADD_SCALAR_INT r").append(rd).append(" = r").append(rs).append(" + ").append(imm).append("\n");
                    break;
                case Opcodes.SUB_SCALAR_INT:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    int subImm = readInt(bytecode, pc);
                    pc += 1;
                    sb.append("SUB_SCALAR_INT r").append(rd).append(" = r").append(rs).append(" - ").append(subImm).append("\n");
                    break;
                case Opcodes.MUL_SCALAR_INT:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    int mulImm = readInt(bytecode, pc);
                    pc += 1;
                    sb.append("MUL_SCALAR_INT r").append(rd).append(" = r").append(rs).append(" * ").append(mulImm).append("\n");
                    break;
                case Opcodes.CONCAT:
                    rd = bytecode[pc++];
                    rs1 = bytecode[pc++];
                    rs2 = bytecode[pc++];
                    sb.append("CONCAT r").append(rd).append(" = r").append(rs1).append(" . r").append(rs2).append("\n");
                    break;
                case Opcodes.REPEAT:
                    rd = bytecode[pc++];
                    rs1 = bytecode[pc++];
                    rs2 = bytecode[pc++];
                    sb.append("REPEAT r").append(rd).append(" = r").append(rs1).append(" x r").append(rs2).append("\n");
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
                    pc += 1;
                    sb.append("ADD_ASSIGN_INT r").append(rd).append(" += ").append(imm).append("\n");
                    break;
                case Opcodes.STRING_CONCAT_ASSIGN:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("STRING_CONCAT_ASSIGN r").append(rd).append(" .= r").append(rs).append("\n");
                    break;
                case Opcodes.BITWISE_AND_ASSIGN:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("BITWISE_AND_ASSIGN r").append(rd).append(" &= r").append(rs).append("\n");
                    break;
                case Opcodes.BITWISE_OR_ASSIGN:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("BITWISE_OR_ASSIGN r").append(rd).append(" |= r").append(rs).append("\n");
                    break;
                case Opcodes.BITWISE_XOR_ASSIGN:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("BITWISE_XOR_ASSIGN r").append(rd).append(" ^= r").append(rs).append("\n");
                    break;
                case Opcodes.STRING_BITWISE_AND_ASSIGN:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("STRING_BITWISE_AND_ASSIGN r").append(rd).append(" &.= r").append(rs).append("\n");
                    break;
                case Opcodes.STRING_BITWISE_OR_ASSIGN:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("STRING_BITWISE_OR_ASSIGN r").append(rd).append(" |.= r").append(rs).append("\n");
                    break;
                case Opcodes.STRING_BITWISE_XOR_ASSIGN:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("STRING_BITWISE_XOR_ASSIGN r").append(rd).append(" ^.= r").append(rs).append("\n");
                    break;
                case Opcodes.BITWISE_AND_BINARY:
                    rd = bytecode[pc++];
                    int andRs1 = bytecode[pc++];
                    int andRs2 = bytecode[pc++];
                    sb.append("BITWISE_AND_BINARY r").append(rd).append(" = r").append(andRs1).append(" & r").append(andRs2).append("\n");
                    break;
                case Opcodes.BITWISE_OR_BINARY:
                    rd = bytecode[pc++];
                    int orRs1 = bytecode[pc++];
                    int orRs2 = bytecode[pc++];
                    sb.append("BITWISE_OR_BINARY r").append(rd).append(" = r").append(orRs1).append(" | r").append(orRs2).append("\n");
                    break;
                case Opcodes.BITWISE_XOR_BINARY:
                    rd = bytecode[pc++];
                    int xorRs1 = bytecode[pc++];
                    int xorRs2 = bytecode[pc++];
                    sb.append("BITWISE_XOR_BINARY r").append(rd).append(" = r").append(xorRs1).append(" ^ r").append(xorRs2).append("\n");
                    break;
                case Opcodes.STRING_BITWISE_AND:
                    rd = bytecode[pc++];
                    int strAndRs1 = bytecode[pc++];
                    int strAndRs2 = bytecode[pc++];
                    sb.append("STRING_BITWISE_AND r").append(rd).append(" = r").append(strAndRs1).append(" &. r").append(strAndRs2).append("\n");
                    break;
                case Opcodes.STRING_BITWISE_OR:
                    rd = bytecode[pc++];
                    int strOrRs1 = bytecode[pc++];
                    int strOrRs2 = bytecode[pc++];
                    sb.append("STRING_BITWISE_OR r").append(rd).append(" = r").append(strOrRs1).append(" |. r").append(strOrRs2).append("\n");
                    break;
                case Opcodes.STRING_BITWISE_XOR:
                    rd = bytecode[pc++];
                    int strXorRs1 = bytecode[pc++];
                    int strXorRs2 = bytecode[pc++];
                    sb.append("STRING_BITWISE_XOR r").append(rd).append(" = r").append(strXorRs1).append(" ^. r").append(strXorRs2).append("\n");
                    break;
                case Opcodes.BITWISE_NOT_BINARY:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("BITWISE_NOT_BINARY r").append(rd).append(" = ~r").append(rs).append("\n");
                    break;
                case Opcodes.BITWISE_NOT_STRING:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("BITWISE_NOT_STRING r").append(rd).append(" = ~.r").append(rs).append("\n");
                    break;
                case Opcodes.STAT:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    int statCtx = bytecode[pc++];
                    sb.append("STAT r").append(rd).append(" = stat(r").append(rs).append(", ctx=").append(statCtx).append(")\n");
                    break;
                case Opcodes.LSTAT:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    int lstatCtx = bytecode[pc++];
                    sb.append("LSTAT r").append(rd).append(" = lstat(r").append(rs).append(", ctx=").append(lstatCtx).append(")\n");
                    break;
                case Opcodes.STAT_LASTHANDLE:
                    rd = bytecode[pc++];
                    int slhCtx = bytecode[pc++];
                    sb.append("STAT_LASTHANDLE r").append(rd).append(" = stat(_, ctx=").append(slhCtx).append(")\n");
                    break;
                case Opcodes.LSTAT_LASTHANDLE:
                    rd = bytecode[pc++];
                    int llhCtx = bytecode[pc++];
                    sb.append("LSTAT_LASTHANDLE r").append(rd).append(" = lstat(_, ctx=").append(llhCtx).append(")\n");
                    break;
                case Opcodes.FILETEST_R:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("FILETEST_R r").append(rd).append(" = -r r").append(rs).append("\n");
                    break;
                case Opcodes.FILETEST_W:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("FILETEST_W r").append(rd).append(" = -w r").append(rs).append("\n");
                    break;
                case Opcodes.FILETEST_X:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("FILETEST_X r").append(rd).append(" = -x r").append(rs).append("\n");
                    break;
                case Opcodes.FILETEST_O:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("FILETEST_O r").append(rd).append(" = -o r").append(rs).append("\n");
                    break;
                case Opcodes.FILETEST_R_REAL:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("FILETEST_R_REAL r").append(rd).append(" = -R r").append(rs).append("\n");
                    break;
                case Opcodes.FILETEST_W_REAL:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("FILETEST_W_REAL r").append(rd).append(" = -W r").append(rs).append("\n");
                    break;
                case Opcodes.FILETEST_X_REAL:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("FILETEST_X_REAL r").append(rd).append(" = -X r").append(rs).append("\n");
                    break;
                case Opcodes.FILETEST_O_REAL:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("FILETEST_O_REAL r").append(rd).append(" = -O r").append(rs).append("\n");
                    break;
                case Opcodes.FILETEST_E:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("FILETEST_E r").append(rd).append(" = -e r").append(rs).append("\n");
                    break;
                case Opcodes.FILETEST_Z:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("FILETEST_Z r").append(rd).append(" = -z r").append(rs).append("\n");
                    break;
                case Opcodes.FILETEST_S:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("FILETEST_S r").append(rd).append(" = -s r").append(rs).append("\n");
                    break;
                case Opcodes.FILETEST_F:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("FILETEST_F r").append(rd).append(" = -f r").append(rs).append("\n");
                    break;
                case Opcodes.FILETEST_D:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("FILETEST_D r").append(rd).append(" = -d r").append(rs).append("\n");
                    break;
                case Opcodes.FILETEST_L:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("FILETEST_L r").append(rd).append(" = -l r").append(rs).append("\n");
                    break;
                case Opcodes.FILETEST_P:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("FILETEST_P r").append(rd).append(" = -p r").append(rs).append("\n");
                    break;
                case Opcodes.FILETEST_S_UPPER:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("FILETEST_S_UPPER r").append(rd).append(" = -S r").append(rs).append("\n");
                    break;
                case Opcodes.FILETEST_B:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("FILETEST_B r").append(rd).append(" = -b r").append(rs).append("\n");
                    break;
                case Opcodes.FILETEST_C:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("FILETEST_C r").append(rd).append(" = -c r").append(rs).append("\n");
                    break;
                case Opcodes.FILETEST_T:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("FILETEST_T r").append(rd).append(" = -t r").append(rs).append("\n");
                    break;
                case Opcodes.FILETEST_U:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("FILETEST_U r").append(rd).append(" = -u r").append(rs).append("\n");
                    break;
                case Opcodes.FILETEST_G:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("FILETEST_G r").append(rd).append(" = -g r").append(rs).append("\n");
                    break;
                case Opcodes.FILETEST_K:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("FILETEST_K r").append(rd).append(" = -k r").append(rs).append("\n");
                    break;
                case Opcodes.FILETEST_T_UPPER:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("FILETEST_T_UPPER r").append(rd).append(" = -T r").append(rs).append("\n");
                    break;
                case Opcodes.FILETEST_B_UPPER:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("FILETEST_B_UPPER r").append(rd).append(" = -B r").append(rs).append("\n");
                    break;
                case Opcodes.FILETEST_M:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("FILETEST_M r").append(rd).append(" = -M r").append(rs).append("\n");
                    break;
                case Opcodes.FILETEST_A:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("FILETEST_A r").append(rd).append(" = -A r").append(rs).append("\n");
                    break;
                case Opcodes.FILETEST_C_UPPER:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("FILETEST_C_UPPER r").append(rd).append(" = -C r").append(rs).append("\n");
                    break;
                case Opcodes.FILETEST_LASTHANDLE:
                    rd = bytecode[pc++];
                    int opStrIdx = bytecode[pc++];
                    sb.append("FILETEST_LASTHANDLE r").append(rd).append(" = ").append(stringPool[opStrIdx]).append(" _\n");
                    break;
                case Opcodes.GLOB_SLOT_GET:
                    rd = bytecode[pc++];
                    int globReg2 = bytecode[pc++];
                    int keyReg = bytecode[pc++];
                    sb.append("GLOB_SLOT_GET r").append(rd).append(" = r").append(globReg2).append("{r").append(keyReg).append("}\n");
                    break;
                case Opcodes.SPRINTF:
                    rd = bytecode[pc++];
                    int formatReg = bytecode[pc++];
                    int argsListReg = bytecode[pc++];
                    sb.append("SPRINTF r").append(rd).append(" = sprintf(r").append(formatReg).append(", r").append(argsListReg).append(")\n");
                    break;
                case Opcodes.CHOP:
                    rd = bytecode[pc++];
                    int scalarReg = bytecode[pc++];
                    sb.append("CHOP r").append(rd).append(" = chop(r").append(scalarReg).append(")\n");
                    break;
                case Opcodes.GET_REPLACEMENT_REGEX:
                    rd = bytecode[pc++];
                    rs1 = bytecode[pc++];  // pattern
                    rs2 = bytecode[pc++];  // replacement
                    int rs3 = bytecode[pc++];  // flags
                    sb.append("GET_REPLACEMENT_REGEX r").append(rd).append(" = getReplacementRegex(r").append(rs1).append(", r").append(rs2).append(", r").append(rs3).append(")\n");
                    break;
                case Opcodes.SUBSTR_VAR:
                    rd = bytecode[pc++];
                    int substrArgsReg = bytecode[pc++];
                    int substrCtx = bytecode[pc++];
                    sb.append("SUBSTR_VAR r").append(rd).append(" = substr(r").append(substrArgsReg).append(", ctx=").append(substrCtx).append(")\n");
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
                case Opcodes.MATCH_REGEX_NOT:
                    rd = bytecode[pc++];
                    strReg = bytecode[pc++];
                    regReg = bytecode[pc++];
                    matchCtx = bytecode[pc++];
                    sb.append("MATCH_REGEX_NOT r").append(rd).append(" = r").append(strReg).append(" !~ r").append(regReg).append(" (ctx=").append(matchCtx).append(")\n");
                    break;
                case Opcodes.CHOMP:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("CHOMP r").append(rd).append(" = chomp(r").append(rs).append(")\n");
                    break;
                case Opcodes.WANTARRAY:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("WANTARRAY r").append(rd).append(" = wantarray(r").append(rs).append(")\n");
                    break;
                case Opcodes.REQUIRE:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("REQUIRE r").append(rd).append(" = require(r").append(rs).append(")\n");
                    break;
                case Opcodes.POS:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("POS r").append(rd).append(" = pos(r").append(rs).append(")\n");
                    break;
                case Opcodes.INDEX: {
                    rd = bytecode[pc++];
                    int idxStrReg = bytecode[pc++];
                    int idxSubstrReg = bytecode[pc++];
                    int idxPosReg = bytecode[pc++];
                    sb.append("INDEX r").append(rd).append(" = index(r").append(idxStrReg).append(", r").append(idxSubstrReg).append(", r").append(idxPosReg).append(")\n");
                    break;
                }
                case Opcodes.RINDEX: {
                    rd = bytecode[pc++];
                    int ridxStrReg = bytecode[pc++];
                    int ridxSubstrReg = bytecode[pc++];
                    int ridxPosReg = bytecode[pc++];
                    sb.append("RINDEX r").append(rd).append(" = rindex(r").append(ridxStrReg).append(", r").append(ridxSubstrReg).append(", r").append(ridxPosReg).append(")\n");
                    break;
                }
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
                    // Read catch target as single int slot (matches emitInt/readInt)
                    int catchPc = bytecode[pc++];
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
                case Opcodes.ARRAY_SET:
                    arrayReg = bytecode[pc++];
                    indexReg = bytecode[pc++];
                    int arraySetValueReg = bytecode[pc++];
                    sb.append("ARRAY_SET r").append(arrayReg).append("[r").append(indexReg).append("] = r").append(arraySetValueReg).append("\n");
                    break;
                case Opcodes.ARRAY_PUSH:
                    arrayReg = bytecode[pc++];
                    int arrayPushValueReg = bytecode[pc++];
                    sb.append("ARRAY_PUSH r").append(arrayReg).append(".push(r").append(arrayPushValueReg).append(")\n");
                    break;
                case Opcodes.ARRAY_POP:
                    rd = bytecode[pc++];
                    arrayReg = bytecode[pc++];
                    sb.append("ARRAY_POP r").append(rd).append(" = r").append(arrayReg).append(".pop()\n");
                    break;
                case Opcodes.ARRAY_SHIFT:
                    rd = bytecode[pc++];
                    arrayReg = bytecode[pc++];
                    sb.append("ARRAY_SHIFT r").append(rd).append(" = r").append(arrayReg).append(".shift()\n");
                    break;
                case Opcodes.ARRAY_UNSHIFT:
                    arrayReg = bytecode[pc++];
                    int arrayUnshiftValueReg = bytecode[pc++];
                    sb.append("ARRAY_UNSHIFT r").append(arrayReg).append(".unshift(r").append(arrayUnshiftValueReg).append(")\n");
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
                    pc += 1;
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
                    pc += 1;  // readInt reads 2 shorts
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
                case Opcodes.FOREACH_NEXT_OR_EXIT: {
                    rd = bytecode[pc++];
                    int iterReg = bytecode[pc++];
                    int bodyTarget = readInt(bytecode, pc);  // Absolute body address
                    pc += 1;
                    sb.append("FOREACH_NEXT_OR_EXIT r").append(rd)
                      .append(" = r").append(iterReg).append(".next() and goto ")
                      .append(bodyTarget).append("\n");
                    break;
                }
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
                case Opcodes.REPEAT_ASSIGN:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("REPEAT_ASSIGN r").append(rd).append(" x= r").append(rs).append("\n");
                    break;
                case Opcodes.POW_ASSIGN:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("POW_ASSIGN r").append(rd).append(" **= r").append(rs).append("\n");
                    break;
                case Opcodes.LEFT_SHIFT_ASSIGN:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("LEFT_SHIFT_ASSIGN r").append(rd).append(" <<= r").append(rs).append("\n");
                    break;
                case Opcodes.RIGHT_SHIFT_ASSIGN:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("RIGHT_SHIFT_ASSIGN r").append(rd).append(" >>= r").append(rs).append("\n");
                    break;
                case Opcodes.LOGICAL_AND_ASSIGN:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("LOGICAL_AND_ASSIGN r").append(rd).append(" &&= r").append(rs).append("\n");
                    break;
                case Opcodes.LOGICAL_OR_ASSIGN:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("LOGICAL_OR_ASSIGN r").append(rd).append(" ||= r").append(rs).append("\n");
                    break;
                case Opcodes.LEFT_SHIFT:
                    rd = bytecode[pc++];
                    rs1 = bytecode[pc++];
                    rs2 = bytecode[pc++];
                    sb.append("LEFT_SHIFT r").append(rd).append(" = r").append(rs1).append(" << r").append(rs2).append("\n");
                    break;
                case Opcodes.RIGHT_SHIFT:
                    rd = bytecode[pc++];
                    rs1 = bytecode[pc++];
                    rs2 = bytecode[pc++];
                    sb.append("RIGHT_SHIFT r").append(rd).append(" = r").append(rs1).append(" >> r").append(rs2).append("\n");
                    break;
                case Opcodes.LIST_TO_COUNT:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("LIST_TO_COUNT r").append(rd).append(" = count(r").append(rs).append(")\n");
                    break;
                case Opcodes.LIST_TO_SCALAR:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("LIST_TO_SCALAR r").append(rd).append(" = scalar(r").append(rs).append(")\n");
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
                case Opcodes.DEREF_GLOB:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    nameIdx = bytecode[pc++];
                    sb.append("DEREF_GLOB r").append(rd).append(" = *{r").append(rs).append("} pkg=").append(stringPool[nameIdx]).append("\n");
                    break;
                case Opcodes.DEREF_ARRAY:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("DEREF_ARRAY r").append(rd).append(" = @{r").append(rs).append("}\n");
                    break;
                case Opcodes.DEREF_HASH:
                    rd = bytecode[pc++];
                    rs = bytecode[pc++];
                    sb.append("DEREF_HASH r").append(rd).append(" = %{r").append(rs).append("}\n");
                    break;
                case Opcodes.RETRIEVE_BEGIN_SCALAR:
                    rd = bytecode[pc++];
                    nameIdx = bytecode[pc++];
                    int beginId = bytecode[pc++];
                    sb.append("RETRIEVE_BEGIN_SCALAR r").append(rd).append(" = BEGIN_").append(beginId)
                      .append("::").append(stringPool[nameIdx]).append("\n");
                    break;
                case Opcodes.SPLIT:
                    rd = bytecode[pc++];
                    int splitPatternReg = bytecode[pc++];
                    int splitArgsReg = bytecode[pc++];
                    int splitCtx = bytecode[pc++];
                    sb.append("SPLIT r").append(rd).append(" = split(r").append(splitPatternReg)
                      .append(", r").append(splitArgsReg).append(", ctx=").append(splitCtx).append(")\n");
                    break;
                case Opcodes.LOCAL_SCALAR:
                    rd = bytecode[pc++];
                    nameIdx = bytecode[pc++];
                    sb.append("LOCAL_SCALAR r").append(rd).append(" = local $").append(stringPool[nameIdx]).append("\n");
                    break;
                case Opcodes.LOCAL_ARRAY:
                    rd = bytecode[pc++];
                    nameIdx = bytecode[pc++];
                    sb.append("LOCAL_ARRAY r").append(rd).append(" = local @").append(stringPool[nameIdx]).append("\n");
                    break;
                case Opcodes.LOCAL_HASH:
                    rd = bytecode[pc++];
                    nameIdx = bytecode[pc++];
                    sb.append("LOCAL_HASH r").append(rd).append(" = local %").append(stringPool[nameIdx]).append("\n");
                    break;
                case Opcodes.LOCAL_SCALAR_SAVE_LEVEL: {
                    rd = bytecode[pc++];
                    int levelReg = bytecode[pc++];
                    nameIdx = bytecode[pc++];
                    sb.append("LOCAL_SCALAR_SAVE_LEVEL r").append(rd).append(", level=r").append(levelReg)
                      .append(" = local $").append(stringPool[nameIdx]).append("\n");
                    break;
                }
                case Opcodes.POP_LOCAL_LEVEL:
                    rs = bytecode[pc++];
                    sb.append("POP_LOCAL_LEVEL DynamicVariableManager.popToLocalLevel(r").append(rs).append(")\n");
                    break;
                case Opcodes.FOREACH_GLOBAL_NEXT_OR_EXIT: {
                    rd = bytecode[pc++];
                    int fgIterReg = bytecode[pc++];
                    nameIdx = bytecode[pc++];
                    int fgBody = readInt(bytecode, pc); pc += 1;
                    sb.append("FOREACH_GLOBAL_NEXT_OR_EXIT r").append(rd).append(" = r").append(fgIterReg)
                      .append(".next(), alias $").append(stringPool[nameIdx]).append(" and goto ").append(fgBody).append("\n");
                    break;
                }
                // Misc list operators: OPCODE rd argsReg ctx
                case Opcodes.PACK:
                case Opcodes.UNPACK:
                case Opcodes.CRYPT:
                case Opcodes.LOCALTIME:
                case Opcodes.GMTIME:
                case Opcodes.CHMOD:
                case Opcodes.UNLINK:
                case Opcodes.UTIME:
                case Opcodes.RENAME:
                case Opcodes.LINK:
                case Opcodes.READLINK:
                case Opcodes.UMASK:
                case Opcodes.GETC:
                case Opcodes.FILENO:
                case Opcodes.SYSTEM:
                case Opcodes.CALLER:
                case Opcodes.EACH:
                case Opcodes.VEC: {
                    rd = bytecode[pc++];
                    int miscArgsReg = bytecode[pc++];
                    int miscCtx = bytecode[pc++];
                    String miscName = switch (opcode) {
                        case Opcodes.PACK -> "pack";
                        case Opcodes.UNPACK -> "unpack";
                        case Opcodes.CRYPT -> "crypt";
                        case Opcodes.LOCALTIME -> "localtime";
                        case Opcodes.GMTIME -> "gmtime";
                        case Opcodes.CHMOD -> "chmod";
                        case Opcodes.UNLINK -> "unlink";
                        case Opcodes.UTIME -> "utime";
                        case Opcodes.RENAME -> "rename";
                        case Opcodes.LINK -> "link";
                        case Opcodes.READLINK -> "readlink";
                        case Opcodes.UMASK -> "umask";
                        case Opcodes.GETC -> "getc";
                        case Opcodes.FILENO -> "fileno";
                        case Opcodes.SYSTEM -> "system";
                        case Opcodes.CALLER -> "caller";
                        case Opcodes.EACH -> "each";
                        case Opcodes.VEC -> "vec";
                        default -> "misc_op_" + opcode;
                    };
                    sb.append(miscName).append(" r").append(rd)
                      .append(" = ").append(miscName).append("(r").append(miscArgsReg)
                      .append(", ctx=").append(miscCtx).append(")\n");
                    break;
                }

                // DEPRECATED: SLOW_OP case removed - opcode 87 is no longer emitted
                // All operations now use direct opcodes (114-154)

                // =================================================================
                // GENERATED BUILT-IN FUNCTION DISASSEMBLY
                // =================================================================
                // Generated by dev/tools/generate_opcode_handlers.pl
                // DO NOT EDIT MANUALLY - regenerate using the tool

                // GENERATED_DISASM_START

                // scalar_binary
                case Opcodes.ATAN2:
                case Opcodes.BINARY_AND:
                case Opcodes.BINARY_OR:
                case Opcodes.BINARY_XOR:
                case Opcodes.EQ:
                case Opcodes.NE:
                case Opcodes.LT:
                case Opcodes.LE:
                case Opcodes.GT:
                case Opcodes.GE:
                case Opcodes.CMP:
                case Opcodes.X:
                    pc = ScalarBinaryOpcodeHandler.disassemble(opcode, bytecode, pc, sb);
                    break;

                // scalar_unary
                case Opcodes.INT:
                case Opcodes.LOG:
                case Opcodes.SQRT:
                case Opcodes.COS:
                case Opcodes.SIN:
                case Opcodes.EXP:
                case Opcodes.ABS:
                case Opcodes.BINARY_NOT:
                case Opcodes.INTEGER_BITWISE_NOT:
                case Opcodes.ORD:
                case Opcodes.ORD_BYTES:
                case Opcodes.OCT:
                case Opcodes.HEX:
                case Opcodes.SRAND:
                case Opcodes.CHR:
                case Opcodes.CHR_BYTES:
                case Opcodes.LENGTH_BYTES:
                case Opcodes.QUOTEMETA:
                case Opcodes.FC:
                case Opcodes.LC:
                case Opcodes.LCFIRST:
                case Opcodes.UC:
                case Opcodes.UCFIRST:
                case Opcodes.SLEEP:
                case Opcodes.TELL:
                case Opcodes.RMDIR:
                case Opcodes.CLOSEDIR:
                case Opcodes.REWINDDIR:
                case Opcodes.TELLDIR:
                case Opcodes.CHDIR:
                case Opcodes.EXIT:
                    pc = ScalarUnaryOpcodeHandler.disassemble(opcode, bytecode, pc, sb);
                    break;
                // GENERATED_DISASM_END

                case Opcodes.FLIP_FLOP: {
                    int ffRd = bytecode[pc++];
                    int ffId = bytecode[pc++];
                    int ffRs1 = bytecode[pc++];
                    int ffRs2 = bytecode[pc++];
                    sb.append("FLIP_FLOP r").append(ffRd).append(" = flipFlop(").append(ffId).append(", r").append(ffRs1).append(", r").append(ffRs2).append(")\n");
                    break;
                }
                case Opcodes.LOCAL_GLOB:
                    sb.append("LOCAL_GLOB r").append(bytecode[pc++]).append(" = pushLocalVariable(glob '").append(stringPool[bytecode[pc++]]).append("')\n");
                    break;
                case Opcodes.GET_LOCAL_LEVEL:
                    sb.append("GET_LOCAL_LEVEL r").append(bytecode[pc++]).append("\n");
                    break;
                case Opcodes.SET_PACKAGE:
                    sb.append("SET_PACKAGE '").append(stringPool[bytecode[pc++]]).append("'\n");
                    break;
                case Opcodes.PUSH_PACKAGE:
                    sb.append("PUSH_PACKAGE '").append(stringPool[bytecode[pc++]]).append("'\n");
                    break;
                case Opcodes.POP_PACKAGE:
                    sb.append("POP_PACKAGE\n");
                    break;
                case Opcodes.DO_FILE:
                    sb.append("DO_FILE r").append(bytecode[pc++]).append(" = doFile(r").append(bytecode[pc++]).append(") ctx=").append(bytecode[pc++]).append("\n");
                    break;
                case Opcodes.PUSH_LABELED_BLOCK: {
                    int labelIdx = bytecode[pc++];
                    int exitPc = bytecode[pc++];
                    sb.append("PUSH_LABELED_BLOCK \"").append(stringPool[labelIdx]).append("\" exitPc=").append(exitPc).append("\n");
                    break;
                }
                case Opcodes.POP_LABELED_BLOCK:
                    sb.append("POP_LABELED_BLOCK\n");
                    break;
                default:
                    sb.append("UNKNOWN(").append(opcode).append(")\n");
                    break;
            }
        }
        return sb.toString();
    }

    /**
     * Read a 32-bit integer from bytecode (stored as 1 int slot).
     * With int[] storage a full int fits in a single slot.
     */
    private static int readInt(int[] bytecode, int pc) {
        return bytecode[pc];
    }

    /**
     * Builder class for constructing InterpretedCode instances.
     */
    public static class Builder {
        private int[] bytecode;
        private Object[] constants = new Object[0];
        private String[] stringPool = new String[0];
        private int maxRegisters = 10;
        private RuntimeBase[] capturedVars = null;
        private String sourceName = "<eval>";
        private int sourceLine = 1;

        public Builder bytecode(int[] bytecode) {
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
