package org.perlonjava.interpreter;

import org.perlonjava.runtime.*;

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

    // Debug information (optional)
    public final String sourceName;        // Source file name (for stack traces)
    public final int sourceLine;           // Source line number

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
     */
    public InterpretedCode(byte[] bytecode, Object[] constants, String[] stringPool,
                          int maxRegisters, RuntimeBase[] capturedVars,
                          String sourceName, int sourceLine) {
        super(null, new java.util.ArrayList<>()); // Call RuntimeCode constructor with null prototype, empty attributes
        this.bytecode = bytecode;
        this.constants = constants;
        this.stringPool = stringPool;
        this.maxRegisters = maxRegisters;
        this.capturedVars = capturedVars;
        this.sourceName = sourceName;
        this.sourceLine = sourceLine;
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
            this.sourceLine
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

        // Store in RuntimeCode.interpretedSubs map for reference
        RuntimeCode.interpretedSubs.put(name, this);

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
                case Opcodes.LOAD_INT:
                    int rd = bytecode[pc++] & 0xFF;
                    int value = readInt(bytecode, pc);
                    pc += 4;
                    sb.append("LOAD_INT r").append(rd).append(" = ").append(value).append("\n");
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
                                      capturedVars, sourceName, sourceLine);
        }
    }
}
