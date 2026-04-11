package org.perlonjava.backend.bytecode;

import org.perlonjava.backend.jvm.JvmClosureTemplate;
import org.perlonjava.runtime.operators.*;
import org.perlonjava.runtime.perlmodule.Attributes;
import org.perlonjava.runtime.regex.RuntimeRegex;
import org.perlonjava.runtime.runtimetypes.*;

/**
 * Extended opcode handlers for recently added operations.
 * <p>
 * Extracted from BytecodeInterpreter.execute() to reduce method size
 * and keep it under the 8KB JIT compilation limit.
 * <p>
 * Handles: SPRINTF, CHOP, GET_REPLACEMENT_REGEX, SUBSTR_VAR, and other
 * less-frequently-used string/regex operations.
 */
public class OpcodeHandlerExtended {

    /**
     * Execute sprintf operation.
     * Format: SPRINTF rd formatReg argsListReg
     *
     * @param bytecode  The bytecode array
     * @param pc        Current program counter
     * @param registers Register file
     * @return Updated program counter
     */
    public static int executeSprintf(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int formatReg = bytecode[pc++];
        int argsListReg = bytecode[pc++];

        RuntimeScalar format = (RuntimeScalar) registers[formatReg];
        RuntimeList argsList = (RuntimeList) registers[argsListReg];

        registers[rd] = SprintfOperator.sprintf(format, argsList);
        return pc;
    }

    /**
     * Execute chop operation.
     * Format: CHOP rd scalarReg
     *
     * @param bytecode  The bytecode array
     * @param pc        Current program counter
     * @param registers Register file
     * @return Updated program counter
     */
    public static int executeChop(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int scalarReg = bytecode[pc++];

        registers[rd] = registers[scalarReg].chop();
        return pc;
    }

    /**
     * Execute get replacement regex operation.
     * Format: GET_REPLACEMENT_REGEX rd pattern_reg replacement_reg flags_reg args_reg
     *
     * @param bytecode  The bytecode array
     * @param pc        Current program counter
     * @param registers Register file
     * @return Updated program counter
     */
    public static int executeGetReplacementRegex(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int patternReg = bytecode[pc++];
        int replacementReg = bytecode[pc++];
        int flagsReg = bytecode[pc++];
        int argsReg = bytecode[pc++];

        RuntimeScalar pattern = (RuntimeScalar) registers[patternReg];
        RuntimeScalar replacement = (RuntimeScalar) registers[replacementReg];
        RuntimeScalar flags = (RuntimeScalar) registers[flagsReg];
        RuntimeArray callerArgs = (registers[argsReg] instanceof RuntimeArray) ? (RuntimeArray) registers[argsReg] : new RuntimeArray();

        registers[rd] = RuntimeRegex.getReplacementRegex(pattern, replacement, flags, callerArgs);
        return pc;
    }

    /**
     * Execute substr with variable arguments.
     * Format: SUBSTR_VAR rd argsListReg ctx
     *
     * @param bytecode  The bytecode array
     * @param pc        Current program counter
     * @param registers Register file
     * @return Updated program counter
     */
    public static int executeSubstrVar(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int argsListReg = bytecode[pc++];
        int ctx = bytecode[pc++];

        if (ctx == RuntimeContextType.RUNTIME) ctx = ((RuntimeScalar) registers[2]).getInt();

        RuntimeList argsList = (RuntimeList) registers[argsListReg];
        RuntimeBase[] substrArgs = argsList.elements.toArray(new RuntimeBase[0]);

        registers[rd] = Operator.substr(ctx, substrArgs);
        return pc;
    }

    /**
     * Execute substr with variable args, no warning.
     * Format: SUBSTR_VAR_NO_WARN rd argsListReg ctx
     * Used when 'no warnings "substr"' is in effect at compile time.
     *
     * @param bytecode  The bytecode array
     * @param pc        Current program counter
     * @param registers Register file
     * @return Updated program counter
     */
    public static int executeSubstrVarNoWarn(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int argsListReg = bytecode[pc++];
        int ctx = bytecode[pc++];

        if (ctx == RuntimeContextType.RUNTIME) ctx = ((RuntimeScalar) registers[2]).getInt();

        RuntimeList argsList = (RuntimeList) registers[argsListReg];
        RuntimeBase[] substrArgs = argsList.elements.toArray(new RuntimeBase[0]);

        registers[rd] = Operator.substrNoWarn(ctx, substrArgs);
        return pc;
    }

