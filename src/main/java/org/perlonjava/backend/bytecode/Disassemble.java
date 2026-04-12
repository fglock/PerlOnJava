package org.perlonjava.backend.bytecode;

import org.perlonjava.runtime.runtimetypes.PerlRange;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

public class Disassemble {
    /**
     * Disassemble bytecode for debugging and optimization analysis.
     *
     * @param interpretedCode
     */
    public static String disassemble(InterpretedCode interpretedCode) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Bytecode Disassembly ===\n");
        sb.append("Source: ").append(interpretedCode.sourceName).append(":").append(interpretedCode.sourceLine).append("\n");
        sb.append("Registers: ").append(interpretedCode.maxRegisters).append("\n");
        sb.append("Bytecode length: ").append(interpretedCode.bytecode.length).append(" shorts\n\n");

        int pc = 0;
        try {
            while (pc < interpretedCode.bytecode.length) {
                int startPc = pc;
                int opcode = interpretedCode.bytecode[pc++];
                int rs1;
                int rs2;
                sb.append(String.format("%4d: ", startPc));

                switch (opcode) {
                    case Opcodes.NOP:
                        sb.append("NOP\n");
                        break;
                    case Opcodes.MORTAL_FLUSH:
                        sb.append("MORTAL_FLUSH\n");
                        break;
                    case Opcodes.MORTAL_PUSH_MARK:
                        sb.append("MORTAL_PUSH_MARK\n");
                        break;
                    case Opcodes.MORTAL_POP_FLUSH:
                        sb.append("MORTAL_POP_FLUSH\n");
                        break;
                    case Opcodes.SCOPE_EXIT_CLEANUP:
                        int secReg = interpretedCode.bytecode[pc++];
                        sb.append("SCOPE_EXIT_CLEANUP r").append(secReg).append("\n");
                        break;
                    case Opcodes.SCOPE_EXIT_CLEANUP_HASH:
                        int sechReg = interpretedCode.bytecode[pc++];
                        sb.append("SCOPE_EXIT_CLEANUP_HASH r").append(sechReg).append("\n");
                        break;
                    case Opcodes.SCOPE_EXIT_CLEANUP_ARRAY:
                        int secaReg = interpretedCode.bytecode[pc++];
                        sb.append("SCOPE_EXIT_CLEANUP_ARRAY r").append(secaReg).append("\n");
                        break;
                    case Opcodes.RETURN:
                        int retReg = interpretedCode.bytecode[pc++];
                        sb.append("RETURN r").append(retReg).append("\n");
                        break;
                    case Opcodes.RETURN_NONLOCAL:
                        int retNLReg = interpretedCode.bytecode[pc++];
                        sb.append("RETURN_NONLOCAL r").append(retNLReg).append("\n");
                        break;
                    case Opcodes.GOTO:
                        sb.append("GOTO ").append(InterpretedCode.readInt(interpretedCode.bytecode, pc)).append("\n");
                        pc += 1;
                        break;
                    case Opcodes.GOTO_DYNAMIC:
                        sb.append("GOTO_DYNAMIC r").append(interpretedCode.bytecode[pc++]).append("\n");
                        break;
                    case Opcodes.LAST:
                        sb.append("LAST ").append(InterpretedCode.readInt(interpretedCode.bytecode, pc)).append("\n");
                        pc += 1;
                        break;
                    case Opcodes.NEXT:
                        sb.append("NEXT ").append(InterpretedCode.readInt(interpretedCode.bytecode, pc)).append("\n");
                        pc += 1;
                        break;
                    case Opcodes.REDO:
                        sb.append("REDO ").append(InterpretedCode.readInt(interpretedCode.bytecode, pc)).append("\n");
                        pc += 1;
                        break;
                    case Opcodes.GOTO_IF_FALSE:
                        int condReg = interpretedCode.bytecode[pc++];
                        int target = InterpretedCode.readInt(interpretedCode.bytecode, pc);
                        pc += 1;
                        sb.append("GOTO_IF_FALSE r").append(condReg).append(" -> ").append(target).append("\n");
                        break;
                    case Opcodes.GOTO_IF_TRUE:
                        condReg = interpretedCode.bytecode[pc++];
                        target = InterpretedCode.readInt(interpretedCode.bytecode, pc);
                        pc += 1;
                        sb.append("GOTO_IF_TRUE r").append(condReg).append(" -> ").append(target).append("\n");
                        break;
                    case Opcodes.ALIAS:
                        int dest = interpretedCode.bytecode[pc++];
                        int src = interpretedCode.bytecode[pc++];
                        sb.append("ALIAS r").append(dest).append(" = r").append(src).append("\n");
                        break;
                    case Opcodes.LOAD_CONST:
                        int rd = interpretedCode.bytecode[pc++];
                        int constIdx = interpretedCode.bytecode[pc++];
                        sb.append("LOAD_CONST r").append(rd).append(" = constants[").append(constIdx).append("]");
                        if (interpretedCode.constants != null && constIdx < interpretedCode.constants.length) {
                            Object obj = interpretedCode.constants[constIdx];
                            sb.append(" (");
                            if (obj instanceof RuntimeScalar scalar) {
                                sb.append("RuntimeScalar{type=").append(scalar.type).append(", value=").append(scalar.value.getClass().getSimpleName()).append("}");
                            } else if (obj instanceof PerlRange range) {
                                // Special handling for PerlRange to avoid expanding large ranges
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
                        rd = interpretedCode.bytecode[pc++];
                        int value = InterpretedCode.readInt(interpretedCode.bytecode, pc);
                        pc += 1;
                        sb.append("LOAD_INT r").append(rd).append(" = ").append(value).append("\n");
                        break;
                    case Opcodes.LOAD_BYTE_STRING:
                    case Opcodes.LOAD_STRING:
                        rd = interpretedCode.bytecode[pc++];
                        int strIdx = interpretedCode.bytecode[pc++];
                        sb.append(opcode == Opcodes.LOAD_BYTE_STRING ? "LOAD_BYTE_STRING r" : "LOAD_STRING r")
                                .append(rd).append(" = \"");
                        if (interpretedCode.stringPool != null && strIdx < interpretedCode.stringPool.length) {
                            String str = interpretedCode.stringPool[strIdx];
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
                        rd = interpretedCode.bytecode[pc++];
                        strIdx = interpretedCode.bytecode[pc++];
                        sb.append("LOAD_VSTRING r").append(rd).append(" = v\"");
                        if (interpretedCode.stringPool != null && strIdx < interpretedCode.stringPool.length) {
                            sb.append(interpretedCode.stringPool[strIdx]);
                        }
                        sb.append("\"\n");
                        break;
                    case Opcodes.GLOB_OP: {
                        int globRd = interpretedCode.bytecode[pc++];
                        int globId = interpretedCode.bytecode[pc++];
                        int globPattern = interpretedCode.bytecode[pc++];
                        int globCtx = interpretedCode.bytecode[pc++];
                        sb.append("GLOB_OP r").append(globRd).append(" = glob(id=").append(globId)
                                .append(", r").append(globPattern).append(", ctx=").append(globCtx).append(")\n");
                        break;
                    }
                    case Opcodes.LOAD_UNDEF:
                        rd = interpretedCode.bytecode[pc++];
                        sb.append("LOAD_UNDEF r").append(rd).append("\n");
                        break;
                    case Opcodes.MY_SCALAR:
                        rd = interpretedCode.bytecode[pc++];
                        src = interpretedCode.bytecode[pc++];
                        sb.append("MY_SCALAR r").append(rd).append(" = r").append(src).append("\n");
                        break;
                    case Opcodes.LOAD_GLOBAL_SCALAR:
                        rd = interpretedCode.bytecode[pc++];
                        int nameIdx = interpretedCode.bytecode[pc++];
                        sb.append("LOAD_GLOBAL_SCALAR r").append(rd).append(" = $");
                        if (interpretedCode.stringPool != null && nameIdx >= 0 && nameIdx < interpretedCode.stringPool.length) {
                            sb.append(interpretedCode.stringPool[nameIdx]);
                        } else {
                            sb.append("<bad_string_idx:").append(nameIdx).append(">");
                        }
                        sb.append("\n");
                        break;
                    case Opcodes.LOAD_GLOBAL_ARRAY:
                        rd = interpretedCode.bytecode[pc++];
                        nameIdx = interpretedCode.bytecode[pc++];
                        sb.append("LOAD_GLOBAL_ARRAY r").append(rd).append(" = @");
                        if (interpretedCode.stringPool != null && nameIdx >= 0 && nameIdx < interpretedCode.stringPool.length) {
                            sb.append(interpretedCode.stringPool[nameIdx]);
                        } else {
                            sb.append("<bad_string_idx:").append(nameIdx).append(">");
                        }
                        sb.append("\n");
                        break;
                    case Opcodes.STORE_GLOBAL_ARRAY:
                        nameIdx = interpretedCode.bytecode[pc++];
                        int storeArraySrcReg = interpretedCode.bytecode[pc++];
                        sb.append("STORE_GLOBAL_ARRAY @");
                        if (interpretedCode.stringPool != null && nameIdx >= 0 && nameIdx < interpretedCode.stringPool.length) {
                            sb.append(interpretedCode.stringPool[nameIdx]);
                        } else {
                            sb.append("<bad_string_idx:").append(nameIdx).append(">");
                        }
                        sb.append(" = r").append(storeArraySrcReg).append("\n");
                        break;
                    case Opcodes.LOAD_GLOBAL_HASH:
                        rd = interpretedCode.bytecode[pc++];
                        nameIdx = interpretedCode.bytecode[pc++];
                        sb.append("LOAD_GLOBAL_HASH r").append(rd).append(" = %");
                        if (interpretedCode.stringPool != null && nameIdx >= 0 && nameIdx < interpretedCode.stringPool.length) {
                            sb.append(interpretedCode.stringPool[nameIdx]);
                        } else {
                            sb.append("<bad_string_idx:").append(nameIdx).append(">");
                        }
                        sb.append("\n");
                        break;
                    case Opcodes.STORE_GLOBAL_HASH:
                        nameIdx = interpretedCode.bytecode[pc++];
                        int storeHashSrcReg = interpretedCode.bytecode[pc++];
                        sb.append("STORE_GLOBAL_HASH %");
                        if (interpretedCode.stringPool != null && nameIdx >= 0 && nameIdx < interpretedCode.stringPool.length) {
                            sb.append(interpretedCode.stringPool[nameIdx]);
                        } else {
                            sb.append("<bad_string_idx:").append(nameIdx).append(">");
                        }
                        sb.append(" = r").append(storeHashSrcReg).append("\n");
                        break;
                    case Opcodes.LOAD_GLOBAL_CODE:
                        rd = interpretedCode.bytecode[pc++];
                        nameIdx = interpretedCode.bytecode[pc++];
                        sb.append("LOAD_GLOBAL_CODE r").append(rd).append(" = &");
                        if (interpretedCode.stringPool != null && nameIdx >= 0 && nameIdx < interpretedCode.stringPool.length) {
                            sb.append(interpretedCode.stringPool[nameIdx]);
                        } else {
                            sb.append("<bad_string_idx:").append(nameIdx).append(">");
                        }
                        sb.append("\n");
                        break;
                    case Opcodes.STORE_GLOBAL_SCALAR:
                        nameIdx = interpretedCode.bytecode[pc++];
                        int srcReg = interpretedCode.bytecode[pc++];
                        sb.append("STORE_GLOBAL_SCALAR $");
                        if (interpretedCode.stringPool != null && nameIdx >= 0 && nameIdx < interpretedCode.stringPool.length) {
                            sb.append(interpretedCode.stringPool[nameIdx]);
                        } else {
                            sb.append("<bad_string_idx:").append(nameIdx).append(">");
                        }
                        sb.append(" = r").append(srcReg).append("\n");
                        break;
                    case Opcodes.HASH_KEYVALUE_SLICE: {
                        rd = interpretedCode.bytecode[pc++];
                        int kvRs1 = interpretedCode.bytecode[pc++];  // hash register
                        int kvRs2 = interpretedCode.bytecode[pc++];  // keys list register
                        sb.append("HASH_KEYVALUE_SLICE r").append(rd)
                                .append(" = r").append(kvRs1)
                                .append(".getKeyValueSlice(r").append(kvRs2).append(")\n");
                        break;
                    }
                    case Opcodes.ADD_SCALAR:
                        rd = interpretedCode.bytecode[pc++];
                        int addRs1 = interpretedCode.bytecode[pc++];
                        int addRs2 = interpretedCode.bytecode[pc++];
                        sb.append("ADD_SCALAR r").append(rd).append(" = r").append(addRs1).append(" + r").append(addRs2).append("\n");
                        break;
                    case Opcodes.SUB_SCALAR:
                        rd = interpretedCode.bytecode[pc++];
                        rs1 = interpretedCode.bytecode[pc++];
                        rs2 = interpretedCode.bytecode[pc++];
                        sb.append("SUB_SCALAR r").append(rd).append(" = r").append(rs1).append(" - r").append(rs2).append("\n");
                        break;
                    case Opcodes.MUL_SCALAR:
                        rd = interpretedCode.bytecode[pc++];
                        rs1 = interpretedCode.bytecode[pc++];
                        rs2 = interpretedCode.bytecode[pc++];
                        sb.append("MUL_SCALAR r").append(rd).append(" = r").append(rs1).append(" * r").append(rs2).append("\n");
                        break;
                    case Opcodes.DIV_SCALAR:
                        rd = interpretedCode.bytecode[pc++];
                        rs1 = interpretedCode.bytecode[pc++];
                        rs2 = interpretedCode.bytecode[pc++];
                        sb.append("DIV_SCALAR r").append(rd).append(" = r").append(rs1).append(" / r").append(rs2).append("\n");
                        break;
                    case Opcodes.MOD_SCALAR:
                        rd = interpretedCode.bytecode[pc++];
                        rs1 = interpretedCode.bytecode[pc++];
                        rs2 = interpretedCode.bytecode[pc++];
                        sb.append("MOD_SCALAR r").append(rd).append(" = r").append(rs1).append(" % r").append(rs2).append("\n");
                        break;
                    case Opcodes.POW_SCALAR:
                        rd = interpretedCode.bytecode[pc++];
                        rs1 = interpretedCode.bytecode[pc++];
                        rs2 = interpretedCode.bytecode[pc++];
                        sb.append("POW_SCALAR r").append(rd).append(" = r").append(rs1).append(" ** r").append(rs2).append("\n");
                        break;
                    case Opcodes.NEG_SCALAR:
                        rd = interpretedCode.bytecode[pc++];
                        int rsNeg = interpretedCode.bytecode[pc++];
                        sb.append("NEG_SCALAR r").append(rd).append(" = -r").append(rsNeg).append("\n");
                        break;
                    case Opcodes.ADD_SCALAR_INT:
                        rd = interpretedCode.bytecode[pc++];
                        int rs = interpretedCode.bytecode[pc++];
                        int imm = InterpretedCode.readInt(interpretedCode.bytecode, pc);
                        pc += 1;
                        sb.append("ADD_SCALAR_INT r").append(rd).append(" = r").append(rs).append(" + ").append(imm).append("\n");
                        break;
                    case Opcodes.SUB_SCALAR_INT:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        int subImm = InterpretedCode.readInt(interpretedCode.bytecode, pc);
                        pc += 1;
                        sb.append("SUB_SCALAR_INT r").append(rd).append(" = r").append(rs).append(" - ").append(subImm).append("\n");
                        break;
                    case Opcodes.MUL_SCALAR_INT:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        int mulImm = InterpretedCode.readInt(interpretedCode.bytecode, pc);
                        pc += 1;
                        sb.append("MUL_SCALAR_INT r").append(rd).append(" = r").append(rs).append(" * ").append(mulImm).append("\n");
                        break;
                    case Opcodes.CONCAT:
                        rd = interpretedCode.bytecode[pc++];
                        rs1 = interpretedCode.bytecode[pc++];
                        rs2 = interpretedCode.bytecode[pc++];
                        sb.append("CONCAT r").append(rd).append(" = r").append(rs1).append(" . r").append(rs2).append("\n");
                        break;
                    case Opcodes.REPEAT:
                        rd = interpretedCode.bytecode[pc++];
                        rs1 = interpretedCode.bytecode[pc++];
                        rs2 = interpretedCode.bytecode[pc++];
                        sb.append("REPEAT r").append(rd).append(" = r").append(rs1).append(" x r").append(rs2).append("\n");
                        break;
                    case Opcodes.LT_NUM:
                        rd = interpretedCode.bytecode[pc++];
                        rs1 = interpretedCode.bytecode[pc++];
                        rs2 = interpretedCode.bytecode[pc++];
                        sb.append("LT_NUM r").append(rd).append(" = r").append(rs1).append(" < r").append(rs2).append("\n");
                        break;
                    case Opcodes.GT_NUM:
                        rd = interpretedCode.bytecode[pc++];
                        rs1 = interpretedCode.bytecode[pc++];
                        rs2 = interpretedCode.bytecode[pc++];
                        sb.append("GT_NUM r").append(rd).append(" = r").append(rs1).append(" > r").append(rs2).append("\n");
                        break;
                    case Opcodes.LE_NUM:
                        rd = interpretedCode.bytecode[pc++];
                        rs1 = interpretedCode.bytecode[pc++];
                        rs2 = interpretedCode.bytecode[pc++];
                        sb.append("LE_NUM r").append(rd).append(" = r").append(rs1).append(" <= r").append(rs2).append("\n");
                        break;
                    case Opcodes.GE_NUM:
                        rd = interpretedCode.bytecode[pc++];
                        rs1 = interpretedCode.bytecode[pc++];
                        rs2 = interpretedCode.bytecode[pc++];
                        sb.append("GE_NUM r").append(rd).append(" = r").append(rs1).append(" >= r").append(rs2).append("\n");
                        break;
                    case Opcodes.NE_NUM:
                        rd = interpretedCode.bytecode[pc++];
                        rs1 = interpretedCode.bytecode[pc++];
                        rs2 = interpretedCode.bytecode[pc++];
                        sb.append("NE_NUM r").append(rd).append(" = r").append(rs1).append(" != r").append(rs2).append("\n");
                        break;
                    case Opcodes.INC_REG:
                        rd = interpretedCode.bytecode[pc++];
                        sb.append("INC_REG r").append(rd).append("++\n");
                        break;
                    case Opcodes.DEC_REG:
                        rd = interpretedCode.bytecode[pc++];
                        sb.append("DEC_REG r").append(rd).append("--\n");
                        break;
                    case Opcodes.ADD_ASSIGN:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("ADD_ASSIGN r").append(rd).append(" += r").append(rs).append("\n");
                        break;
                    case Opcodes.ADD_ASSIGN_INT:
                        rd = interpretedCode.bytecode[pc++];
                        imm = InterpretedCode.readInt(interpretedCode.bytecode, pc);
                        pc += 1;
                        sb.append("ADD_ASSIGN_INT r").append(rd).append(" += ").append(imm).append("\n");
                        break;
                    case Opcodes.STRING_CONCAT_ASSIGN:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("STRING_CONCAT_ASSIGN r").append(rd).append(" .= r").append(rs).append("\n");
                        break;
                    case Opcodes.BITWISE_AND_ASSIGN:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("BITWISE_AND_ASSIGN r").append(rd).append(" &= r").append(rs).append("\n");
                        break;
                    case Opcodes.BITWISE_OR_ASSIGN:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("BITWISE_OR_ASSIGN r").append(rd).append(" |= r").append(rs).append("\n");
                        break;
                    case Opcodes.BITWISE_XOR_ASSIGN:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("BITWISE_XOR_ASSIGN r").append(rd).append(" ^= r").append(rs).append("\n");
                        break;
                    case Opcodes.STRING_BITWISE_AND_ASSIGN:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("STRING_BITWISE_AND_ASSIGN r").append(rd).append(" &.= r").append(rs).append("\n");
                        break;
                    case Opcodes.STRING_BITWISE_OR_ASSIGN:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("STRING_BITWISE_OR_ASSIGN r").append(rd).append(" |.= r").append(rs).append("\n");
                        break;
                    case Opcodes.STRING_BITWISE_XOR_ASSIGN:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("STRING_BITWISE_XOR_ASSIGN r").append(rd).append(" ^.= r").append(rs).append("\n");
                        break;
                    case Opcodes.BITWISE_AND_BINARY:
                        rd = interpretedCode.bytecode[pc++];
                        int andRs1 = interpretedCode.bytecode[pc++];
                        int andRs2 = interpretedCode.bytecode[pc++];
                        sb.append("BITWISE_AND_BINARY r").append(rd).append(" = r").append(andRs1).append(" & r").append(andRs2).append("\n");
                        break;
                    case Opcodes.BITWISE_OR_BINARY:
                        rd = interpretedCode.bytecode[pc++];
                        int orRs1 = interpretedCode.bytecode[pc++];
                        int orRs2 = interpretedCode.bytecode[pc++];
                        sb.append("BITWISE_OR_BINARY r").append(rd).append(" = r").append(orRs1).append(" | r").append(orRs2).append("\n");
                        break;
                    case Opcodes.BITWISE_XOR_BINARY:
                        rd = interpretedCode.bytecode[pc++];
                        int xorRs1 = interpretedCode.bytecode[pc++];
                        int xorRs2 = interpretedCode.bytecode[pc++];
                        sb.append("BITWISE_XOR_BINARY r").append(rd).append(" = r").append(xorRs1).append(" ^ r").append(xorRs2).append("\n");
                        break;
                    case Opcodes.STRING_BITWISE_AND:
                        rd = interpretedCode.bytecode[pc++];
                        int strAndRs1 = interpretedCode.bytecode[pc++];
                        int strAndRs2 = interpretedCode.bytecode[pc++];
                        sb.append("STRING_BITWISE_AND r").append(rd).append(" = r").append(strAndRs1).append(" &. r").append(strAndRs2).append("\n");
                        break;
                    case Opcodes.STRING_BITWISE_OR:
                        rd = interpretedCode.bytecode[pc++];
                        int strOrRs1 = interpretedCode.bytecode[pc++];
                        int strOrRs2 = interpretedCode.bytecode[pc++];
                        sb.append("STRING_BITWISE_OR r").append(rd).append(" = r").append(strOrRs1).append(" |. r").append(strOrRs2).append("\n");
                        break;
                    case Opcodes.STRING_BITWISE_XOR:
                        rd = interpretedCode.bytecode[pc++];
                        int strXorRs1 = interpretedCode.bytecode[pc++];
                        int strXorRs2 = interpretedCode.bytecode[pc++];
                        sb.append("STRING_BITWISE_XOR r").append(rd).append(" = r").append(strXorRs1).append(" ^. r").append(strXorRs2).append("\n");
                        break;
                    case Opcodes.BITWISE_NOT_BINARY:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("BITWISE_NOT_BINARY r").append(rd).append(" = ~r").append(rs).append("\n");
                        break;
                    case Opcodes.BITWISE_NOT_STRING:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("BITWISE_NOT_STRING r").append(rd).append(" = ~.r").append(rs).append("\n");
                        break;
                    case Opcodes.STAT:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        int statCtx = interpretedCode.bytecode[pc++];
                        sb.append("STAT r").append(rd).append(" = stat(r").append(rs).append(", ctx=").append(statCtx).append(")\n");
                        break;
                    case Opcodes.LSTAT:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        int lstatCtx = interpretedCode.bytecode[pc++];
                        sb.append("LSTAT r").append(rd).append(" = lstat(r").append(rs).append(", ctx=").append(lstatCtx).append(")\n");
                        break;
                    case Opcodes.STAT_LASTHANDLE:
                        rd = interpretedCode.bytecode[pc++];
                        int slhCtx = interpretedCode.bytecode[pc++];
                        sb.append("STAT_LASTHANDLE r").append(rd).append(" = stat(_, ctx=").append(slhCtx).append(")\n");
                        break;
                    case Opcodes.LSTAT_LASTHANDLE:
                        rd = interpretedCode.bytecode[pc++];
                        int llhCtx = interpretedCode.bytecode[pc++];
                        sb.append("LSTAT_LASTHANDLE r").append(rd).append(" = lstat(_, ctx=").append(llhCtx).append(")\n");
                        break;
                    case Opcodes.FILETEST_R:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("FILETEST_R r").append(rd).append(" = -r r").append(rs).append("\n");
                        break;
                    case Opcodes.FILETEST_W:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("FILETEST_W r").append(rd).append(" = -w r").append(rs).append("\n");
                        break;
                    case Opcodes.FILETEST_X:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("FILETEST_X r").append(rd).append(" = -x r").append(rs).append("\n");
                        break;
                    case Opcodes.FILETEST_O:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("FILETEST_O r").append(rd).append(" = -o r").append(rs).append("\n");
                        break;
                    case Opcodes.FILETEST_R_REAL:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("FILETEST_R_REAL r").append(rd).append(" = -R r").append(rs).append("\n");
                        break;
                    case Opcodes.FILETEST_W_REAL:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("FILETEST_W_REAL r").append(rd).append(" = -W r").append(rs).append("\n");
                        break;
                    case Opcodes.FILETEST_X_REAL:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("FILETEST_X_REAL r").append(rd).append(" = -X r").append(rs).append("\n");
                        break;
                    case Opcodes.FILETEST_O_REAL:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("FILETEST_O_REAL r").append(rd).append(" = -O r").append(rs).append("\n");
                        break;
                    case Opcodes.FILETEST_E:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("FILETEST_E r").append(rd).append(" = -e r").append(rs).append("\n");
                        break;
                    case Opcodes.FILETEST_Z:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("FILETEST_Z r").append(rd).append(" = -z r").append(rs).append("\n");
                        break;
                    case Opcodes.FILETEST_S:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("FILETEST_S r").append(rd).append(" = -s r").append(rs).append("\n");
                        break;
                    case Opcodes.FILETEST_F:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("FILETEST_F r").append(rd).append(" = -f r").append(rs).append("\n");
                        break;
                    case Opcodes.FILETEST_D:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("FILETEST_D r").append(rd).append(" = -d r").append(rs).append("\n");
                        break;
                    case Opcodes.FILETEST_L:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("FILETEST_L r").append(rd).append(" = -l r").append(rs).append("\n");
                        break;
                    case Opcodes.FILETEST_P:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("FILETEST_P r").append(rd).append(" = -p r").append(rs).append("\n");
                        break;
                    case Opcodes.FILETEST_S_UPPER:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("FILETEST_S_UPPER r").append(rd).append(" = -S r").append(rs).append("\n");
                        break;
                    case Opcodes.FILETEST_B:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("FILETEST_B r").append(rd).append(" = -b r").append(rs).append("\n");
                        break;
                    case Opcodes.FILETEST_C:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("FILETEST_C r").append(rd).append(" = -c r").append(rs).append("\n");
                        break;
                    case Opcodes.FILETEST_T:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("FILETEST_T r").append(rd).append(" = -t r").append(rs).append("\n");
                        break;
                    case Opcodes.FILETEST_U:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("FILETEST_U r").append(rd).append(" = -u r").append(rs).append("\n");
                        break;
                    case Opcodes.FILETEST_G:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("FILETEST_G r").append(rd).append(" = -g r").append(rs).append("\n");
                        break;
                    case Opcodes.FILETEST_K:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("FILETEST_K r").append(rd).append(" = -k r").append(rs).append("\n");
                        break;
                    case Opcodes.FILETEST_T_UPPER:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("FILETEST_T_UPPER r").append(rd).append(" = -T r").append(rs).append("\n");
                        break;
                    case Opcodes.FILETEST_B_UPPER:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("FILETEST_B_UPPER r").append(rd).append(" = -B r").append(rs).append("\n");
                        break;
                    case Opcodes.FILETEST_M:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("FILETEST_M r").append(rd).append(" = -M r").append(rs).append("\n");
                        break;
                    case Opcodes.FILETEST_A:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("FILETEST_A r").append(rd).append(" = -A r").append(rs).append("\n");
                        break;
                    case Opcodes.FILETEST_C_UPPER:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("FILETEST_C_UPPER r").append(rd).append(" = -C r").append(rs).append("\n");
                        break;
                    case Opcodes.FILETEST_LASTHANDLE:
                        rd = interpretedCode.bytecode[pc++];
                        int opStrIdx = interpretedCode.bytecode[pc++];
                        sb.append("FILETEST_LASTHANDLE r").append(rd).append(" = ").append(interpretedCode.stringPool[opStrIdx]).append(" _\n");
                        break;
                    case Opcodes.GLOB_SLOT_GET:
                        rd = interpretedCode.bytecode[pc++];
                        int globReg2 = interpretedCode.bytecode[pc++];
                        int keyReg = interpretedCode.bytecode[pc++];
                        sb.append("GLOB_SLOT_GET r").append(rd).append(" = r").append(globReg2).append("{r").append(keyReg).append("}\n");
                        break;
                    case Opcodes.SPRINTF:
                        rd = interpretedCode.bytecode[pc++];
                        int formatReg = interpretedCode.bytecode[pc++];
                        int argsListReg = interpretedCode.bytecode[pc++];
                        sb.append("SPRINTF r").append(rd).append(" = sprintf(r").append(formatReg).append(", r").append(argsListReg).append(")\n");
                        break;
                    case Opcodes.CHOP:
                        rd = interpretedCode.bytecode[pc++];
                        int scalarReg = interpretedCode.bytecode[pc++];
                        sb.append("CHOP r").append(rd).append(" = chop(r").append(scalarReg).append(")\n");
                        break;
                    case Opcodes.GET_REPLACEMENT_REGEX:
                        rd = interpretedCode.bytecode[pc++];
                        rs1 = interpretedCode.bytecode[pc++];  // pattern
                        rs2 = interpretedCode.bytecode[pc++];  // replacement
                        int rs3 = interpretedCode.bytecode[pc++];  // flags
                        int callerArgsReg = interpretedCode.bytecode[pc++];  // caller @_
                        sb.append("GET_REPLACEMENT_REGEX r").append(rd).append(" = getReplacementRegex(r").append(rs1).append(", r").append(rs2).append(", r").append(rs3).append(", r").append(callerArgsReg).append(")\n");
                        break;
                    case Opcodes.SUBSTR_VAR:
                        rd = interpretedCode.bytecode[pc++];
                        int substrArgsReg = interpretedCode.bytecode[pc++];
                        int substrCtx = interpretedCode.bytecode[pc++];
                        sb.append("SUBSTR_VAR r").append(rd).append(" = substr(r").append(substrArgsReg).append(", ctx=").append(substrCtx).append(")\n");
                        break;
                    case Opcodes.SUBSTR_VAR_NO_WARN:
                        rd = interpretedCode.bytecode[pc++];
                        int substrNoWarnArgsReg = interpretedCode.bytecode[pc++];
                        int substrNoWarnCtx = interpretedCode.bytecode[pc++];
                        sb.append("SUBSTR_VAR_NO_WARN r").append(rd).append(" = substrNoWarn(r").append(substrNoWarnArgsReg).append(", ctx=").append(substrNoWarnCtx).append(")\n");
                        break;
                    case Opcodes.PUSH_LOCAL_VARIABLE:
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("PUSH_LOCAL_VARIABLE r").append(rs).append("\n");
                        break;
                    case Opcodes.STORE_GLOB:
                        int globReg = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("STORE_GLOB r").append(globReg).append(" = r").append(rs).append("\n");
                        break;
                    case Opcodes.OPEN:
                        rd = interpretedCode.bytecode[pc++];
                        int openCtx = interpretedCode.bytecode[pc++];
                        int openArgs = interpretedCode.bytecode[pc++];
                        sb.append("OPEN r").append(rd).append(" = open(ctx=").append(openCtx).append(", r").append(openArgs).append(")\n");
                        break;
                    case Opcodes.READLINE:
                        rd = interpretedCode.bytecode[pc++];
                        int fhReg = interpretedCode.bytecode[pc++];
                        int readCtx = interpretedCode.bytecode[pc++];
                        sb.append("READLINE r").append(rd).append(" = readline(r").append(fhReg).append(", ctx=").append(readCtx).append(")\n");
                        break;
                    case Opcodes.MATCH_REGEX:
                        rd = interpretedCode.bytecode[pc++];
                        int strReg = interpretedCode.bytecode[pc++];
                        int regReg = interpretedCode.bytecode[pc++];
                        int matchCtx = interpretedCode.bytecode[pc++];
                        sb.append("MATCH_REGEX r").append(rd).append(" = r").append(strReg).append(" =~ r").append(regReg).append(" (ctx=").append(matchCtx).append(")\n");
                        break;
                    case Opcodes.MATCH_REGEX_NOT:
                        rd = interpretedCode.bytecode[pc++];
                        strReg = interpretedCode.bytecode[pc++];
                        regReg = interpretedCode.bytecode[pc++];
                        matchCtx = interpretedCode.bytecode[pc++];
                        sb.append("MATCH_REGEX_NOT r").append(rd).append(" = r").append(strReg).append(" !~ r").append(regReg).append(" (ctx=").append(matchCtx).append(")\n");
                        break;
                    case Opcodes.CHOMP:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("CHOMP r").append(rd).append(" = chomp(r").append(rs).append(")\n");
                        break;
                    case Opcodes.WANTARRAY:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("WANTARRAY r").append(rd).append(" = wantarray(r").append(rs).append(")\n");
                        break;
                    case Opcodes.REQUIRE:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("REQUIRE r").append(rd).append(" = require(r").append(rs).append(")\n");
                        break;
                    case Opcodes.POS:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("POS r").append(rd).append(" = pos(r").append(rs).append(")\n");
                        break;
                    case Opcodes.INDEX: {
                        rd = interpretedCode.bytecode[pc++];
                        int idxStrReg = interpretedCode.bytecode[pc++];
                        int idxSubstrReg = interpretedCode.bytecode[pc++];
                        int idxPosReg = interpretedCode.bytecode[pc++];
                        sb.append("INDEX r").append(rd).append(" = index(r").append(idxStrReg).append(", r").append(idxSubstrReg).append(", r").append(idxPosReg).append(")\n");
                        break;
                    }
                    case Opcodes.RINDEX: {
                        rd = interpretedCode.bytecode[pc++];
                        int ridxStrReg = interpretedCode.bytecode[pc++];
                        int ridxSubstrReg = interpretedCode.bytecode[pc++];
                        int ridxPosReg = interpretedCode.bytecode[pc++];
                        sb.append("RINDEX r").append(rd).append(" = rindex(r").append(ridxStrReg).append(", r").append(ridxSubstrReg).append(", r").append(ridxPosReg).append(")\n");
                        break;
                    }
                    case Opcodes.PRE_AUTOINCREMENT:
                        rd = interpretedCode.bytecode[pc++];
                        sb.append("PRE_AUTOINCREMENT ++r").append(rd).append("\n");
                        break;
                    case Opcodes.POST_AUTOINCREMENT:
                        rd = interpretedCode.bytecode[pc++];
                        int postIncSrc = interpretedCode.bytecode[pc++];
                        sb.append("POST_AUTOINCREMENT r").append(rd).append(" = r").append(postIncSrc).append("++\n");
                        break;
                    case Opcodes.PRE_AUTODECREMENT:
                        rd = interpretedCode.bytecode[pc++];
                        sb.append("PRE_AUTODECREMENT --r").append(rd).append("\n");
                        break;
                    case Opcodes.POST_AUTODECREMENT:
                        rd = interpretedCode.bytecode[pc++];
                        int postDecSrc = interpretedCode.bytecode[pc++];
                        sb.append("POST_AUTODECREMENT r").append(rd).append(" = r").append(postDecSrc).append("--\n");
                        break;
                    case Opcodes.PRINT: {
                        int contentReg = interpretedCode.bytecode[pc++];
                        int filehandleReg = interpretedCode.bytecode[pc++];
                        sb.append("PRINT r").append(contentReg).append(", fh=r").append(filehandleReg).append("\n");
                        break;
                    }
                    case Opcodes.SAY: {
                        int contentReg = interpretedCode.bytecode[pc++];
                        int filehandleReg = interpretedCode.bytecode[pc++];
                        sb.append("SAY r").append(contentReg).append(", fh=r").append(filehandleReg).append("\n");
                        break;
                    }
                    case Opcodes.CREATE_REF:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("CREATE_REF r").append(rd).append(" = \\r").append(rs).append("\n");
                        break;
                    case Opcodes.DEREF:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("DEREF r").append(rd).append(" = ${r").append(rs).append("}\n");
                        break;
                    case Opcodes.GET_TYPE:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("GET_TYPE r").append(rd).append(" = type(r").append(rs).append(")\n");
                        break;
                    case Opcodes.DIE:
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("DIE r").append(rs).append("\n");
                        break;
                    case Opcodes.WARN:
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("WARN r").append(rs).append("\n");
                        break;
                    case Opcodes.EVAL_TRY: {
                        // Read catch target as single int slot (matches emitInt/readInt)
                        int catchPc = interpretedCode.bytecode[pc++];
                        sb.append("EVAL_TRY catch_at=").append(catchPc).append("\n");
                        break;
                    }
                    case Opcodes.EVAL_END:
                        sb.append("EVAL_END\n");
                        break;
                    case Opcodes.EVAL_CATCH:
                        rd = interpretedCode.bytecode[pc++];
                        sb.append("EVAL_CATCH r").append(rd).append("\n");
                        break;
                    case Opcodes.ARRAY_GET:
                        rd = interpretedCode.bytecode[pc++];
                        int arrayReg = interpretedCode.bytecode[pc++];
                        int indexReg = interpretedCode.bytecode[pc++];
                        sb.append("ARRAY_GET r").append(rd).append(" = r").append(arrayReg).append("[r").append(indexReg).append("]\n");
                        break;
                    case Opcodes.ARRAY_SET:
                        rd = interpretedCode.bytecode[pc++];
                        arrayReg = interpretedCode.bytecode[pc++];
                        indexReg = interpretedCode.bytecode[pc++];
                        int arraySetValueReg = interpretedCode.bytecode[pc++];
                        sb.append("ARRAY_SET r").append(rd).append(" = r").append(arrayReg).append("[r").append(indexReg).append("] = r").append(arraySetValueReg).append("\n");
                        break;
                    case Opcodes.ARRAY_PUSH:
                        arrayReg = interpretedCode.bytecode[pc++];
                        int arrayPushValueReg = interpretedCode.bytecode[pc++];
                        sb.append("ARRAY_PUSH r").append(arrayReg).append(".push(r").append(arrayPushValueReg).append(")\n");
                        break;
                    case Opcodes.ARRAY_POP:
                        rd = interpretedCode.bytecode[pc++];
                        arrayReg = interpretedCode.bytecode[pc++];
                        sb.append("ARRAY_POP r").append(rd).append(" = r").append(arrayReg).append(".pop()\n");
                        break;
                    case Opcodes.ARRAY_SHIFT:
                        rd = interpretedCode.bytecode[pc++];
                        arrayReg = interpretedCode.bytecode[pc++];
                        sb.append("ARRAY_SHIFT r").append(rd).append(" = r").append(arrayReg).append(".shift()\n");
                        break;
                    case Opcodes.ARRAY_UNSHIFT:
                        arrayReg = interpretedCode.bytecode[pc++];
                        int arrayUnshiftValueReg = interpretedCode.bytecode[pc++];
                        sb.append("ARRAY_UNSHIFT r").append(arrayReg).append(".unshift(r").append(arrayUnshiftValueReg).append(")\n");
                        break;
                    case Opcodes.ARRAY_SIZE:
                        rd = interpretedCode.bytecode[pc++];
                        arrayReg = interpretedCode.bytecode[pc++];
                        sb.append("ARRAY_SIZE r").append(rd).append(" = size(r").append(arrayReg).append(")\n");
                        break;
                    case Opcodes.CREATE_ARRAY:
                        rd = interpretedCode.bytecode[pc++];
                        int listSourceReg = interpretedCode.bytecode[pc++];
                        sb.append("CREATE_ARRAY r").append(rd).append(" = array(r").append(listSourceReg).append(")\n");
                        break;
                    case Opcodes.HASH_GET:
                        rd = interpretedCode.bytecode[pc++];
                        int hashGetReg = interpretedCode.bytecode[pc++];
                        int keyGetReg = interpretedCode.bytecode[pc++];
                        sb.append("HASH_GET r").append(rd).append(" = r").append(hashGetReg).append("{r").append(keyGetReg).append("}\n");
                        break;
                    case Opcodes.HASH_SET:
                        rd = interpretedCode.bytecode[pc++];
                        int hashSetReg = interpretedCode.bytecode[pc++];
                        int keySetReg = interpretedCode.bytecode[pc++];
                        int valueSetReg = interpretedCode.bytecode[pc++];
                        sb.append("HASH_SET r").append(rd).append(" = r").append(hashSetReg).append("{r").append(keySetReg).append("} = r").append(valueSetReg).append("\n");
                        break;
                    case Opcodes.HASH_EXISTS:
                        rd = interpretedCode.bytecode[pc++];
                        int hashExistsReg = interpretedCode.bytecode[pc++];
                        int keyExistsReg = interpretedCode.bytecode[pc++];
                        sb.append("HASH_EXISTS r").append(rd).append(" = exists r").append(hashExistsReg).append("{r").append(keyExistsReg).append("}\n");
                        break;
                    case Opcodes.HASH_DELETE:
                        rd = interpretedCode.bytecode[pc++];
                        int hashDeleteReg = interpretedCode.bytecode[pc++];
                        int keyDeleteReg = interpretedCode.bytecode[pc++];
                        sb.append("HASH_DELETE r").append(rd).append(" = delete r").append(hashDeleteReg).append("{r").append(keyDeleteReg).append("}\n");
                        break;
                    case Opcodes.ARRAY_EXISTS:
                        rd = interpretedCode.bytecode[pc++];
                        int arrExistsReg = interpretedCode.bytecode[pc++];
                        int idxExistsReg = interpretedCode.bytecode[pc++];
                        sb.append("ARRAY_EXISTS r").append(rd).append(" = exists r").append(arrExistsReg).append("[r").append(idxExistsReg).append("]\n");
                        break;
                    case Opcodes.ARRAY_DELETE:
                        rd = interpretedCode.bytecode[pc++];
                        int arrDeleteReg = interpretedCode.bytecode[pc++];
                        int idxDeleteReg = interpretedCode.bytecode[pc++];
                        sb.append("ARRAY_DELETE r").append(rd).append(" = delete r").append(arrDeleteReg).append("[r").append(idxDeleteReg).append("]\n");
                        break;
                    case Opcodes.HASH_DELETE_LOCAL:
                        rd = interpretedCode.bytecode[pc++];
                        int hashDLReg = interpretedCode.bytecode[pc++];
                        int keyDLReg = interpretedCode.bytecode[pc++];
                        sb.append("HASH_DELETE_LOCAL r").append(rd).append(" = delete local r").append(hashDLReg).append("{r").append(keyDLReg).append("}\n");
                        break;
                    case Opcodes.ARRAY_DELETE_LOCAL:
                        rd = interpretedCode.bytecode[pc++];
                        int arrDLReg = interpretedCode.bytecode[pc++];
                        int idxDLReg = interpretedCode.bytecode[pc++];
                        sb.append("ARRAY_DELETE_LOCAL r").append(rd).append(" = delete local r").append(arrDLReg).append("[r").append(idxDLReg).append("]\n");
                        break;
                    case Opcodes.HASH_SLICE_DELETE_LOCAL: {
                        rd = interpretedCode.bytecode[pc++];
                        int hsdlHashReg = interpretedCode.bytecode[pc++];
                        int hsdlKeysReg = interpretedCode.bytecode[pc++];
                        sb.append("HASH_SLICE_DELETE_LOCAL r").append(rd).append(" = delete local r").append(hsdlHashReg)
                                .append("{r").append(hsdlKeysReg).append("}\n");
                        break;
                    }
                    case Opcodes.ARRAY_SLICE_DELETE_LOCAL: {
                        rd = interpretedCode.bytecode[pc++];
                        int asdlArrayReg = interpretedCode.bytecode[pc++];
                        int asdlIndicesReg = interpretedCode.bytecode[pc++];
                        sb.append("ARRAY_SLICE_DELETE_LOCAL r").append(rd).append(" = delete local r").append(asdlArrayReg)
                                .append("[r").append(asdlIndicesReg).append("]\n");
                        break;
                    }
                    case Opcodes.VIVIFY_LVALUE: {
                        int vivReg = interpretedCode.bytecode[pc++];
                        sb.append("VIVIFY_LVALUE r").append(vivReg).append("\n");
                        break;
                    }
                    case Opcodes.LIST_SLICE: {
                        rd = interpretedCode.bytecode[pc++];
                        int lsListReg = interpretedCode.bytecode[pc++];
                        int lsIndicesReg = interpretedCode.bytecode[pc++];
                        sb.append("LIST_SLICE r").append(rd).append(" = r").append(lsListReg)
                                .append(".getSlice(r").append(lsIndicesReg).append(")\n");
                        break;
                    }
                    case Opcodes.HASH_KEYS:
                        rd = interpretedCode.bytecode[pc++];
                        int hashKeysReg = interpretedCode.bytecode[pc++];
                        sb.append("HASH_KEYS r").append(rd).append(" = keys(r").append(hashKeysReg).append(")\n");
                        break;
                    case Opcodes.HASH_VALUES:
                        rd = interpretedCode.bytecode[pc++];
                        int hashValuesReg = interpretedCode.bytecode[pc++];
                        sb.append("HASH_VALUES r").append(rd).append(" = values(r").append(hashValuesReg).append(")\n");
                        break;
                    case Opcodes.CREATE_LIST: {
                        rd = interpretedCode.bytecode[pc++];
                        int listCount = interpretedCode.bytecode[pc++];
                        sb.append("CREATE_LIST r").append(rd).append(" = [");
                        for (int i = 0; i < listCount; i++) {
                            if (i > 0) sb.append(", ");
                            int listRs = interpretedCode.bytecode[pc++];
                            sb.append("r").append(listRs);
                        }
                        sb.append("]\n");
                        break;
                    }
                    case Opcodes.CALL_SUB:
                    case Opcodes.CALL_SUB_SHARE_ARGS:
                        rd = interpretedCode.bytecode[pc++];
                        int coderefReg = interpretedCode.bytecode[pc++];
                        int argsReg = interpretedCode.bytecode[pc++];
                        int ctx = interpretedCode.bytecode[pc++];
                        sb.append(opcode == Opcodes.CALL_SUB_SHARE_ARGS ? "CALL_SUB_SHARE_ARGS r" : "CALL_SUB r")
                                .append(rd).append(" = r").append(coderefReg)
                                .append("->(r").append(argsReg).append(", ctx=").append(ctx).append(")\n");
                        break;
                    case Opcodes.CALL_METHOD:
                        rd = interpretedCode.bytecode[pc++];
                        int invocantReg = interpretedCode.bytecode[pc++];
                        int methodReg = interpretedCode.bytecode[pc++];
                        int currentSubReg = interpretedCode.bytecode[pc++];
                        argsReg = interpretedCode.bytecode[pc++];
                        ctx = interpretedCode.bytecode[pc++];
                        sb.append("CALL_METHOD r").append(rd).append(" = r").append(invocantReg)
                                .append("->r").append(methodReg)
                                .append("(r").append(argsReg).append(", sub=r").append(currentSubReg)
                                .append(", ctx=").append(ctx).append(")\n");
                        break;
                    case Opcodes.JOIN:
                        rd = interpretedCode.bytecode[pc++];
                        int separatorReg = interpretedCode.bytecode[pc++];
                        int listReg = interpretedCode.bytecode[pc++];
                        sb.append("JOIN r").append(rd).append(" = join(r").append(separatorReg)
                                .append(", r").append(listReg).append(")\n");
                        break;
                    case Opcodes.JOIN_NO_OVERLOAD:
                        rd = interpretedCode.bytecode[pc++];
                        separatorReg = interpretedCode.bytecode[pc++];
                        listReg = interpretedCode.bytecode[pc++];
                        sb.append("JOIN_NO_OVERLOAD r").append(rd).append(" = joinNoOverload(r").append(separatorReg)
                                .append(", r").append(listReg).append(")\n");
                        break;
                    case Opcodes.CONCAT_NO_OVERLOAD:
                        rd = interpretedCode.bytecode[pc++];
                        rs1 = interpretedCode.bytecode[pc++];
                        rs2 = interpretedCode.bytecode[pc++];
                        sb.append("CONCAT_NO_OVERLOAD r").append(rd).append(" = r").append(rs1).append(" . r").append(rs2).append("\n");
                        break;
                    case Opcodes.SELECT:
                        rd = interpretedCode.bytecode[pc++];
                        listReg = interpretedCode.bytecode[pc++];
                        sb.append("SELECT r").append(rd).append(" = select(r").append(listReg).append(")\n");
                        break;
                    case Opcodes.RANGE:
                        rd = interpretedCode.bytecode[pc++];
                        int startReg = interpretedCode.bytecode[pc++];
                        int endReg = interpretedCode.bytecode[pc++];
                        sb.append("RANGE r").append(rd).append(" = r").append(startReg).append("..r").append(endReg).append("\n");
                        break;
                    case Opcodes.CREATE_HASH:
                        rd = interpretedCode.bytecode[pc++];
                        int hashListReg = interpretedCode.bytecode[pc++];
                        sb.append("CREATE_HASH r").append(rd).append(" = hash_ref(r").append(hashListReg).append(")\n");
                        break;
                    case Opcodes.RAND:
                        rd = interpretedCode.bytecode[pc++];
                        int maxReg = interpretedCode.bytecode[pc++];
                        sb.append("RAND r").append(rd).append(" = rand(r").append(maxReg).append(")\n");
                        break;
                    case Opcodes.MAP:
                        rd = interpretedCode.bytecode[pc++];
                        rs1 = interpretedCode.bytecode[pc++];  // list register
                        rs2 = interpretedCode.bytecode[pc++];  // closure register
                        int mapCtx = interpretedCode.bytecode[pc++];  // context
                        sb.append("MAP r").append(rd).append(" = map(r").append(rs1)
                                .append(", r").append(rs2).append(", ctx=").append(mapCtx).append(")\n");
                        break;
                    case Opcodes.GREP:
                        rd = interpretedCode.bytecode[pc++];
                        rs1 = interpretedCode.bytecode[pc++];  // list register
                        rs2 = interpretedCode.bytecode[pc++];  // closure register
                        int grepCtx = interpretedCode.bytecode[pc++];  // context
                        sb.append("GREP r").append(rd).append(" = grep(r").append(rs1)
                                .append(", r").append(rs2).append(", ctx=").append(grepCtx).append(")\n");
                        break;
                    case Opcodes.SORT:
                        rd = interpretedCode.bytecode[pc++];
                        rs1 = interpretedCode.bytecode[pc++];  // list register
                        rs2 = interpretedCode.bytecode[pc++];  // closure register
                        int pkgIdx = InterpretedCode.readInt(interpretedCode.bytecode, pc);
                        pc += 1;
                        sb.append("SORT r").append(rd).append(" = sort(r").append(rs1)
                                .append(", r").append(rs2).append(", pkg=").append(interpretedCode.stringPool[pkgIdx]).append(")\n");
                        break;
                    case Opcodes.NEW_ARRAY:
                        rd = interpretedCode.bytecode[pc++];
                        sb.append("NEW_ARRAY r").append(rd).append(" = new RuntimeArray()\n");
                        break;
                    case Opcodes.NEW_HASH:
                        rd = interpretedCode.bytecode[pc++];
                        sb.append("NEW_HASH r").append(rd).append(" = new RuntimeHash()\n");
                        break;
                    case Opcodes.ARRAY_SET_FROM_LIST:
                        rs1 = interpretedCode.bytecode[pc++];  // array register
                        rs2 = interpretedCode.bytecode[pc++];  // list register
                        sb.append("ARRAY_SET_FROM_LIST r").append(rs1).append(".setFromList(r").append(rs2).append(")\n");
                        break;
                    case Opcodes.SET_FROM_LIST:
                        rd = interpretedCode.bytecode[pc++];
                        rs1 = interpretedCode.bytecode[pc++];  // lhs list
                        rs2 = interpretedCode.bytecode[pc++];  // rhs list
                        sb.append("SET_FROM_LIST r").append(rd).append(" = r").append(rs1).append(".setFromList(r").append(rs2).append(")\n");
                        break;
                    case Opcodes.HASH_SET_FROM_LIST:
                        rs1 = interpretedCode.bytecode[pc++];  // hash register
                        rs2 = interpretedCode.bytecode[pc++];  // list register
                        sb.append("HASH_SET_FROM_LIST r").append(rs1).append(".setFromList(r").append(rs2).append(")\n");
                        break;
                    case Opcodes.STORE_GLOBAL_CODE:
                        int codeNameIdx = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("STORE_GLOBAL_CODE '").append(interpretedCode.stringPool[codeNameIdx]).append("' = r").append(rs).append("\n");
                        break;
                    case Opcodes.CREATE_CLOSURE:
                        rd = interpretedCode.bytecode[pc++];
                        int templateIdx = interpretedCode.bytecode[pc++];
                        int numCaptures = interpretedCode.bytecode[pc++];
                        sb.append("CREATE_CLOSURE r").append(rd).append(" = closure(template[").append(templateIdx).append("], captures=[");
                        for (int i = 0; i < numCaptures; i++) {
                            if (i > 0) sb.append(", ");
                            int captureReg = interpretedCode.bytecode[pc++];
                            sb.append("r").append(captureReg);
                        }
                        sb.append("])\n");
                        break;
                    case Opcodes.SET_SCALAR:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("SET_SCALAR r").append(rd).append(".set(r").append(rs).append(")\n");
                        break;
                    case Opcodes.NOT:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("NOT r").append(rd).append(" = !r").append(rs).append("\n");
                        break;
                    case Opcodes.DEFINED:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("DEFINED r").append(rd).append(" = defined(r").append(rs).append(")\n");
                        break;
                    case Opcodes.DEFINED_CODE:
                        rd = interpretedCode.bytecode[pc++];
                        int definedCodeNameIdx = interpretedCode.bytecode[pc++];
                        sb.append("DEFINED_CODE r").append(rd).append(" = defined(&")
                          .append(interpretedCode.stringPool[definedCodeNameIdx]).append(")\n");
                        break;
                    case Opcodes.DEFINED_GLOB:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        int definedGlobPkgIdx = interpretedCode.bytecode[pc++];
                        sb.append("DEFINED_GLOB r").append(rd).append(" = defined(*r").append(rs)
                          .append(") pkg=").append(interpretedCode.stringPool[definedGlobPkgIdx]).append("\n");
                        break;
                    case Opcodes.REF:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("REF r").append(rd).append(" = ref(r").append(rs).append(")\n");
                        break;
                    case Opcodes.BLESS:
                        rd = interpretedCode.bytecode[pc++];
                        int refReg = interpretedCode.bytecode[pc++];
                        int packageReg = interpretedCode.bytecode[pc++];
                        sb.append("BLESS r").append(rd).append(" = bless(r").append(refReg)
                                .append(", r").append(packageReg).append(")\n");
                        break;
                    case Opcodes.ISA:
                        rd = interpretedCode.bytecode[pc++];
                        int objReg = interpretedCode.bytecode[pc++];
                        int pkgReg = interpretedCode.bytecode[pc++];
                        sb.append("ISA r").append(rd).append(" = isa(r").append(objReg)
                                .append(", r").append(pkgReg).append(")\n");
                        break;
                    case Opcodes.PROTOTYPE:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        int packageIdx = InterpretedCode.readInt(interpretedCode.bytecode, pc);
                        pc += 1;  // readInt reads 2 shorts
                        String packageName = (interpretedCode.stringPool != null && packageIdx < interpretedCode.stringPool.length) ?
                                interpretedCode.stringPool[packageIdx] : "<unknown>";
                        sb.append("PROTOTYPE r").append(rd).append(" = prototype(r").append(rs)
                                .append(", \"").append(packageName).append("\")\n");
                        break;
                    case Opcodes.QUOTE_REGEX:
                        rd = interpretedCode.bytecode[pc++];
                        int patternReg = interpretedCode.bytecode[pc++];
                        int flagsReg = interpretedCode.bytecode[pc++];
                        sb.append("QUOTE_REGEX r").append(rd).append(" = qr{r").append(patternReg)
                                .append("}r").append(flagsReg).append("\n");
                        break;
                    case Opcodes.QUOTE_REGEX_O:
                        rd = interpretedCode.bytecode[pc++];
                        patternReg = interpretedCode.bytecode[pc++];
                        flagsReg = interpretedCode.bytecode[pc++];
                        int callsiteId = interpretedCode.bytecode[pc++];
                        sb.append("QUOTE_REGEX_O r").append(rd).append(" = qr{r").append(patternReg)
                                .append("}r").append(flagsReg).append(" callsite=").append(callsiteId).append("\n");
                        break;
                    case Opcodes.ITERATOR_CREATE:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("ITERATOR_CREATE r").append(rd).append(" = r").append(rs).append(".iterator()\n");
                        break;
                    case Opcodes.ITERATOR_HAS_NEXT:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("ITERATOR_HAS_NEXT r").append(rd).append(" = r").append(rs).append(".hasNext()\n");
                        break;
                    case Opcodes.ITERATOR_NEXT:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("ITERATOR_NEXT r").append(rd).append(" = r").append(rs).append(".next()\n");
                        break;
                    case Opcodes.FOREACH_NEXT_OR_EXIT: {
                        rd = interpretedCode.bytecode[pc++];
                        int iterReg = interpretedCode.bytecode[pc++];
                        int bodyTarget = InterpretedCode.readInt(interpretedCode.bytecode, pc);  // Absolute body address
                        pc += 1;
                        sb.append("FOREACH_NEXT_OR_EXIT r").append(rd)
                                .append(" = r").append(iterReg).append(".next() and goto ")
                                .append(bodyTarget).append("\n");
                        break;
                    }
                    case Opcodes.SUBTRACT_ASSIGN:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("SUBTRACT_ASSIGN r").append(rd).append(" -= r").append(rs).append("\n");
                        break;
                    case Opcodes.MULTIPLY_ASSIGN:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("MULTIPLY_ASSIGN r").append(rd).append(" *= r").append(rs).append("\n");
                        break;
                    case Opcodes.DIVIDE_ASSIGN:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("DIVIDE_ASSIGN r").append(rd).append(" /= r").append(rs).append("\n");
                        break;
                    case Opcodes.MODULUS_ASSIGN:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("MODULUS_ASSIGN r").append(rd).append(" %= r").append(rs).append("\n");
                        break;
                    case Opcodes.REPEAT_ASSIGN:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("REPEAT_ASSIGN r").append(rd).append(" x= r").append(rs).append("\n");
                        break;
                    case Opcodes.POW_ASSIGN:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("POW_ASSIGN r").append(rd).append(" **= r").append(rs).append("\n");
                        break;
                    case Opcodes.LEFT_SHIFT_ASSIGN:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("LEFT_SHIFT_ASSIGN r").append(rd).append(" <<= r").append(rs).append("\n");
                        break;
                    case Opcodes.RIGHT_SHIFT_ASSIGN:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("RIGHT_SHIFT_ASSIGN r").append(rd).append(" >>= r").append(rs).append("\n");
                        break;
                    case Opcodes.INTEGER_LEFT_SHIFT_ASSIGN:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("INTEGER_LEFT_SHIFT_ASSIGN r").append(rd).append(" <<= r").append(rs).append("\n");
                        break;
                    case Opcodes.INTEGER_RIGHT_SHIFT_ASSIGN:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("INTEGER_RIGHT_SHIFT_ASSIGN r").append(rd).append(" >>= r").append(rs).append("\n");
                        break;
                    case Opcodes.INTEGER_DIV_ASSIGN:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("INTEGER_DIV_ASSIGN r").append(rd).append(" /= r").append(rs).append("\n");
                        break;
                    case Opcodes.INTEGER_MOD_ASSIGN:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("INTEGER_MOD_ASSIGN r").append(rd).append(" %= r").append(rs).append("\n");
                        break;
                    case Opcodes.LOGICAL_AND_ASSIGN:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("LOGICAL_AND_ASSIGN r").append(rd).append(" &&= r").append(rs).append("\n");
                        break;
                    case Opcodes.LOGICAL_OR_ASSIGN:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("LOGICAL_OR_ASSIGN r").append(rd).append(" ||= r").append(rs).append("\n");
                        break;
                    case Opcodes.LEFT_SHIFT:
                        rd = interpretedCode.bytecode[pc++];
                        rs1 = interpretedCode.bytecode[pc++];
                        rs2 = interpretedCode.bytecode[pc++];
                        sb.append("LEFT_SHIFT r").append(rd).append(" = r").append(rs1).append(" << r").append(rs2).append("\n");
                        break;
                    case Opcodes.RIGHT_SHIFT:
                        rd = interpretedCode.bytecode[pc++];
                        rs1 = interpretedCode.bytecode[pc++];
                        rs2 = interpretedCode.bytecode[pc++];
                        sb.append("RIGHT_SHIFT r").append(rd).append(" = r").append(rs1).append(" >> r").append(rs2).append("\n");
                        break;
                    case Opcodes.INTEGER_LEFT_SHIFT:
                        rd = interpretedCode.bytecode[pc++];
                        rs1 = interpretedCode.bytecode[pc++];
                        rs2 = interpretedCode.bytecode[pc++];
                        sb.append("INTEGER_LEFT_SHIFT r").append(rd).append(" = r").append(rs1).append(" << r").append(rs2).append("\n");
                        break;
                    case Opcodes.INTEGER_RIGHT_SHIFT:
                        rd = interpretedCode.bytecode[pc++];
                        rs1 = interpretedCode.bytecode[pc++];
                        rs2 = interpretedCode.bytecode[pc++];
                        sb.append("INTEGER_RIGHT_SHIFT r").append(rd).append(" = r").append(rs1).append(" >> r").append(rs2).append("\n");
                        break;
                    case Opcodes.INTEGER_DIV:
                        rd = interpretedCode.bytecode[pc++];
                        rs1 = interpretedCode.bytecode[pc++];
                        rs2 = interpretedCode.bytecode[pc++];
                        sb.append("INTEGER_DIV r").append(rd).append(" = r").append(rs1).append(" / r").append(rs2).append("\n");
                        break;
                    case Opcodes.INTEGER_MOD:
                        rd = interpretedCode.bytecode[pc++];
                        rs1 = interpretedCode.bytecode[pc++];
                        rs2 = interpretedCode.bytecode[pc++];
                        sb.append("INTEGER_MOD r").append(rd).append(" = r").append(rs1).append(" % r").append(rs2).append("\n");
                        break;
                    case Opcodes.LIST_TO_COUNT:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("LIST_TO_COUNT r").append(rd).append(" = count(r").append(rs).append(")\n");
                        break;
                    case Opcodes.LIST_TO_SCALAR:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("LIST_TO_SCALAR r").append(rd).append(" = scalar(r").append(rs).append(")\n");
                        break;
                    case Opcodes.SCALAR_TO_LIST:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("SCALAR_TO_LIST r").append(rd).append(" = to_list(r").append(rs).append(")\n");
                        break;
                    case Opcodes.EVAL_STRING:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        int evalCtx = interpretedCode.bytecode[pc++];
                        int evalSite = interpretedCode.bytecode[pc++];
                        sb.append("EVAL_STRING r").append(rd).append(" = eval(r").append(rs)
                                .append(", ctx=").append(evalCtx).append(", site=").append(evalSite).append(")\n");
                        break;
                    case Opcodes.SELECT_OP:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("SELECT_OP r").append(rd).append(" = select(r").append(rs).append(")\n");
                        break;
                    case Opcodes.LOAD_GLOB:
                        rd = interpretedCode.bytecode[pc++];
                        nameIdx = interpretedCode.bytecode[pc++];
                        sb.append("LOAD_GLOB r").append(rd).append(" = *").append(interpretedCode.stringPool[nameIdx]).append("\n");
                        break;
                    case Opcodes.TIME_OP:
                        rd = interpretedCode.bytecode[pc++];
                        sb.append("TIME_OP r").append(rd).append(" = time()\n");
                        break;
                    case Opcodes.WAIT_OP:
                        rd = interpretedCode.bytecode[pc++];
                        sb.append("WAIT_OP r").append(rd).append(" = wait()\n");
                        break;
                    case Opcodes.SLEEP_OP:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("SLEEP_OP r").append(rd).append(" = sleep(r").append(rs).append(")\n");
                        break;
                    case Opcodes.ALARM_OP:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("ALARM_OP r").append(rd).append(" = alarm(r").append(rs).append(")\n");
                        break;
                    case Opcodes.DEREF_GLOB:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        nameIdx = interpretedCode.bytecode[pc++];
                        sb.append("DEREF_GLOB r").append(rd).append(" = *{r").append(rs).append("} strict pkg=").append(interpretedCode.stringPool[nameIdx]).append("\n");
                        break;
                    case Opcodes.DEREF_GLOB_NONSTRICT:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        nameIdx = interpretedCode.bytecode[pc++];
                        sb.append("DEREF_GLOB_NONSTRICT r").append(rd).append(" = *{r").append(rs).append("} pkg=").append(interpretedCode.stringPool[nameIdx]).append("\n");
                        break;
                    case Opcodes.DEREF_ARRAY:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("DEREF_ARRAY r").append(rd).append(" = @{r").append(rs).append("}\n");
                        break;
                    case Opcodes.DEREF_HASH:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("DEREF_HASH r").append(rd).append(" = %{r").append(rs).append("}\n");
                        break;
                    case Opcodes.DEREF_HASH_NONSTRICT:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        nameIdx = interpretedCode.bytecode[pc++];
                        sb.append("DEREF_HASH_NONSTRICT r").append(rd).append(" = %{r").append(rs).append("} pkg=").append(interpretedCode.stringPool[nameIdx]).append("\n");
                        break;
                    case Opcodes.DEREF_ARRAY_NONSTRICT:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        nameIdx = interpretedCode.bytecode[pc++];
                        sb.append("DEREF_ARRAY_NONSTRICT r").append(rd).append(" = @{r").append(rs).append("} pkg=").append(interpretedCode.stringPool[nameIdx]).append("\n");
                        break;
                    case Opcodes.DEREF_SCALAR_STRICT:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("DEREF_SCALAR_STRICT r").append(rd).append(" = ${r").append(rs).append("}\n");
                        break;
                    case Opcodes.DEREF_SCALAR_NONSTRICT:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        nameIdx = interpretedCode.bytecode[pc++];
                        sb.append("DEREF_SCALAR_NONSTRICT r").append(rd).append(" = ${r").append(rs).append("} pkg=").append(interpretedCode.stringPool[nameIdx]).append("\n");
                        break;
                    case Opcodes.CODE_DEREF_NONSTRICT:
                        rd = interpretedCode.bytecode[pc++];
                        rs = interpretedCode.bytecode[pc++];
                        nameIdx = interpretedCode.bytecode[pc++];
                        sb.append("CODE_DEREF_NONSTRICT r").append(rd).append(" = &{r").append(rs).append("} pkg=").append(interpretedCode.stringPool[nameIdx]).append("\n");
                        break;
                    case Opcodes.RETRIEVE_BEGIN_SCALAR:
                        rd = interpretedCode.bytecode[pc++];
                        nameIdx = interpretedCode.bytecode[pc++];
                        int beginId = interpretedCode.bytecode[pc++];
                        sb.append("RETRIEVE_BEGIN_SCALAR r").append(rd).append(" = BEGIN_").append(beginId)
                                .append("::").append(interpretedCode.stringPool[nameIdx]).append("\n");
                        break;
                    case Opcodes.SPLIT:
                        rd = interpretedCode.bytecode[pc++];
                        int splitPatternReg = interpretedCode.bytecode[pc++];
                        int splitArgsReg = interpretedCode.bytecode[pc++];
                        int splitCtx = interpretedCode.bytecode[pc++];
                        sb.append("SPLIT r").append(rd).append(" = split(r").append(splitPatternReg)
                                .append(", r").append(splitArgsReg).append(", ctx=").append(splitCtx).append(")\n");
                        break;
                    case Opcodes.LOCAL_SCALAR:
                        rd = interpretedCode.bytecode[pc++];
                        nameIdx = interpretedCode.bytecode[pc++];
                        sb.append("LOCAL_SCALAR r").append(rd).append(" = local $").append(interpretedCode.stringPool[nameIdx]).append("\n");
                        break;
                    case Opcodes.LOCAL_ARRAY:
                        rd = interpretedCode.bytecode[pc++];
                        nameIdx = interpretedCode.bytecode[pc++];
                        sb.append("LOCAL_ARRAY r").append(rd).append(" = local @").append(interpretedCode.stringPool[nameIdx]).append("\n");
                        break;
                    case Opcodes.LOCAL_HASH:
                        rd = interpretedCode.bytecode[pc++];
                        nameIdx = interpretedCode.bytecode[pc++];
                        sb.append("LOCAL_HASH r").append(rd).append(" = local %").append(interpretedCode.stringPool[nameIdx]).append("\n");
                        break;
                    case Opcodes.LOCAL_SCALAR_SAVE_LEVEL: {
                        rd = interpretedCode.bytecode[pc++];
                        int levelReg = interpretedCode.bytecode[pc++];
                        nameIdx = interpretedCode.bytecode[pc++];
                        sb.append("LOCAL_SCALAR_SAVE_LEVEL r").append(rd).append(", level=r").append(levelReg)
                                .append(" = local $").append(interpretedCode.stringPool[nameIdx]).append("\n");
                        break;
                    }
                    case Opcodes.POP_LOCAL_LEVEL:
                        rs = interpretedCode.bytecode[pc++];
                        sb.append("POP_LOCAL_LEVEL DynamicVariableManager.popToLocalLevel(r").append(rs).append(")\n");
                        break;
                    case Opcodes.FOREACH_GLOBAL_NEXT_OR_EXIT: {
                        rd = interpretedCode.bytecode[pc++];
                        int fgIterReg = interpretedCode.bytecode[pc++];
                        nameIdx = interpretedCode.bytecode[pc++];
                        int fgBody = InterpretedCode.readInt(interpretedCode.bytecode, pc);
                        pc += 1;
                        sb.append("FOREACH_GLOBAL_NEXT_OR_EXIT r").append(rd).append(" = r").append(fgIterReg)
                                .append(".next(), alias $").append(interpretedCode.stringPool[nameIdx]).append(" and goto ").append(fgBody).append("\n");
                        break;
                    }
                    // Misc list operators: OPCODE rd argsReg ctx
                    case Opcodes.PACK:
                    case Opcodes.UNPACK:
                    case Opcodes.CRYPT:
                    case Opcodes.LOCALTIME:
                    case Opcodes.GMTIME:
                    case Opcodes.RESET:
                    case Opcodes.TIMES:
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
                    case Opcodes.KILL:
                    case Opcodes.CALLER:
                    case Opcodes.EACH:
                    case Opcodes.VEC: {
                        rd = interpretedCode.bytecode[pc++];
                        int miscArgsReg = interpretedCode.bytecode[pc++];
                        int miscCtx = interpretedCode.bytecode[pc++];
                        String miscName = switch (opcode) {
                            case Opcodes.PACK -> "pack";
                            case Opcodes.UNPACK -> "unpack";
                            case Opcodes.CRYPT -> "crypt";
                            case Opcodes.LOCALTIME -> "localtime";
                            case Opcodes.GMTIME -> "gmtime";
                            case Opcodes.RESET -> "reset";
                            case Opcodes.TIMES -> "times";
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
                            case Opcodes.KILL -> "kill";
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
                        pc = ScalarBinaryOpcodeHandler.disassemble(opcode, interpretedCode.bytecode, pc, sb);
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
                    case Opcodes.FC_BYTES:
                    case Opcodes.LC:
                    case Opcodes.LC_BYTES:
                    case Opcodes.LCFIRST:
                    case Opcodes.LCFIRST_BYTES:
                    case Opcodes.UC:
                    case Opcodes.UC_BYTES:
                    case Opcodes.UCFIRST:
                    case Opcodes.UCFIRST_BYTES:
                    case Opcodes.TO_BYTES_STRING:
                    case Opcodes.SLEEP:
                    case Opcodes.TELL:
                    case Opcodes.RMDIR:
                    case Opcodes.CLOSEDIR:
                    case Opcodes.REWINDDIR:
                    case Opcodes.TELLDIR:
                    case Opcodes.CHDIR:
                    case Opcodes.EXIT:
                        pc = ScalarUnaryOpcodeHandler.disassemble(opcode, interpretedCode.bytecode, pc, sb);
                        break;
                    // GENERATED_DISASM_END

                    case Opcodes.FLIP_FLOP: {
                        int ffRd = interpretedCode.bytecode[pc++];
                        int ffId = interpretedCode.bytecode[pc++];
                        int ffRs1 = interpretedCode.bytecode[pc++];
                        int ffRs2 = interpretedCode.bytecode[pc++];
                        sb.append("FLIP_FLOP r").append(ffRd).append(" = flipFlop(").append(ffId).append(", r").append(ffRs1).append(", r").append(ffRs2).append(")\n");
                        break;
                    }
                    case Opcodes.LOCAL_GLOB:
                        sb.append("LOCAL_GLOB r").append(interpretedCode.bytecode[pc++]).append(" = pushLocalVariable(glob '").append(interpretedCode.stringPool[interpretedCode.bytecode[pc++]]).append("')\n");
                        break;
                    case Opcodes.LOCAL_GLOB_DYNAMIC: {
                        int lgdRd = interpretedCode.bytecode[pc++];
                        int lgdNameReg = interpretedCode.bytecode[pc++];
                        sb.append("LOCAL_GLOB_DYNAMIC r").append(lgdRd).append(" = pushLocalVariable(glob r").append(lgdNameReg).append(")\n");
                        break;
                    }
                    case Opcodes.GET_LOCAL_LEVEL:
                        sb.append("GET_LOCAL_LEVEL r").append(interpretedCode.bytecode[pc++]).append("\n");
                        break;
                    case Opcodes.SET_PACKAGE:
                        sb.append("SET_PACKAGE '").append(interpretedCode.stringPool[interpretedCode.bytecode[pc++]]).append("'\n");
                        break;
                    case Opcodes.PUSH_PACKAGE:
                        sb.append("PUSH_PACKAGE '").append(interpretedCode.stringPool[interpretedCode.bytecode[pc++]]).append("'\n");
                        break;
                    case Opcodes.POP_PACKAGE:
                        sb.append("POP_PACKAGE\n");
                        break;
                    case Opcodes.DO_FILE:
                        sb.append("DO_FILE r").append(interpretedCode.bytecode[pc++]).append(" = doFile(r").append(interpretedCode.bytecode[pc++]).append(") ctx=").append(interpretedCode.bytecode[pc++]).append("\n");
                        break;
                    case Opcodes.PUSH_DEFER: {
                        int deferCodeReg = interpretedCode.bytecode[pc++];
                        int deferArgsReg = interpretedCode.bytecode[pc++];
                        sb.append("PUSH_DEFER pushLocalVariable(new DeferBlock(r").append(deferCodeReg).append(", r").append(deferArgsReg).append("))\n");
                        break;
                    }
                    case Opcodes.PUSH_LABELED_BLOCK: {
                        int labelIdx = interpretedCode.bytecode[pc++];
                        int exitPc = interpretedCode.bytecode[pc++];
                        sb.append("PUSH_LABELED_BLOCK \"").append(interpretedCode.stringPool[labelIdx]).append("\" exitPc=").append(exitPc).append("\n");
                        break;
                    }
                    case Opcodes.POP_LABELED_BLOCK:
                        sb.append("POP_LABELED_BLOCK\n");
                        break;

                    // =================================================================
                    // CORE OPS (29-67) - String, comparison, logical, control flow
                    // =================================================================

                    case Opcodes.SUBSTR: {
                        // Substring: rd = substr(rs1, rs2, rs3)
                        // Format: SUBSTR rd strReg offsetReg lengthReg
                        rd = interpretedCode.bytecode[pc++];
                        rs1 = interpretedCode.bytecode[pc++];
                        rs2 = interpretedCode.bytecode[pc++];
                        sb.append("SUBSTR r").append(rd).append(" = substr(r").append(rs1)
                                .append(", r").append(rs2).append(")\n");
                        break;
                    }
                    case Opcodes.LENGTH: {
                        // String length: rd = length(rs)
                        // Format: LENGTH rd rs
                        rd = interpretedCode.bytecode[pc++];
                        int lenRs = interpretedCode.bytecode[pc++];
                        sb.append("LENGTH r").append(rd).append(" = length(r").append(lenRs).append(")\n");
                        break;
                    }
                    case Opcodes.COMPARE_NUM:
                        rd = interpretedCode.bytecode[pc++];
                        rs1 = interpretedCode.bytecode[pc++];
                        rs2 = interpretedCode.bytecode[pc++];
                        sb.append("COMPARE_NUM r").append(rd).append(" = r").append(rs1).append(" <=> r").append(rs2).append("\n");
                        break;
                    case Opcodes.COMPARE_STR:
                        rd = interpretedCode.bytecode[pc++];
                        rs1 = interpretedCode.bytecode[pc++];
                        rs2 = interpretedCode.bytecode[pc++];
                        sb.append("COMPARE_STR r").append(rd).append(" = r").append(rs1).append(" cmp r").append(rs2).append("\n");
                        break;
                    case Opcodes.EQ_NUM:
                        rd = interpretedCode.bytecode[pc++];
                        rs1 = interpretedCode.bytecode[pc++];
                        rs2 = interpretedCode.bytecode[pc++];
                        sb.append("EQ_NUM r").append(rd).append(" = r").append(rs1).append(" == r").append(rs2).append("\n");
                        break;
                    case Opcodes.EQ_STR:
                        rd = interpretedCode.bytecode[pc++];
                        rs1 = interpretedCode.bytecode[pc++];
                        rs2 = interpretedCode.bytecode[pc++];
                        sb.append("EQ_STR r").append(rd).append(" = r").append(rs1).append(" eq r").append(rs2).append("\n");
                        break;
                    case Opcodes.NE_STR:
                        rd = interpretedCode.bytecode[pc++];
                        rs1 = interpretedCode.bytecode[pc++];
                        rs2 = interpretedCode.bytecode[pc++];
                        sb.append("NE_STR r").append(rd).append(" = r").append(rs1).append(" ne r").append(rs2).append("\n");
                        break;
                    case Opcodes.AND:
                        rd = interpretedCode.bytecode[pc++];
                        rs1 = interpretedCode.bytecode[pc++];
                        rs2 = interpretedCode.bytecode[pc++];
                        sb.append("AND r").append(rd).append(" = r").append(rs1).append(" && r").append(rs2).append("\n");
                        break;
                    case Opcodes.OR:
                        rd = interpretedCode.bytecode[pc++];
                        rs1 = interpretedCode.bytecode[pc++];
                        rs2 = interpretedCode.bytecode[pc++];
                        sb.append("OR r").append(rd).append(" = r").append(rs1).append(" || r").append(rs2).append("\n");
                        break;
                    case Opcodes.CALL_BUILTIN: {
                        // Call builtin: rd = builtin(args, ctx)
                        // Format: CALL_BUILTIN rd builtinId argsReg ctx
                        rd = interpretedCode.bytecode[pc++];
                        int builtinId = interpretedCode.bytecode[pc++];
                        int builtinArgsReg = interpretedCode.bytecode[pc++];
                        int builtinCtx = interpretedCode.bytecode[pc++];
                        sb.append("CALL_BUILTIN r").append(rd).append(" = builtin(").append(builtinId)
                                .append(", r").append(builtinArgsReg).append(", ctx=").append(builtinCtx).append(")\n");
                        break;
                    }

                    // Control flow creation: rd = RuntimeControlFlowList(type, label)
                    // Format: CREATE_xxx rd labelIdx
                    case Opcodes.CREATE_LAST: {
                        rd = interpretedCode.bytecode[pc++];
                        int cfLabelIdx = interpretedCode.bytecode[pc++];
                        sb.append("CREATE_LAST r").append(rd).append(" label=");
                        if (cfLabelIdx == 255) {
                            sb.append("<none>");
                        } else if (interpretedCode.stringPool != null && cfLabelIdx < interpretedCode.stringPool.length) {
                            sb.append("\"").append(interpretedCode.stringPool[cfLabelIdx]).append("\"");
                        } else {
                            sb.append(cfLabelIdx);
                        }
                        sb.append("\n");
                        break;
                    }
                    case Opcodes.CREATE_NEXT: {
                        rd = interpretedCode.bytecode[pc++];
                        int cfLabelIdx = interpretedCode.bytecode[pc++];
                        sb.append("CREATE_NEXT r").append(rd).append(" label=");
                        if (cfLabelIdx == 255) {
                            sb.append("<none>");
                        } else if (interpretedCode.stringPool != null && cfLabelIdx < interpretedCode.stringPool.length) {
                            sb.append("\"").append(interpretedCode.stringPool[cfLabelIdx]).append("\"");
                        } else {
                            sb.append(cfLabelIdx);
                        }
                        sb.append("\n");
                        break;
                    }
                    case Opcodes.CREATE_REDO: {
                        rd = interpretedCode.bytecode[pc++];
                        int cfLabelIdx = interpretedCode.bytecode[pc++];
                        sb.append("CREATE_REDO r").append(rd).append(" label=");
                        if (cfLabelIdx == 255) {
                            sb.append("<none>");
                        } else if (interpretedCode.stringPool != null && cfLabelIdx < interpretedCode.stringPool.length) {
                            sb.append("\"").append(interpretedCode.stringPool[cfLabelIdx]).append("\"");
                        } else {
                            sb.append(cfLabelIdx);
                        }
                        sb.append("\n");
                        break;
                    }
                    case Opcodes.CREATE_LAST_DYNAMIC:
                    case Opcodes.CREATE_NEXT_DYNAMIC:
                    case Opcodes.CREATE_REDO_DYNAMIC: {
                        rd = interpretedCode.bytecode[pc++];
                        int labelReg = interpretedCode.bytecode[pc++];
                        String dynName = opcode == Opcodes.CREATE_LAST_DYNAMIC ? "CREATE_LAST_DYNAMIC"
                                : opcode == Opcodes.CREATE_NEXT_DYNAMIC ? "CREATE_NEXT_DYNAMIC"
                                : "CREATE_REDO_DYNAMIC";
                        sb.append(dynName).append(" r").append(rd).append(" r").append(labelReg).append("\n");
                        break;
                    }
                    case Opcodes.CREATE_GOTO: {
                        rd = interpretedCode.bytecode[pc++];
                        int cfLabelIdx = interpretedCode.bytecode[pc++];
                        sb.append("CREATE_GOTO r").append(rd).append(" label=");
                        if (cfLabelIdx == 255) {
                            sb.append("<none>");
                        } else if (interpretedCode.stringPool != null && cfLabelIdx < interpretedCode.stringPool.length) {
                            sb.append("\"").append(interpretedCode.stringPool[cfLabelIdx]).append("\"");
                        } else {
                            sb.append(cfLabelIdx);
                        }
                        sb.append("\n");
                        break;
                    }
                    case Opcodes.IS_CONTROL_FLOW:
                        rd = interpretedCode.bytecode[pc++];
                        rs1 = interpretedCode.bytecode[pc++];
                        sb.append("IS_CONTROL_FLOW r").append(rd).append(" = (r").append(rs1).append(" instanceof ControlFlow)\n");
                        break;
                    case Opcodes.GET_CONTROL_FLOW_TYPE:
                        rd = interpretedCode.bytecode[pc++];
                        rs1 = interpretedCode.bytecode[pc++];
                        sb.append("GET_CONTROL_FLOW_TYPE r").append(rd).append(" = r").append(rs1).append(".getControlFlowType()\n");
                        break;

                    // =================================================================
                    // SLICE/COLLECTION OPS (116-127)
                    // =================================================================

                    case Opcodes.ARRAY_SLICE: {
                        // Format: ARRAY_SLICE rd arrayReg indicesReg
                        rd = interpretedCode.bytecode[pc++];
                        int asArrayReg = interpretedCode.bytecode[pc++];
                        int asIndicesReg = interpretedCode.bytecode[pc++];
                        sb.append("ARRAY_SLICE r").append(rd).append(" = r").append(asArrayReg)
                                .append("[r").append(asIndicesReg).append("]\n");
                        break;
                    }
                    case Opcodes.ARRAY_SLICE_SET: {
                        // Format: ARRAY_SLICE_SET arrayReg indicesReg valuesReg
                        int assArrayReg = interpretedCode.bytecode[pc++];
                        int assIndicesReg = interpretedCode.bytecode[pc++];
                        int assValuesReg = interpretedCode.bytecode[pc++];
                        sb.append("ARRAY_SLICE_SET r").append(assArrayReg)
                                .append("[r").append(assIndicesReg).append("] = r").append(assValuesReg).append("\n");
                        break;
                    }
                    case Opcodes.HASH_SLICE: {
                        // Format: HASH_SLICE rd hashReg keysListReg
                        rd = interpretedCode.bytecode[pc++];
                        int hsHashReg = interpretedCode.bytecode[pc++];
                        int hsKeysReg = interpretedCode.bytecode[pc++];
                        sb.append("HASH_SLICE r").append(rd).append(" = r").append(hsHashReg)
                                .append("{r").append(hsKeysReg).append("}\n");
                        break;
                    }
                    case Opcodes.HASH_SLICE_SET: {
                        // Format: HASH_SLICE_SET hashReg keysListReg valuesListReg
                        int hssHashReg = interpretedCode.bytecode[pc++];
                        int hssKeysReg = interpretedCode.bytecode[pc++];
                        int hssValuesReg = interpretedCode.bytecode[pc++];
                        sb.append("HASH_SLICE_SET r").append(hssHashReg)
                                .append("{r").append(hssKeysReg).append("} = r").append(hssValuesReg).append("\n");
                        break;
                    }
                    case Opcodes.HASH_SLICE_DELETE: {
                        // Format: HASH_SLICE_DELETE rd hashReg keysListReg
                        rd = interpretedCode.bytecode[pc++];
                        int hsdHashReg = interpretedCode.bytecode[pc++];
                        int hsdKeysReg = interpretedCode.bytecode[pc++];
                        sb.append("HASH_SLICE_DELETE r").append(rd).append(" = delete r").append(hsdHashReg)
                                .append("{r").append(hsdKeysReg).append("}\n");
                        break;
                    }
                    case Opcodes.ARRAY_SLICE_DELETE: {
                        // Format: ARRAY_SLICE_DELETE rd arrayReg indicesListReg
                        rd = interpretedCode.bytecode[pc++];
                        int asdArrayReg = interpretedCode.bytecode[pc++];
                        int asdIndicesReg = interpretedCode.bytecode[pc++];
                        sb.append("ARRAY_SLICE_DELETE r").append(rd).append(" = delete r").append(asdArrayReg)
                                .append("[r").append(asdIndicesReg).append("]\n");
                        break;
                    }
                    case Opcodes.HASH_KV_SLICE_DELETE: {
                        // Format: HASH_KV_SLICE_DELETE rd hashReg keysListReg
                        rd = interpretedCode.bytecode[pc++];
                        int hkvHashReg = interpretedCode.bytecode[pc++];
                        int hkvKeysReg = interpretedCode.bytecode[pc++];
                        sb.append("HASH_KV_SLICE_DELETE r").append(rd).append(" = delete %r").append(hkvHashReg)
                                .append("{r").append(hkvKeysReg).append("}\n");
                        break;
                    }
                    case Opcodes.ARRAY_KV_SLICE_DELETE: {
                        // Format: ARRAY_KV_SLICE_DELETE rd arrayReg indicesListReg
                        rd = interpretedCode.bytecode[pc++];
                        int akvArrayReg = interpretedCode.bytecode[pc++];
                        int akvIndicesReg = interpretedCode.bytecode[pc++];
                        sb.append("ARRAY_KV_SLICE_DELETE r").append(rd).append(" = delete %r").append(akvArrayReg)
                                .append("[r").append(akvIndicesReg).append("]\n");
                        break;
                    }
                    case Opcodes.LIST_SLICE_FROM: {
                        // Format: LIST_SLICE_FROM rd listReg startIndex
                        rd = interpretedCode.bytecode[pc++];
                        int lsfListReg = interpretedCode.bytecode[pc++];
                        int lsfStartIdx = interpretedCode.bytecode[pc++];
                        sb.append("LIST_SLICE_FROM r").append(rd).append(" = r").append(lsfListReg)
                                .append("[").append(lsfStartIdx).append("..]\n");
                        break;
                    }
                    case Opcodes.SPLICE: {
                        // Format: SPLICE rd arrayReg argsReg context
                        rd = interpretedCode.bytecode[pc++];
                        int splArrayReg = interpretedCode.bytecode[pc++];
                        int splArgsReg = interpretedCode.bytecode[pc++];
                        int splCtx = interpretedCode.bytecode[pc++];
                        sb.append("SPLICE r").append(rd).append(" = splice(r").append(splArrayReg)
                                .append(", r").append(splArgsReg).append(", ctx=").append(splCtx).append(")\n");
                        break;
                    }
                    case Opcodes.REVERSE: {
                        // Format: REVERSE rd argsReg ctx
                        rd = interpretedCode.bytecode[pc++];
                        int revArgsReg = interpretedCode.bytecode[pc++];
                        int revCtx = interpretedCode.bytecode[pc++];
                        sb.append("REVERSE r").append(rd).append(" = reverse(r").append(revArgsReg)
                                .append(", ctx=").append(revCtx).append(")\n");
                        break;
                    }
                    case Opcodes.LENGTH_OP: {
                        // Format: LENGTH_OP rd stringReg
                        rd = interpretedCode.bytecode[pc++];
                        int lopRs = interpretedCode.bytecode[pc++];
                        sb.append("LENGTH_OP r").append(rd).append(" = length(r").append(lopRs).append(")\n");
                        break;
                    }
                    case Opcodes.EXISTS: {
                        // Format: EXISTS rd operandReg
                        rd = interpretedCode.bytecode[pc++];
                        int exOperandReg = interpretedCode.bytecode[pc++];
                        sb.append("EXISTS r").append(rd).append(" = exists(r").append(exOperandReg).append(")\n");
                        break;
                    }
                    case Opcodes.DELETE: {
                        // Format: DELETE rd operandReg
                        rd = interpretedCode.bytecode[pc++];
                        int delOperandReg = interpretedCode.bytecode[pc++];
                        sb.append("DELETE r").append(rd).append(" = delete(r").append(delOperandReg).append(")\n");
                        break;
                    }

                    // =================================================================
                    // BEGIN RETRIEVAL (129-130)
                    // =================================================================

                    case Opcodes.RETRIEVE_BEGIN_ARRAY: {
                        // Format: RETRIEVE_BEGIN_ARRAY rd nameIdx beginId
                        rd = interpretedCode.bytecode[pc++];
                        nameIdx = interpretedCode.bytecode[pc++];
                        int rbaBeginId = interpretedCode.bytecode[pc++];
                        sb.append("RETRIEVE_BEGIN_ARRAY r").append(rd).append(" = BEGIN_").append(rbaBeginId)
                                .append("::").append(interpretedCode.stringPool[nameIdx]).append("\n");
                        break;
                    }
                    case Opcodes.RETRIEVE_BEGIN_HASH: {
                        // Format: RETRIEVE_BEGIN_HASH rd nameIdx beginId
                        rd = interpretedCode.bytecode[pc++];
                        nameIdx = interpretedCode.bytecode[pc++];
                        int rbhBeginId = interpretedCode.bytecode[pc++];
                        String rbhName = (nameIdx >= 0 && nameIdx < interpretedCode.stringPool.length)
                                ? interpretedCode.stringPool[nameIdx]
                                : "<invalid:" + nameIdx + ">";
                        sb.append("RETRIEVE_BEGIN_HASH r").append(rd).append(" = BEGIN_").append(rbhBeginId)
                                .append("::").append(rbhName).append("\n");
                        break;
                    }

                    // =================================================================
                    // SYSTEM/IPC OPS (132-150)
                    // =================================================================

                    // Most system/IPC ops use MiscOpcodeHandler pattern: rd argsReg ctx → 3 operands
                    case Opcodes.CHOWN:
                    case Opcodes.WAITPID:
                    case Opcodes.GETPGRP:
                    case Opcodes.SETPGRP:
                    case Opcodes.GETPRIORITY:
                    case Opcodes.SETPRIORITY:
                    case Opcodes.GETSOCKOPT:
                    case Opcodes.SETSOCKOPT:
                    case Opcodes.SYMLINK:
                    case Opcodes.CHROOT:
                    case Opcodes.MKDIR:
                    case Opcodes.MSGCTL:
                    case Opcodes.SHMCTL:
                    case Opcodes.SEMCTL:
                    case Opcodes.EXEC:
                    case Opcodes.FCNTL:
                    case Opcodes.IOCTL:
                    case Opcodes.GETPWENT:
                    case Opcodes.SETPWENT:
                    case Opcodes.ENDPWENT:
                    case Opcodes.GETLOGIN:
                    case Opcodes.GETPWNAM:
                    case Opcodes.GETPWUID:
                    case Opcodes.GETGRNAM:
                    case Opcodes.GETGRGID:
                    case Opcodes.GETGRENT:
                    case Opcodes.SETGRENT:
                    case Opcodes.ENDGRENT:
                    case Opcodes.GETHOSTBYADDR:
                    case Opcodes.GETSERVBYNAME:
                    case Opcodes.GETSERVBYPORT:
                    case Opcodes.GETPROTOBYNAME:
                    case Opcodes.GETPROTOBYNUMBER:
                    case Opcodes.ENDHOSTENT:
                    case Opcodes.ENDNETENT:
                    case Opcodes.ENDPROTOENT:
                    case Opcodes.ENDSERVENT:
                    case Opcodes.GETHOSTENT:
                    case Opcodes.GETNETBYADDR:
                    case Opcodes.GETNETBYNAME:
                    case Opcodes.GETNETENT:
                    case Opcodes.GETPROTOENT:
                    case Opcodes.GETSERVENT:
                    case Opcodes.SETHOSTENT:
                    case Opcodes.SETNETENT:
                    case Opcodes.SETPROTOENT:
                    case Opcodes.SETSERVENT: {
                        rd = interpretedCode.bytecode[pc++];
                        int sysArgsReg = interpretedCode.bytecode[pc++];
                        int sysCtx = interpretedCode.bytecode[pc++];
                        String sysName = switch (opcode) {
                            case Opcodes.CHOWN -> "chown";
                            case Opcodes.WAITPID -> "waitpid";
                            case Opcodes.GETPGRP -> "getpgrp";
                            case Opcodes.SETPGRP -> "setpgrp";
                            case Opcodes.GETPRIORITY -> "getpriority";
                            case Opcodes.SETPRIORITY -> "setpriority";
                            case Opcodes.GETSOCKOPT -> "getsockopt";
                            case Opcodes.SETSOCKOPT -> "setsockopt";
                            case Opcodes.SYMLINK -> "symlink";
                            case Opcodes.CHROOT -> "chroot";
                            case Opcodes.MKDIR -> "mkdir";
                            case Opcodes.MSGCTL -> "msgctl";
                            case Opcodes.SHMCTL -> "shmctl";
                            case Opcodes.SEMCTL -> "semctl";
                            case Opcodes.EXEC -> "exec";
                            case Opcodes.FCNTL -> "fcntl";
                            case Opcodes.IOCTL -> "ioctl";
                            case Opcodes.GETPWENT -> "getpwent";
                            case Opcodes.SETPWENT -> "setpwent";
                            case Opcodes.ENDPWENT -> "endpwent";
                            case Opcodes.GETLOGIN -> "getlogin";
                            case Opcodes.GETPWNAM -> "getpwnam";
                            case Opcodes.GETPWUID -> "getpwuid";
                            case Opcodes.GETGRNAM -> "getgrnam";
                            case Opcodes.GETGRGID -> "getgrgid";
                            case Opcodes.GETGRENT -> "getgrent";
                            case Opcodes.SETGRENT -> "setgrent";
                            case Opcodes.ENDGRENT -> "endgrent";
                            case Opcodes.GETHOSTBYADDR -> "gethostbyaddr";
                            case Opcodes.GETSERVBYNAME -> "getservbyname";
                            case Opcodes.GETSERVBYPORT -> "getservbyport";
                            case Opcodes.GETPROTOBYNAME -> "getprotobyname";
                            case Opcodes.GETPROTOBYNUMBER -> "getprotobynumber";
                            case Opcodes.ENDHOSTENT -> "endhostent";
                            case Opcodes.ENDNETENT -> "endnetent";
                            case Opcodes.ENDPROTOENT -> "endprotoent";
                            case Opcodes.ENDSERVENT -> "endservent";
                            case Opcodes.GETHOSTENT -> "gethostent";
                            case Opcodes.GETNETBYADDR -> "getnetbyaddr";
                            case Opcodes.GETNETBYNAME -> "getnetbyname";
                            case Opcodes.GETNETENT -> "getnetent";
                            case Opcodes.GETPROTOENT -> "getprotoent";
                            case Opcodes.GETSERVENT -> "getservent";
                            case Opcodes.SETHOSTENT -> "sethostent";
                            case Opcodes.SETNETENT -> "setnetent";
                            case Opcodes.SETPROTOENT -> "setprotoent";
                            case Opcodes.SETSERVENT -> "setservent";
                            default -> "sys_op_" + opcode;
                        };
                        sb.append(sysName).append(" r").append(rd)
                                .append(" = ").append(sysName).append("(r").append(sysArgsReg)
                                .append(", ctx=").append(sysCtx).append(")\n");
                        break;
                    }
                    case Opcodes.FORK: {
                        // Format: FORK rd
                        rd = interpretedCode.bytecode[pc++];
                        sb.append("FORK r").append(rd).append(" = fork()\n");
                        break;
                    }
                    case Opcodes.GETPPID: {
                        // Format: GETPPID rd
                        rd = interpretedCode.bytecode[pc++];
                        sb.append("GETPPID r").append(rd).append(" = getppid()\n");
                        break;
                    }
                    case Opcodes.SYSCALL: {
                        // Format: SYSCALL rd numberReg argCount [argRegs...]
                        rd = interpretedCode.bytecode[pc++];
                        int sysNumReg = interpretedCode.bytecode[pc++];
                        int sysArgCount = interpretedCode.bytecode[pc++];
                        sb.append("SYSCALL r").append(rd).append(" = syscall(r").append(sysNumReg).append(", [");
                        for (int i = 0; i < sysArgCount; i++) {
                            if (i > 0) sb.append(", ");
                            sb.append("r").append(interpretedCode.bytecode[pc++]);
                        }
                        sb.append("])\n");
                        break;
                    }
                    case Opcodes.SEMGET: {
                        // Format: SEMGET rd keyReg nsemsReg flagsReg
                        rd = interpretedCode.bytecode[pc++];
                        int sgKeyReg = interpretedCode.bytecode[pc++];
                        int sgNsemsReg = interpretedCode.bytecode[pc++];
                        int sgFlagsReg = interpretedCode.bytecode[pc++];
                        sb.append("SEMGET r").append(rd).append(" = semget(r").append(sgKeyReg)
                                .append(", r").append(sgNsemsReg).append(", r").append(sgFlagsReg).append(")\n");
                        break;
                    }
                    case Opcodes.SEMOP: {
                        // Format: SEMOP rd semidReg opstringReg
                        rd = interpretedCode.bytecode[pc++];
                        int soSemidReg = interpretedCode.bytecode[pc++];
                        int soOpstringReg = interpretedCode.bytecode[pc++];
                        sb.append("SEMOP r").append(rd).append(" = semop(r").append(soSemidReg)
                                .append(", r").append(soOpstringReg).append(")\n");
                        break;
                    }
                    case Opcodes.MSGGET: {
                        // Format: MSGGET rd keyReg flagsReg
                        rd = interpretedCode.bytecode[pc++];
                        int mgKeyReg = interpretedCode.bytecode[pc++];
                        int mgFlagsReg = interpretedCode.bytecode[pc++];
                        sb.append("MSGGET r").append(rd).append(" = msgget(r").append(mgKeyReg)
                                .append(", r").append(mgFlagsReg).append(")\n");
                        break;
                    }
                    case Opcodes.MSGSND: {
                        // Format: MSGSND rd idReg msgReg flagsReg
                        rd = interpretedCode.bytecode[pc++];
                        int msIdReg = interpretedCode.bytecode[pc++];
                        int msMsgReg = interpretedCode.bytecode[pc++];
                        int msFlagsReg = interpretedCode.bytecode[pc++];
                        sb.append("MSGSND r").append(rd).append(" = msgsnd(r").append(msIdReg)
                                .append(", r").append(msMsgReg).append(", r").append(msFlagsReg).append(")\n");
                        break;
                    }
                    case Opcodes.MSGRCV: {
                        // Format: MSGRCV rd idReg sizeReg typeReg flagsReg
                        rd = interpretedCode.bytecode[pc++];
                        int mrIdReg = interpretedCode.bytecode[pc++];
                        int mrSizeReg = interpretedCode.bytecode[pc++];
                        int mrTypeReg = interpretedCode.bytecode[pc++];
                        int mrFlagsReg = interpretedCode.bytecode[pc++];
                        sb.append("MSGRCV r").append(rd).append(" = msgrcv(r").append(mrIdReg)
                                .append(", r").append(mrSizeReg).append(", r").append(mrTypeReg)
                                .append(", r").append(mrFlagsReg).append(")\n");
                        break;
                    }
                    case Opcodes.SHMGET: {
                        // Format: SHMGET rd keyReg sizeReg flagsReg
                        rd = interpretedCode.bytecode[pc++];
                        int shgKeyReg = interpretedCode.bytecode[pc++];
                        int shgSizeReg = interpretedCode.bytecode[pc++];
                        int shgFlagsReg = interpretedCode.bytecode[pc++];
                        sb.append("SHMGET r").append(rd).append(" = shmget(r").append(shgKeyReg)
                                .append(", r").append(shgSizeReg).append(", r").append(shgFlagsReg).append(")\n");
                        break;
                    }
                    case Opcodes.SHMREAD: {
                        // Format: SHMREAD rd idReg posReg sizeReg
                        rd = interpretedCode.bytecode[pc++];
                        int shrIdReg = interpretedCode.bytecode[pc++];
                        int shrPosReg = interpretedCode.bytecode[pc++];
                        int shrSizeReg = interpretedCode.bytecode[pc++];
                        sb.append("SHMREAD r").append(rd).append(" = shmread(r").append(shrIdReg)
                                .append(", r").append(shrPosReg).append(", r").append(shrSizeReg).append(")\n");
                        break;
                    }
                    case Opcodes.SHMWRITE: {
                        // Format: SHMWRITE idReg posReg stringReg
                        int shwIdReg = interpretedCode.bytecode[pc++];
                        int shwPosReg = interpretedCode.bytecode[pc++];
                        int shwStringReg = interpretedCode.bytecode[pc++];
                        sb.append("SHMWRITE shmwrite(r").append(shwIdReg)
                                .append(", r").append(shwPosReg).append(", r").append(shwStringReg).append(")\n");
                        break;
                    }

                    // =================================================================
                    // OPERATOR PROMOTIONS (156-157, 310)
                    // =================================================================

                    case Opcodes.OP_ABS:
                        rd = interpretedCode.bytecode[pc++];
                        rs1 = interpretedCode.bytecode[pc++];
                        sb.append("OP_ABS r").append(rd).append(" = abs(r").append(rs1).append(")\n");
                        break;
                    case Opcodes.OP_INT:
                        rd = interpretedCode.bytecode[pc++];
                        rs1 = interpretedCode.bytecode[pc++];
                        sb.append("OP_INT r").append(rd).append(" = int(r").append(rs1).append(")\n");
                        break;
                    case Opcodes.OP_POW:
                        rd = interpretedCode.bytecode[pc++];
                        rs1 = interpretedCode.bytecode[pc++];
                        rs2 = interpretedCode.bytecode[pc++];
                        sb.append("OP_POW r").append(rd).append(" = r").append(rs1).append(" ** r").append(rs2).append("\n");
                        break;

                    // =================================================================
                    // MISC OPERATORS
                    // =================================================================

                    case Opcodes.TR_TRANSLITERATE: {
                        // Format: TR_TRANSLITERATE rd searchReg replaceReg modifiersReg targetReg context
                        rd = interpretedCode.bytecode[pc++];
                        int trSearchReg = interpretedCode.bytecode[pc++];
                        int trReplaceReg = interpretedCode.bytecode[pc++];
                        int trModifiersReg = interpretedCode.bytecode[pc++];
                        int trTargetReg = interpretedCode.bytecode[pc++];
                        int trCtx = interpretedCode.bytecode[pc++];
                        sb.append("TR_TRANSLITERATE r").append(rd).append(" = tr(r").append(trSearchReg)
                                .append(", r").append(trReplaceReg).append(", r").append(trModifiersReg)
                                .append(", r").append(trTargetReg).append(", ctx=").append(trCtx).append(")\n");
                        break;
                    }
                    case Opcodes.STORE_SYMBOLIC_SCALAR: {
                        // Format: STORE_SYMBOLIC_SCALAR nameReg valueReg
                        int ssNameReg = interpretedCode.bytecode[pc++];
                        int ssValueReg = interpretedCode.bytecode[pc++];
                        sb.append("STORE_SYMBOLIC_SCALAR ${r").append(ssNameReg).append("} = r").append(ssValueReg).append("\n");
                        break;
                    }
                    case Opcodes.LOAD_SYMBOLIC_SCALAR: {
                        // Format: LOAD_SYMBOLIC_SCALAR rd nameReg
                        rd = interpretedCode.bytecode[pc++];
                        int lsNameReg = interpretedCode.bytecode[pc++];
                        sb.append("LOAD_SYMBOLIC_SCALAR r").append(rd).append(" = ${r").append(lsNameReg).append("}\n");
                        break;
                    }
                    case Opcodes.TIE: {
                        // Format: TIE rd argsReg ctx
                        rd = interpretedCode.bytecode[pc++];
                        int tieArgsReg = interpretedCode.bytecode[pc++];
                        int tieCtx = interpretedCode.bytecode[pc++];
                        sb.append("TIE r").append(rd).append(" = tie(r").append(tieArgsReg)
                                .append(", ctx=").append(tieCtx).append(")\n");
                        break;
                    }
                    case Opcodes.UNTIE: {
                        // Format: UNTIE rd argsReg ctx
                        rd = interpretedCode.bytecode[pc++];
                        int untieArgsReg = interpretedCode.bytecode[pc++];
                        int untieCtx = interpretedCode.bytecode[pc++];
                        sb.append("UNTIE r").append(rd).append(" = untie(r").append(untieArgsReg)
                                .append(", ctx=").append(untieCtx).append(")\n");
                        break;
                    }
                    case Opcodes.TIED: {
                        // Format: TIED rd argsReg ctx
                        rd = interpretedCode.bytecode[pc++];
                        int tiedArgsReg = interpretedCode.bytecode[pc++];
                        int tiedCtx = interpretedCode.bytecode[pc++];
                        sb.append("TIED r").append(rd).append(" = tied(r").append(tiedArgsReg)
                                .append(", ctx=").append(tiedCtx).append(")\n");
                        break;
                    }
                    case Opcodes.QX: {
                        // Format: QX rd argsReg ctx (MiscOpcodeHandler pattern)
                        rd = interpretedCode.bytecode[pc++];
                        int qxArgsReg = interpretedCode.bytecode[pc++];
                        int qxCtx = interpretedCode.bytecode[pc++];
                        sb.append("QX r").append(rd).append(" = qx(r").append(qxArgsReg)
                                .append(", ctx=").append(qxCtx).append(")\n");
                        break;
                    }

                    // =================================================================
                    // I/O OPERATIONS (309-330)
                    // =================================================================

                    case Opcodes.CLOSE:
                    case Opcodes.BINMODE:
                    case Opcodes.SEEK:
                    case Opcodes.EOF_OP:
                    case Opcodes.SYSREAD:
                    case Opcodes.SYSWRITE:
                    case Opcodes.SYSOPEN:
                    case Opcodes.SOCKET:
                    case Opcodes.BIND:
                    case Opcodes.CONNECT:
                    case Opcodes.LISTEN:
                    case Opcodes.WRITE:
                    case Opcodes.FORMLINE:
                    case Opcodes.PRINTF:
                    case Opcodes.ACCEPT:
                    case Opcodes.SYSSEEK:
                    case Opcodes.TRUNCATE:
                    case Opcodes.FLOCK:
                    case Opcodes.READ:
                    case Opcodes.OPENDIR:
                    case Opcodes.READDIR:
                    case Opcodes.SEEKDIR: {
                        // All use MiscOpcodeHandler pattern: rd argsReg ctx
                        rd = interpretedCode.bytecode[pc++];
                        int ioArgsReg = interpretedCode.bytecode[pc++];
                        int ioCtx = interpretedCode.bytecode[pc++];
                        String ioName = switch (opcode) {
                            case Opcodes.CLOSE -> "close";
                            case Opcodes.BINMODE -> "binmode";
                            case Opcodes.SEEK -> "seek";
                            case Opcodes.EOF_OP -> "eof";
                            case Opcodes.SYSREAD -> "sysread";
                            case Opcodes.SYSWRITE -> "syswrite";
                            case Opcodes.SYSOPEN -> "sysopen";
                            case Opcodes.SOCKET -> "socket";
                            case Opcodes.BIND -> "bind";
                            case Opcodes.CONNECT -> "connect";
                            case Opcodes.LISTEN -> "listen";
                            case Opcodes.WRITE -> "write";
                            case Opcodes.FORMLINE -> "formline";
                            case Opcodes.PRINTF -> "printf";
                            case Opcodes.ACCEPT -> "accept";
                            case Opcodes.SYSSEEK -> "sysseek";
                            case Opcodes.TRUNCATE -> "truncate";
                            case Opcodes.FLOCK -> "flock";
                            case Opcodes.READ -> "read";
                            case Opcodes.OPENDIR -> "opendir";
                            case Opcodes.READDIR -> "readdir";
                            case Opcodes.SEEKDIR -> "seekdir";
                            default -> "io_op_" + opcode;
                        };
                        sb.append(ioName).append(" r").append(rd)
                                .append(" = ").append(ioName).append("(r").append(ioArgsReg)
                                .append(", ctx=").append(ioCtx).append(")\n");
                        break;
                    }

                    // =================================================================
                    // OTHER MISSING OPS
                    // =================================================================

                    case Opcodes.LOAD_GLOB_DYNAMIC: {
                        // Format: LOAD_GLOB_DYNAMIC rd nameReg pkgIdx
                        rd = interpretedCode.bytecode[pc++];
                        int lgdNameReg = interpretedCode.bytecode[pc++];
                        int lgdPkgIdx = interpretedCode.bytecode[pc++];
                        sb.append("LOAD_GLOB_DYNAMIC r").append(rd).append(" = *{r").append(lgdNameReg)
                                .append("} pkg=").append(interpretedCode.stringPool[lgdPkgIdx]).append("\n");
                        break;
                    }
                    case Opcodes.SET_ARRAY_LAST_INDEX: {
                        // Format: SET_ARRAY_LAST_INDEX arrayReg valueReg
                        int saliArrayReg = interpretedCode.bytecode[pc++];
                        int saliValueReg = interpretedCode.bytecode[pc++];
                        sb.append("SET_ARRAY_LAST_INDEX $#r").append(saliArrayReg).append(" = r").append(saliValueReg).append("\n");
                        break;
                    }
                    case Opcodes.XOR_LOGICAL:
                        rd = interpretedCode.bytecode[pc++];
                        rs1 = interpretedCode.bytecode[pc++];
                        rs2 = interpretedCode.bytecode[pc++];
                        sb.append("XOR_LOGICAL r").append(rd).append(" = r").append(rs1).append(" xor r").append(rs2).append("\n");
                        break;
                    case Opcodes.DEFINED_OR_ASSIGN:
                        rd = interpretedCode.bytecode[pc++];
                        rs1 = interpretedCode.bytecode[pc++];
                        sb.append("DEFINED_OR_ASSIGN r").append(rd).append(" //= r").append(rs1).append("\n");
                        break;
                    case Opcodes.UNDEFINE_SCALAR:
                        rd = interpretedCode.bytecode[pc++];
                        sb.append("UNDEFINE_SCALAR r").append(rd).append("\n");
                        break;
                    case Opcodes.SAVE_REGEX_STATE: {
                        // Format: SAVE_REGEX_STATE dummy
                        int srsDummy = interpretedCode.bytecode[pc++];
                        sb.append("SAVE_REGEX_STATE r").append(srsDummy).append("\n");
                        break;
                    }
                    case Opcodes.RESTORE_REGEX_STATE: {
                        // Format: RESTORE_REGEX_STATE dummy
                        int rrsDummy = interpretedCode.bytecode[pc++];
                        sb.append("RESTORE_REGEX_STATE r").append(rrsDummy).append("\n");
                        break;
                    }
                    case Opcodes.SLOW_OP: {
                        // Deprecated: SLOW_OP was removed, all operations now use direct opcodes
                        // Format was: SLOW_OP slow_op_id rd argsReg ctx
                        int slowOpId = interpretedCode.bytecode[pc++];
                        rd = interpretedCode.bytecode[pc++];
                        int slowArgsReg = interpretedCode.bytecode[pc++];
                        int slowCtx = interpretedCode.bytecode[pc++];
                        sb.append("SLOW_OP(").append(slowOpId).append(") r").append(rd)
                                .append(" = slow(r").append(slowArgsReg).append(", ctx=").append(slowCtx).append(")\n");
                        break;
                    }

                    case Opcodes.DEBUG: {
                        int fileIdx = interpretedCode.bytecode[pc++];
                        int line = interpretedCode.bytecode[pc++];
                        sb.append("DEBUG file=\"").append(interpretedCode.stringPool[fileIdx])
                                .append("\" line=").append(line).append("\n");
                        break;
                    }

                    // =================================================================
                    // SUPEROPERATORS
                    // =================================================================

                    case Opcodes.HASH_DEREF_FETCH: {
                        rd = interpretedCode.bytecode[pc++];
                        int hashrefReg = interpretedCode.bytecode[pc++];
                        int keyIdx = interpretedCode.bytecode[pc++];
                        sb.append("HASH_DEREF_FETCH r").append(rd)
                                .append(" = r").append(hashrefReg).append("->{\"");
                        if (interpretedCode.stringPool != null && keyIdx < interpretedCode.stringPool.length) {
                            sb.append(interpretedCode.stringPool[keyIdx]);
                        }
                        sb.append("\"}\n");
                        break;
                    }

                    case Opcodes.ARRAY_DEREF_FETCH: {
                        rd = interpretedCode.bytecode[pc++];
                        int arrayrefReg = interpretedCode.bytecode[pc++];
                        int index = InterpretedCode.readInt(interpretedCode.bytecode, pc);
                        pc += 1;
                        sb.append("ARRAY_DEREF_FETCH r").append(rd)
                                .append(" = r").append(arrayrefReg).append("->[").append(index).append("]\n");
                        break;
                    }

                    case Opcodes.HASH_DEREF_FETCH_NONSTRICT: {
                        rd = interpretedCode.bytecode[pc++];
                        int hashrefReg = interpretedCode.bytecode[pc++];
                        int keyIdx = interpretedCode.bytecode[pc++];
                        int pkgIdxH = interpretedCode.bytecode[pc++];
                        sb.append("HASH_DEREF_FETCH_NONSTRICT r").append(rd)
                                .append(" = r").append(hashrefReg).append("->{\"");
                        if (interpretedCode.stringPool != null && keyIdx < interpretedCode.stringPool.length) {
                            sb.append(interpretedCode.stringPool[keyIdx]);
                        }
                        sb.append("\"} pkg=");
                        if (interpretedCode.stringPool != null && pkgIdxH < interpretedCode.stringPool.length) {
                            sb.append(interpretedCode.stringPool[pkgIdxH]);
                        }
                        sb.append("\n");
                        break;
                    }

                    case Opcodes.ARRAY_DEREF_FETCH_NONSTRICT: {
                        rd = interpretedCode.bytecode[pc++];
                        int arrayrefReg = interpretedCode.bytecode[pc++];
                        int index = InterpretedCode.readInt(interpretedCode.bytecode, pc);
                        pc += 1;
                        int pkgIdxA = interpretedCode.bytecode[pc++];
                        sb.append("ARRAY_DEREF_FETCH_NONSTRICT r").append(rd)
                                .append(" = r").append(arrayrefReg).append("->[").append(index).append("] pkg=");
                        if (interpretedCode.stringPool != null && pkgIdxA < interpretedCode.stringPool.length) {
                            sb.append(interpretedCode.stringPool[pkgIdxA]);
                        }
                        sb.append("\n");
                        break;
                    }
                    case Opcodes.STATE_INIT_SCALAR:
                    case Opcodes.STATE_INIT_ARRAY:
                    case Opcodes.STATE_INIT_HASH: {
                        String name = opcode == Opcodes.STATE_INIT_SCALAR ? "STATE_INIT_SCALAR" :
                                opcode == Opcodes.STATE_INIT_ARRAY ? "STATE_INIT_ARRAY" : "STATE_INIT_HASH";
                        int stRd = interpretedCode.bytecode[pc++];
                        int stVal = interpretedCode.bytecode[pc++];
                        int stName = interpretedCode.bytecode[pc++];
                        int stPersist = interpretedCode.bytecode[pc++];
                        sb.append(name).append(" r").append(stRd).append(", r").append(stVal)
                                .append(", name=").append(stName).append(", persist=").append(stPersist).append("\n");
                        break;
                    }
                    case Opcodes.SMARTMATCH: {
                        int smRd = interpretedCode.bytecode[pc++];
                        int smRs1 = interpretedCode.bytecode[pc++];
                        int smRs2 = interpretedCode.bytecode[pc++];
                        sb.append("SMARTMATCH r").append(smRd).append(", r").append(smRs1).append(", r").append(smRs2).append("\n");
                        break;
                    }

                    default:
                        sb.append("UNKNOWN(").append(opcode).append(")\n");
                        break;
                }
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            sb.append("\n*** Disassembly error at pc=").append(pc).append(": ").append(e.getMessage()).append(" ***\n");
        }
        return sb.toString();
    }
}
