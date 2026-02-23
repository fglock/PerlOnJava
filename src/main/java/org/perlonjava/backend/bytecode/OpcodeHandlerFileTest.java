package org.perlonjava.backend.bytecode;

import org.perlonjava.runtime.operators.FileTestOperator;
import org.perlonjava.runtime.runtimetypes.RuntimeBase;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

/**
 * File test opcode handlers.
 *
 * Extracted from BytecodeInterpreter.execute() to reduce method size.
 * Handles all file test operations (opcodes 190-216).
 */
public class OpcodeHandlerFileTest {

    /**
     * Execute file test operation based on opcode.
     * Format: FILETEST_* rd rs
     *
     * @param bytecode The bytecode array
     * @param pc Current program counter
     * @param registers Register file
     * @param opcode The file test opcode (190-216)
     * @return Updated program counter
     */
    public static int executeFileTest(int[] bytecode, int pc, RuntimeBase[] registers, int opcode) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];

        // Map opcode to test flag
        String testFlag = switch (opcode) {
            case Opcodes.FILETEST_R -> "-r";
            case Opcodes.FILETEST_W -> "-w";
            case Opcodes.FILETEST_X -> "-x";
            case Opcodes.FILETEST_O -> "-o";
            case Opcodes.FILETEST_R_REAL -> "-R";
            case Opcodes.FILETEST_W_REAL -> "-W";
            case Opcodes.FILETEST_X_REAL -> "-X";
            case Opcodes.FILETEST_O_REAL -> "-O";
            case Opcodes.FILETEST_E -> "-e";
            case Opcodes.FILETEST_Z -> "-z";
            case Opcodes.FILETEST_S -> "-s";
            case Opcodes.FILETEST_F -> "-f";
            case Opcodes.FILETEST_D -> "-d";
            case Opcodes.FILETEST_L -> "-l";
            case Opcodes.FILETEST_P -> "-p";
            case Opcodes.FILETEST_S_UPPER -> "-S";
            case Opcodes.FILETEST_B -> "-b";
            case Opcodes.FILETEST_C -> "-c";
            case Opcodes.FILETEST_T -> "-t";
            case Opcodes.FILETEST_U -> "-u";
            case Opcodes.FILETEST_G -> "-g";
            case Opcodes.FILETEST_K -> "-k";
            case Opcodes.FILETEST_T_UPPER -> "-T";
            case Opcodes.FILETEST_B_UPPER -> "-B";
            case Opcodes.FILETEST_M -> "-M";
            case Opcodes.FILETEST_A -> "-A";
            case Opcodes.FILETEST_C_UPPER -> "-C";
            default -> throw new RuntimeException("Unknown file test opcode: " + opcode);
        };

        registers[rd] = FileTestOperator.fileTest(testFlag, (RuntimeScalar) registers[rs]);
        return pc;
    }
}