    /**
     * Execute repeat assign operation.
     * Format: REPEAT_ASSIGN rd rs
     *
     * @param bytecode  The bytecode array
     * @param pc        Current program counter
     * @param registers Register file
     * @return Updated program counter
     */
    public static int executeRepeatAssign(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        if (BytecodeInterpreter.isImmutableProxy(registers[rd])) {
            registers[rd] = BytecodeInterpreter.ensureMutableScalar(registers[rd]);
        }
        RuntimeBase result = Operator.repeat(
                registers[rd],
                (RuntimeScalar) registers[rs],
                1  // scalar context
        );
        ((RuntimeScalar) registers[rd]).set((RuntimeScalar) result);
        return pc;
    }

    /**
     * Execute power assign operation.
     * Format: POW_ASSIGN rd rs
     *
     * @param bytecode  The bytecode array
     * @param pc        Current program counter
     * @param registers Register file
     * @return Updated program counter
     */
    public static int executePowAssign(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        if (BytecodeInterpreter.isImmutableProxy(registers[rd])) {
            registers[rd] = BytecodeInterpreter.ensureMutableScalar(registers[rd]);
        }
        RuntimeBase val1 = registers[rd];
        RuntimeBase val2 = registers[rs];
        RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
        RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();
        // Use warn variant - it checks at runtime if warnings are enabled
        RuntimeScalar result = MathOperators.powWarn(s1, s2);
        ((RuntimeScalar) registers[rd]).set(result);
        return pc;
    }

    /**
     * Execute left shift assign operation.
     * Format: LEFT_SHIFT_ASSIGN rd rs
     *
     * @param bytecode  The bytecode array
     * @param pc        Current program counter
     * @param registers Register file
     * @return Updated program counter
     */
    public static int executeLeftShiftAssign(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        if (BytecodeInterpreter.isImmutableProxy(registers[rd])) {
            registers[rd] = BytecodeInterpreter.ensureMutableScalar(registers[rd]);
        }
        RuntimeScalar s1 = (RuntimeScalar) registers[rd];
        RuntimeScalar s2 = (RuntimeScalar) registers[rs];
        RuntimeScalar result = BitwiseOperators.shiftLeft(s1, s2);
        s1.set(result);
        return pc;
    }

    /**
     * Execute right shift assign operation.
     * Format: RIGHT_SHIFT_ASSIGN rd rs
     *
     * @param bytecode  The bytecode array
     * @param pc        Current program counter
     * @param registers Register file
     * @return Updated program counter
     */
    public static int executeRightShiftAssign(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        if (BytecodeInterpreter.isImmutableProxy(registers[rd])) {
            registers[rd] = BytecodeInterpreter.ensureMutableScalar(registers[rd]);
        }
        RuntimeScalar s1 = (RuntimeScalar) registers[rd];
        RuntimeScalar s2 = (RuntimeScalar) registers[rs];
        RuntimeScalar result = BitwiseOperators.shiftRight(s1, s2);
        s1.set(result);
        return pc;
    }

    /**
     * Execute logical AND assign operation.
     * Format: LOGICAL_AND_ASSIGN rd rs
     *
     * @param bytecode  The bytecode array
     * @param pc        Current program counter
     * @param registers Register file
     * @return Updated program counter
     */
    public static int executeLogicalAndAssign(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        if (BytecodeInterpreter.isImmutableProxy(registers[rd])) {
            registers[rd] = BytecodeInterpreter.ensureMutableScalar(registers[rd]);
        }
        RuntimeScalar s1 = registers[rd].scalar();
        if (!s1.getBoolean()) {
            return pc;
        }
        RuntimeScalar s2 = registers[rs].scalar();
        ((RuntimeScalar) registers[rd]).set(s2);
        return pc;
    }

    /**
     * Execute logical OR assign operation.
     * Format: LOGICAL_OR_ASSIGN rd rs
     *
     * @param bytecode  The bytecode array
     * @param pc        Current program counter
     * @param registers Register file
     * @return Updated program counter
     */
    public static int executeLogicalOrAssign(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        if (BytecodeInterpreter.isImmutableProxy(registers[rd])) {
            registers[rd] = BytecodeInterpreter.ensureMutableScalar(registers[rd]);
        }
        RuntimeScalar s1 = registers[rd].scalar();
        if (s1.getBoolean()) {
            return pc;
        }
        RuntimeScalar s2 = registers[rs].scalar();
        ((RuntimeScalar) registers[rd]).set(s2);
        return pc;
    }

    public static int executeDefinedOrAssign(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        if (BytecodeInterpreter.isImmutableProxy(registers[rd])) {
            registers[rd] = BytecodeInterpreter.ensureMutableScalar(registers[rd]);
        }
        RuntimeScalar s1 = registers[rd].scalar();
        if (s1.getDefinedBoolean()) {
            return pc;
        }
        RuntimeScalar s2 = registers[rs].scalar();
        ((RuntimeScalar) registers[rd]).set(s2);
        return pc;
    }

    /**
     * Execute string concatenation assign operation.
     * Format: STRING_CONCAT_ASSIGN rd rs
     *
     * @param bytecode  The bytecode array
     * @param pc        Current program counter
     * @param registers Register file
     * @return Updated program counter
     */
    public static int executeStringConcatAssign(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        if (BytecodeInterpreter.isImmutableProxy(registers[rd])) {
            registers[rd] = BytecodeInterpreter.ensureMutableScalar(registers[rd]);
        }
        RuntimeScalar target = (RuntimeScalar) registers[rd];
        // Remember if target was BYTE_STRING before concatenation.
        // Only preserve BYTE_STRING when the concat result itself is BYTE_STRING
        // (both operands were non-UTF-8). When concat produces STRING (at least
        // one operand was UTF-8), preserve the UTF-8 flag per Perl semantics.
        boolean wasByteString = (target.type == RuntimeScalarType.BYTE_STRING);
        RuntimeScalar result = StringOperators.stringConcat(
                target,
                (RuntimeScalar) registers[rs]
        );
        target.set(result);
        // Preserve BYTE_STRING type only when both the target was byte string AND
        // the concat result was also byte string (meaning the RHS was also non-UTF-8)
        if (wasByteString && result.type == RuntimeScalarType.BYTE_STRING && target.type == RuntimeScalarType.STRING) {
            String s = target.toString();
            boolean fits = true;
            for (int i = 0; i < s.length(); i++) {
                if (s.charAt(i) > 255) {
                    fits = false;
                    break;
                }
            }
            if (fits) {
                target.type = RuntimeScalarType.BYTE_STRING;
            }
        }
        // Invalidate pos() - any string modification should reset pos to undef
        RuntimePosLvalue.invalidatePos(target);
        return pc;
    }

    /**
     * Execute bitwise AND assign operation.
     * Format: BITWISE_AND_ASSIGN rd rs
     *
     * @param bytecode  The bytecode array
     * @param pc        Current program counter
     * @param registers Register file
     * @return Updated program counter
     */
    public static int executeBitwiseAndAssign(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        if (BytecodeInterpreter.isImmutableProxy(registers[rd])) {
            registers[rd] = BytecodeInterpreter.ensureMutableScalar(registers[rd]);
        }
        RuntimeScalar result = BitwiseOperators.bitwiseAnd(
                (RuntimeScalar) registers[rd],
                (RuntimeScalar) registers[rs]
        );
        ((RuntimeScalar) registers[rd]).set(result);
        return pc;
    }

    /**
     * Execute bitwise OR assign operation.
     * Format: BITWISE_OR_ASSIGN rd rs
     *
     * @param bytecode  The bytecode array
     * @param pc        Current program counter
     * @param registers Register file
     * @return Updated program counter
     */
    public static int executeBitwiseOrAssign(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        if (BytecodeInterpreter.isImmutableProxy(registers[rd])) {
            registers[rd] = BytecodeInterpreter.ensureMutableScalar(registers[rd]);
        }
        RuntimeScalar result = BitwiseOperators.bitwiseOr(
                (RuntimeScalar) registers[rd],
                (RuntimeScalar) registers[rs]
        );
        ((RuntimeScalar) registers[rd]).set(result);
        return pc;
    }

    /**
     * Execute bitwise XOR assign operation.
     * Format: BITWISE_XOR_ASSIGN rd rs
     *
     * @param bytecode  The bytecode array
     * @param pc        Current program counter
     * @param registers Register file
     * @return Updated program counter
     */
    public static int executeBitwiseXorAssign(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        if (BytecodeInterpreter.isImmutableProxy(registers[rd])) {
            registers[rd] = BytecodeInterpreter.ensureMutableScalar(registers[rd]);
        }
        RuntimeScalar result = BitwiseOperators.bitwiseXor(
                (RuntimeScalar) registers[rd],
                (RuntimeScalar) registers[rs]
        );
        ((RuntimeScalar) registers[rd]).set(result);
        return pc;
    }

    public static int executeStringBitwiseAndAssign(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        if (BytecodeInterpreter.isImmutableProxy(registers[rd])) {
            registers[rd] = BytecodeInterpreter.ensureMutableScalar(registers[rd]);
        }
        RuntimeScalar result = BitwiseOperators.bitwiseAndDot(
                (RuntimeScalar) registers[rd],
                (RuntimeScalar) registers[rs]
        );
        ((RuntimeScalar) registers[rd]).set(result);
        return pc;
    }

    public static int executeStringBitwiseOrAssign(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        if (BytecodeInterpreter.isImmutableProxy(registers[rd])) {
            registers[rd] = BytecodeInterpreter.ensureMutableScalar(registers[rd]);
        }
        RuntimeScalar result = BitwiseOperators.bitwiseOrDot(
                (RuntimeScalar) registers[rd],
                (RuntimeScalar) registers[rs]
        );
        ((RuntimeScalar) registers[rd]).set(result);
        return pc;
    }

    public static int executeStringBitwiseXorAssign(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        if (BytecodeInterpreter.isImmutableProxy(registers[rd])) {
            registers[rd] = BytecodeInterpreter.ensureMutableScalar(registers[rd]);
        }
        RuntimeScalar result = BitwiseOperators.bitwiseXorDot(
                (RuntimeScalar) registers[rd],
                (RuntimeScalar) registers[rs]
        );
        ((RuntimeScalar) registers[rd]).set(result);
        return pc;
    }

    // Bitwise binary operators (non-assignment)

    /**
     * Execute bitwise AND binary operation.
     * Format: BITWISE_AND_BINARY rd rs1 rs2
     */
    public static int executeBitwiseAndBinary(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs1 = bytecode[pc++];
        int rs2 = bytecode[pc++];
        registers[rd] = BitwiseOperators.bitwiseAnd(
                registers[rs1].scalar(),
                registers[rs2].scalar()
        );
        return pc;
    }

    /**
     * Execute bitwise OR binary operation.
     * Format: BITWISE_OR_BINARY rd rs1 rs2
     */
    public static int executeBitwiseOrBinary(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs1 = bytecode[pc++];
        int rs2 = bytecode[pc++];
        registers[rd] = BitwiseOperators.bitwiseOr(
                registers[rs1].scalar(),
                registers[rs2].scalar()
        );
        return pc;
    }

    /**
     * Execute bitwise XOR binary operation.
     * Format: BITWISE_XOR_BINARY rd rs1 rs2
     */
    public static int executeBitwiseXorBinary(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs1 = bytecode[pc++];
        int rs2 = bytecode[pc++];
        registers[rd] = BitwiseOperators.bitwiseXor(
                registers[rs1].scalar(),
                registers[rs2].scalar()
        );
        return pc;
    }

    /**
     * Execute string bitwise AND operation.
     * Format: STRING_BITWISE_AND rd rs1 rs2
     */
    public static int executeStringBitwiseAnd(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs1 = bytecode[pc++];
        int rs2 = bytecode[pc++];
        registers[rd] = BitwiseOperators.bitwiseAndDot(
                registers[rs1].scalar(),
                registers[rs2].scalar()
        );
        return pc;
    }

    /**
     * Execute string bitwise OR operation.
     * Format: STRING_BITWISE_OR rd rs1 rs2
     */
    public static int executeStringBitwiseOr(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs1 = bytecode[pc++];
        int rs2 = bytecode[pc++];
        registers[rd] = BitwiseOperators.bitwiseOrDot(
                registers[rs1].scalar(),
                registers[rs2].scalar()
        );
        return pc;
    }

    /**
     * Execute string bitwise XOR operation.
     * Format: STRING_BITWISE_XOR rd rs1 rs2
     */
    public static int executeStringBitwiseXor(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs1 = bytecode[pc++];
        int rs2 = bytecode[pc++];
        registers[rd] = BitwiseOperators.bitwiseXorDot(
                registers[rs1].scalar(),
                registers[rs2].scalar()
        );
        return pc;
    }

    /**
     * Execute bitwise NOT binary operation.
     * Format: BITWISE_NOT_BINARY rd rs
     */
    public static int executeBitwiseNotBinary(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        registers[rd] = BitwiseOperators.bitwiseNotBinary(registers[rs].scalar());
        return pc;
    }

    /**
     * Execute bitwise NOT string operation.
     * Format: BITWISE_NOT_STRING rd rs
     */
    public static int executeBitwiseNotString(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        registers[rd] = BitwiseOperators.bitwiseNotDot(registers[rs].scalar());
        return pc;
    }

    /**
     * Execute stat operation.
     * Format: STAT rd rs ctx
     */
    public static int executeStat(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        int ctx = bytecode[pc++];

        if (ctx == RuntimeContextType.RUNTIME) ctx = ((RuntimeScalar) registers[2]).getInt();
        registers[rd] = Stat.stat((RuntimeScalar) registers[rs], ctx);
        return pc;
    }

    /**
     * Execute lstat operation.
     * Format: LSTAT rd rs ctx
     */
    public static int executeLstat(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        int ctx = bytecode[pc++];

        if (ctx == RuntimeContextType.RUNTIME) ctx = ((RuntimeScalar) registers[2]).getInt();
        registers[rd] = Stat.lstat((RuntimeScalar) registers[rs], ctx);
        return pc;
    }

    public static int executeStatLastHandle(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int ctx = bytecode[pc++];

        if (ctx == RuntimeContextType.RUNTIME) ctx = ((RuntimeScalar) registers[2]).getInt();
        registers[rd] = Stat.statLastHandle(ctx);
        return pc;
    }

    public static int executeLstatLastHandle(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int ctx = bytecode[pc++];

        if (ctx == RuntimeContextType.RUNTIME) ctx = ((RuntimeScalar) registers[2]).getInt();
        registers[rd] = Stat.lstatLastHandle(ctx);
        return pc;
    }

    /**
     * Execute print operation.
     * Format: PRINT contentReg filehandleReg
     */
    public static int executePrint(int[] bytecode, int pc, RuntimeBase[] registers) {
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
        IOOperator.print(list, fh);
        return pc;
    }

    /**
     * Execute say operation.
     * Format: SAY contentReg filehandleReg
     */
    public static int executeSay(int[] bytecode, int pc, RuntimeBase[] registers) {
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
        IOOperator.say(list, fh);
        return pc;
    }

    /**
     * Execute chomp operation.
     * Format: CHOMP rd rs
     */
    public static int executeChomp(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        registers[rd] = registers[rs].chomp();
        return pc;
    }

    /**
     * Execute wantarray operation.
     * Format: WANTARRAY rd wantarrayReg
     */
    public static int executeWantarray(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int wantarrayReg = bytecode[pc++];
        int ctx = ((RuntimeScalar) registers[wantarrayReg]).getInt();
        registers[rd] = Operator.wantarray(ctx);
        return pc;
    }

    /**
     * Execute require operation.
     * Format: REQUIRE rd rs
     */
    public static int executeRequire(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        registers[rd] = ModuleOperators.require((RuntimeScalar) registers[rs]);
        return pc;
    }

    /**
     * Execute pos operation.
     * Format: POS rd rs
     */
    public static int executePos(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        registers[rd] = ((RuntimeScalar) registers[rs]).pos();
        return pc;
    }

    /**
     * Execute index operation.
     * Format: INDEX rd strReg substrReg posReg
     */
    public static int executeIndex(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int strReg = bytecode[pc++];
        int substrReg = bytecode[pc++];
        int posReg = bytecode[pc++];
        registers[rd] = StringOperators.index(
                (RuntimeScalar) registers[strReg],
                (RuntimeScalar) registers[substrReg],
                (RuntimeScalar) registers[posReg]
        );
        return pc;
    }

    /**
     * Execute rindex operation.
     * Format: RINDEX rd strReg substrReg posReg
     */
    public static int executeRindex(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int strReg = bytecode[pc++];
        int substrReg = bytecode[pc++];
        int posReg = bytecode[pc++];
        registers[rd] = StringOperators.rindex(
                (RuntimeScalar) registers[strReg],
                (RuntimeScalar) registers[substrReg],
                (RuntimeScalar) registers[posReg]
        );
        return pc;
    }

    /**
     * Execute pre-increment operation.
     * Format: PRE_AUTOINCREMENT rd
     */
    public static int executePreAutoIncrement(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        if (BytecodeInterpreter.isImmutableProxy(registers[rd])) {
            registers[rd] = BytecodeInterpreter.ensureMutableScalar(registers[rd]);
        }
        ((RuntimeScalar) registers[rd]).preAutoIncrement();
        return pc;
    }

    /**
     * Execute post-increment operation.
     * Format: POST_AUTOINCREMENT rd rs
     */
    public static int executePostAutoIncrement(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        if (BytecodeInterpreter.isImmutableProxy(registers[rs])) {
            registers[rs] = BytecodeInterpreter.ensureMutableScalar(registers[rs]);
        }
        registers[rd] = ((RuntimeScalar) registers[rs]).postAutoIncrement();
        return pc;
    }

    /**
     * Execute pre-decrement operation.
     * Format: PRE_AUTODECREMENT rd
     */
    public static int executePreAutoDecrement(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        if (BytecodeInterpreter.isImmutableProxy(registers[rd])) {
            registers[rd] = BytecodeInterpreter.ensureMutableScalar(registers[rd]);
        }
        ((RuntimeScalar) registers[rd]).preAutoDecrement();
        return pc;
    }

    /**
     * Execute post-decrement operation.
     * Format: POST_AUTODECREMENT rd rs
     */
    public static int executePostAutoDecrement(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        if (BytecodeInterpreter.isImmutableProxy(registers[rs])) {
            registers[rs] = BytecodeInterpreter.ensureMutableScalar(registers[rs]);
        }
        registers[rd] = ((RuntimeScalar) registers[rs]).postAutoDecrement();
        return pc;
    }

    /**
     * Execute open operation.
     * Format: OPEN rd ctx argsReg
     */
    public static int executeOpen(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int ctx = bytecode[pc++];

        if (ctx == RuntimeContextType.RUNTIME) ctx = ((RuntimeScalar) registers[2]).getInt();
        int argsReg = bytecode[pc++];
        RuntimeArray argsArray = (RuntimeArray) registers[argsReg];
        RuntimeBase[] argsVarargs = argsArray.elements.toArray(new RuntimeBase[0]);
        registers[rd] = IOOperator.open(ctx, argsVarargs);
        return pc;
    }

    /**
     * Execute readline operation.
     * Format: READLINE rd fhReg ctx
     */
    public static int executeReadline(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int fhReg = bytecode[pc++];
        int ctx = bytecode[pc++];

        if (ctx == RuntimeContextType.RUNTIME) ctx = ((RuntimeScalar) registers[2]).getInt();
        RuntimeScalar fh = (RuntimeScalar) registers[fhReg];
        // Diamond operator <> passes a plain string scalar (not a glob/IO).
        // Route to DiamondIO.readline which manages @ARGV / STDIN iteration.
        if (fh.getRuntimeIO() == null) {
            registers[rd] = DiamondIO.readline(fh, ctx);
        } else {
            registers[rd] = Readline.readline(fh, ctx);
        }
        return pc;
    }

    /**
     * Execute match regex operation.
     * Format: MATCH_REGEX rd stringReg regexReg ctx
     */
    public static int executeMatchRegex(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int stringReg = bytecode[pc++];
        int regexReg = bytecode[pc++];
        int ctx = bytecode[pc++];

        if (ctx == RuntimeContextType.RUNTIME) ctx = ((RuntimeScalar) registers[2]).getInt();
        registers[rd] = RuntimeRegex.matchRegex(
                (RuntimeScalar) registers[regexReg],
                (RuntimeScalar) registers[stringReg],
                ctx
        );
        return pc;
    }

    /**
     * Execute negated match regex operation.
     * Format: MATCH_REGEX_NOT rd stringReg regexReg ctx
     */
    public static int executeMatchRegexNot(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int stringReg = bytecode[pc++];
        int regexReg = bytecode[pc++];
        int ctx = bytecode[pc++];

        if (ctx == RuntimeContextType.RUNTIME) ctx = ((RuntimeScalar) registers[2]).getInt();
        RuntimeBase matchResult = RuntimeRegex.matchRegex(
                (RuntimeScalar) registers[regexReg],
                (RuntimeScalar) registers[stringReg],
                ctx
        );
        // Negate the boolean result
        registers[rd] = new RuntimeScalar(matchResult.scalar().getBoolean() ? 0 : 1);
        return pc;
    }

    /**
     * Execute create closure operation.
     * Format: CREATE_CLOSURE rd template_idx num_captures reg1 reg2 ...
     * <p>
     * Supports both InterpretedCode templates (interpreter-compiled subs)
     * and JvmClosureTemplate (JVM-compiled subs from eval STRING).
     */
    public static int executeCreateClosure(int[] bytecode, int pc, RuntimeBase[] registers, InterpretedCode code) {
        int rd = bytecode[pc++];
        int templateIdx = bytecode[pc++];
        int numCaptures = bytecode[pc++];

        // Capture the current register values
        RuntimeBase[] capturedVars = new RuntimeBase[numCaptures];
        for (int i = 0; i < numCaptures; i++) {
            int captureReg = bytecode[pc++];
            capturedVars[i] = registers[captureReg];
        }

        Object template = code.constants[templateIdx];

        if (template instanceof JvmClosureTemplate jvmTemplate) {
            // JVM-compiled closure: instantiate the generated class with captured variables
            registers[rd] = jvmTemplate.instantiate(capturedVars);
        } else {
            // InterpretedCode closure: create a new copy with captured variables
            InterpretedCode interpTemplate = (InterpretedCode) template;
            InterpretedCode closureCode = interpTemplate.withCapturedVars(capturedVars);

            // Track captureCount on captured RuntimeScalar variables.
            // This mirrors what RuntimeCode.makeCodeObject() does for JVM-compiled closures.
            // Without this, scopeExitCleanup() doesn't know the variable is still alive
            // via this closure, and may prematurely clear weak references to its value.
            java.util.List<RuntimeScalar> capturedScalars = new java.util.ArrayList<>();
            for (RuntimeBase captured : capturedVars) {
                if (captured instanceof RuntimeScalar s) {
                    capturedScalars.add(s);
                    s.captureCount++;
                }
            }
            if (!capturedScalars.isEmpty()) {
                closureCode.capturedScalars = capturedScalars.toArray(new RuntimeScalar[0]);
                closureCode.refCount = 0;
            }

            // Wrap in RuntimeScalar and set __SUB__ for self-reference
            RuntimeScalar codeRef = new RuntimeScalar(closureCode);
            closureCode.__SUB__ = codeRef;
            registers[rd] = codeRef;

            // Dispatch MODIFY_CODE_ATTRIBUTES for anonymous subs with non-builtin attributes
            // Pass isClosure=true since CREATE_CLOSURE always creates a closure
            if (closureCode.attributes != null && !closureCode.attributes.isEmpty()
                    && closureCode.packageName != null) {
                Attributes.runtimeDispatchModifyCodeAttributes(closureCode.packageName, codeRef, true);
            }
        }
        return pc;
    }

    /**
     * Execute iterator create operation.
     * Format: ITERATOR_CREATE rd rs
     */
    public static int executeIteratorCreate(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];

        RuntimeBase iterable = registers[rs];
        java.util.Iterator<RuntimeScalar> iterator = iterable.iterator();

        // Store iterator as a constant (preserve the Iterator object)
        // Wrap in RuntimeScalar for storage
        registers[rd] = new RuntimeScalar(iterator);
        return pc;
    }

    /**
     * Execute iterator has next operation.
     * Format: ITERATOR_HAS_NEXT rd iterReg
     */
    public static int executeIteratorHasNext(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int iterReg = bytecode[pc++];

        RuntimeScalar iterScalar = (RuntimeScalar) registers[iterReg];
        @SuppressWarnings("unchecked")
        java.util.Iterator<RuntimeScalar> iterator =
                (java.util.Iterator<RuntimeScalar>) iterScalar.value;

        boolean hasNext = iterator.hasNext();
        registers[rd] = hasNext ? RuntimeScalarCache.scalarTrue : RuntimeScalarCache.scalarFalse;
        return pc;
    }

    /**
     * Execute iterator next operation.
     * Format: ITERATOR_NEXT rd iterReg
     */
    public static int executeIteratorNext(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int iterReg = bytecode[pc++];

        RuntimeScalar iterScalar = (RuntimeScalar) registers[iterReg];
        @SuppressWarnings("unchecked")
        java.util.Iterator<RuntimeScalar> iterator =
                (java.util.Iterator<RuntimeScalar>) iterScalar.value;

        RuntimeScalar next = iterator.next();
        registers[rd] = BytecodeInterpreter.isImmutableProxy(next) ? BytecodeInterpreter.ensureMutableScalar(next) : next;
        return pc;
    }

    /**
     * Execute subtract assign operation.
     * Format: SUBTRACT_ASSIGN rd rs
     */
    public static int executeSubtractAssign(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];

        RuntimeBase val1 = registers[rd];
        if (BytecodeInterpreter.isImmutableProxy(val1)) {
            val1 = BytecodeInterpreter.ensureMutableScalar(val1);
            registers[rd] = val1;
        }
        RuntimeBase val2 = registers[rs];
        RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
        RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();

        // Note: -= does NOT warn for uninitialized values in Perl
        registers[rd] = MathOperators.subtractAssign(s1, s2);
        return pc;
    }

    /**
     * Execute multiply assign operation.
     * Format: MULTIPLY_ASSIGN rd rs
     */
    public static int executeMultiplyAssign(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];

        RuntimeBase val1 = registers[rd];
        if (BytecodeInterpreter.isImmutableProxy(val1)) {
            val1 = BytecodeInterpreter.ensureMutableScalar(val1);
            registers[rd] = val1;
        }
        RuntimeBase val2 = registers[rs];
        RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
        RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();

        registers[rd] = MathOperators.multiplyAssignWarn(s1, s2);
        return pc;
    }

    /**
     * Execute divide assign operation.
     * Format: DIVIDE_ASSIGN rd rs
     */
    public static int executeDivideAssign(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];

        if (BytecodeInterpreter.isImmutableProxy(registers[rd])) {
            registers[rd] = BytecodeInterpreter.ensureMutableScalar(registers[rd]);
        }
        RuntimeBase val1 = registers[rd];
        RuntimeBase val2 = registers[rs];
        RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
        RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();

        registers[rd] = MathOperators.divideAssignWarn(s1, s2);
        return pc;
    }

    public static int executeModulusAssign(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];

        if (BytecodeInterpreter.isImmutableProxy(registers[rd])) {
            registers[rd] = BytecodeInterpreter.ensureMutableScalar(registers[rd]);
        }
        RuntimeBase val1 = registers[rd];
        RuntimeBase val2 = registers[rs];
        RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
        RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();

        registers[rd] = MathOperators.modulusAssignWarn(s1, s2);
        return pc;
    }
}
