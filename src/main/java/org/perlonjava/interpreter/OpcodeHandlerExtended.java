package org.perlonjava.interpreter;

import org.perlonjava.operators.*;
import org.perlonjava.regex.RuntimeRegex;
import org.perlonjava.runtime.*;

/**
 * Extended opcode handlers for recently added operations.
 *
 * Extracted from BytecodeInterpreter.execute() to reduce method size
 * and keep it under the 8KB JIT compilation limit.
 *
 * Handles: SPRINTF, CHOP, GET_REPLACEMENT_REGEX, SUBSTR_VAR, and other
 * less-frequently-used string/regex operations.
 */
public class OpcodeHandlerExtended {

    /**
     * Execute sprintf operation.
     * Format: SPRINTF rd formatReg argsListReg
     *
     * @param bytecode The bytecode array
     * @param pc Current program counter
     * @param registers Register file
     * @return Updated program counter
     */
    public static int executeSprintf(short[] bytecode, int pc, RuntimeBase[] registers) {
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
     * @param bytecode The bytecode array
     * @param pc Current program counter
     * @param registers Register file
     * @return Updated program counter
     */
    public static int executeChop(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int scalarReg = bytecode[pc++];

        RuntimeScalar scalar = (RuntimeScalar) registers[scalarReg];
        registers[rd] = StringOperators.chopScalar(scalar);
        return pc;
    }

    /**
     * Execute get replacement regex operation.
     * Format: GET_REPLACEMENT_REGEX rd pattern_reg replacement_reg flags_reg
     *
     * @param bytecode The bytecode array
     * @param pc Current program counter
     * @param registers Register file
     * @return Updated program counter
     */
    public static int executeGetReplacementRegex(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int patternReg = bytecode[pc++];
        int replacementReg = bytecode[pc++];
        int flagsReg = bytecode[pc++];

        RuntimeScalar pattern = (RuntimeScalar) registers[patternReg];
        RuntimeScalar replacement = (RuntimeScalar) registers[replacementReg];
        RuntimeScalar flags = (RuntimeScalar) registers[flagsReg];

        registers[rd] = RuntimeRegex.getReplacementRegex(pattern, replacement, flags);
        return pc;
    }

    /**
     * Execute substr with variable arguments.
     * Format: SUBSTR_VAR rd argsListReg ctx
     *
     * @param bytecode The bytecode array
     * @param pc Current program counter
     * @param registers Register file
     * @return Updated program counter
     */
    public static int executeSubstrVar(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int argsListReg = bytecode[pc++];
        int ctx = bytecode[pc++];

        RuntimeList argsList = (RuntimeList) registers[argsListReg];
        RuntimeBase[] substrArgs = argsList.elements.toArray(new RuntimeBase[0]);

        registers[rd] = Operator.substr(ctx, substrArgs);
        return pc;
    }

    /**
     * Execute repeat assign operation.
     * Format: REPEAT_ASSIGN rd rs
     *
     * @param bytecode The bytecode array
     * @param pc Current program counter
     * @param registers Register file
     * @return Updated program counter
     */
    public static int executeRepeatAssign(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
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
     * @param bytecode The bytecode array
     * @param pc Current program counter
     * @param registers Register file
     * @return Updated program counter
     */
    public static int executePowAssign(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        RuntimeBase val1 = registers[rd];
        RuntimeBase val2 = registers[rs];
        RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
        RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();
        RuntimeScalar result = MathOperators.pow(s1, s2);
        ((RuntimeScalar) registers[rd]).set(result);
        return pc;
    }

    /**
     * Execute left shift assign operation.
     * Format: LEFT_SHIFT_ASSIGN rd rs
     *
     * @param bytecode The bytecode array
     * @param pc Current program counter
     * @param registers Register file
     * @return Updated program counter
     */
    public static int executeLeftShiftAssign(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
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
     * @param bytecode The bytecode array
     * @param pc Current program counter
     * @param registers Register file
     * @return Updated program counter
     */
    public static int executeRightShiftAssign(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
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
     * @param bytecode The bytecode array
     * @param pc Current program counter
     * @param registers Register file
     * @return Updated program counter
     */
    public static int executeLogicalAndAssign(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        RuntimeScalar s1 = ((RuntimeBase) registers[rd]).scalar();
        if (!s1.getBoolean()) {
            // Left side is false, result is left side (no assignment needed)
            return pc;
        }
        // Left side is true, assign right side
        RuntimeScalar s2 = ((RuntimeBase) registers[rs]).scalar();
        ((RuntimeScalar) registers[rd]).set(s2);
        return pc;
    }

    /**
     * Execute logical OR assign operation.
     * Format: LOGICAL_OR_ASSIGN rd rs
     *
     * @param bytecode The bytecode array
     * @param pc Current program counter
     * @param registers Register file
     * @return Updated program counter
     */
    public static int executeLogicalOrAssign(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        RuntimeScalar s1 = ((RuntimeBase) registers[rd]).scalar();
        if (s1.getBoolean()) {
            // Left side is true, result is left side (no assignment needed)
            return pc;
        }
        // Left side is false, assign right side
        RuntimeScalar s2 = ((RuntimeBase) registers[rs]).scalar();
        ((RuntimeScalar) registers[rd]).set(s2);
        return pc;
    }

    /**
     * Execute string concatenation assign operation.
     * Format: STRING_CONCAT_ASSIGN rd rs
     *
     * @param bytecode The bytecode array
     * @param pc Current program counter
     * @param registers Register file
     * @return Updated program counter
     */
    public static int executeStringConcatAssign(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        RuntimeScalar result = StringOperators.stringConcat(
            (RuntimeScalar) registers[rd],
            (RuntimeScalar) registers[rs]
        );
        ((RuntimeScalar) registers[rd]).set(result);
        return pc;
    }

    /**
     * Execute bitwise AND assign operation.
     * Format: BITWISE_AND_ASSIGN rd rs
     *
     * @param bytecode The bytecode array
     * @param pc Current program counter
     * @param registers Register file
     * @return Updated program counter
     */
    public static int executeBitwiseAndAssign(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
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
     * @param bytecode The bytecode array
     * @param pc Current program counter
     * @param registers Register file
     * @return Updated program counter
     */
    public static int executeBitwiseOrAssign(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        RuntimeScalar result = BitwiseOperators.bitwiseOrBinary(
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
     * @param bytecode The bytecode array
     * @param pc Current program counter
     * @param registers Register file
     * @return Updated program counter
     */
    public static int executeBitwiseXorAssign(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        RuntimeScalar result = BitwiseOperators.bitwiseXorBinary(
            (RuntimeScalar) registers[rd],
            (RuntimeScalar) registers[rs]
        );
        ((RuntimeScalar) registers[rd]).set(result);
        return pc;
    }

    /**
     * Execute string bitwise AND assign operation.
     * Format: STRING_BITWISE_AND_ASSIGN rd rs
     *
     * @param bytecode The bytecode array
     * @param pc Current program counter
     * @param registers Register file
     * @return Updated program counter
     */
    public static int executeStringBitwiseAndAssign(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        RuntimeScalar result = BitwiseOperators.bitwiseAndDot(
            (RuntimeScalar) registers[rd],
            (RuntimeScalar) registers[rs]
        );
        ((RuntimeScalar) registers[rd]).set(result);
        return pc;
    }

    /**
     * Execute string bitwise OR assign operation.
     * Format: STRING_BITWISE_OR_ASSIGN rd rs
     *
     * @param bytecode The bytecode array
     * @param pc Current program counter
     * @param registers Register file
     * @return Updated program counter
     */
    public static int executeStringBitwiseOrAssign(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        RuntimeScalar result = BitwiseOperators.bitwiseOrDot(
            (RuntimeScalar) registers[rd],
            (RuntimeScalar) registers[rs]
        );
        ((RuntimeScalar) registers[rd]).set(result);
        return pc;
    }

    /**
     * Execute string bitwise XOR assign operation.
     * Format: STRING_BITWISE_XOR_ASSIGN rd rs
     *
     * @param bytecode The bytecode array
     * @param pc Current program counter
     * @param registers Register file
     * @return Updated program counter
     */
    public static int executeStringBitwiseXorAssign(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
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
    public static int executeBitwiseAndBinary(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs1 = bytecode[pc++];
        int rs2 = bytecode[pc++];
        registers[rd] = BitwiseOperators.bitwiseAndBinary(
            (RuntimeScalar) registers[rs1],
            (RuntimeScalar) registers[rs2]
        );
        return pc;
    }

    /**
     * Execute bitwise OR binary operation.
     * Format: BITWISE_OR_BINARY rd rs1 rs2
     */
    public static int executeBitwiseOrBinary(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs1 = bytecode[pc++];
        int rs2 = bytecode[pc++];
        registers[rd] = BitwiseOperators.bitwiseOrBinary(
            (RuntimeScalar) registers[rs1],
            (RuntimeScalar) registers[rs2]
        );
        return pc;
    }

    /**
     * Execute bitwise XOR binary operation.
     * Format: BITWISE_XOR_BINARY rd rs1 rs2
     */
    public static int executeBitwiseXorBinary(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs1 = bytecode[pc++];
        int rs2 = bytecode[pc++];
        registers[rd] = BitwiseOperators.bitwiseXorBinary(
            (RuntimeScalar) registers[rs1],
            (RuntimeScalar) registers[rs2]
        );
        return pc;
    }

    /**
     * Execute string bitwise AND operation.
     * Format: STRING_BITWISE_AND rd rs1 rs2
     */
    public static int executeStringBitwiseAnd(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs1 = bytecode[pc++];
        int rs2 = bytecode[pc++];
        registers[rd] = BitwiseOperators.bitwiseAndDot(
            (RuntimeScalar) registers[rs1],
            (RuntimeScalar) registers[rs2]
        );
        return pc;
    }

    /**
     * Execute string bitwise OR operation.
     * Format: STRING_BITWISE_OR rd rs1 rs2
     */
    public static int executeStringBitwiseOr(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs1 = bytecode[pc++];
        int rs2 = bytecode[pc++];
        registers[rd] = BitwiseOperators.bitwiseOrDot(
            (RuntimeScalar) registers[rs1],
            (RuntimeScalar) registers[rs2]
        );
        return pc;
    }

    /**
     * Execute string bitwise XOR operation.
     * Format: STRING_BITWISE_XOR rd rs1 rs2
     */
    public static int executeStringBitwiseXor(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs1 = bytecode[pc++];
        int rs2 = bytecode[pc++];
        registers[rd] = BitwiseOperators.bitwiseXorDot(
            (RuntimeScalar) registers[rs1],
            (RuntimeScalar) registers[rs2]
        );
        return pc;
    }

    /**
     * Execute bitwise NOT binary operation.
     * Format: BITWISE_NOT_BINARY rd rs
     */
    public static int executeBitwiseNotBinary(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        registers[rd] = BitwiseOperators.bitwiseNotBinary((RuntimeScalar) registers[rs]);
        return pc;
    }

    /**
     * Execute bitwise NOT string operation.
     * Format: BITWISE_NOT_STRING rd rs
     */
    public static int executeBitwiseNotString(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        registers[rd] = BitwiseOperators.bitwiseNotDot((RuntimeScalar) registers[rs]);
        return pc;
    }

    /**
     * Execute stat operation.
     * Format: STAT rd rs ctx
     */
    public static int executeStat(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        int ctx = bytecode[pc++];
        registers[rd] = Stat.stat((RuntimeScalar) registers[rs], ctx);
        return pc;
    }

    /**
     * Execute lstat operation.
     * Format: LSTAT rd rs ctx
     */
    public static int executeLstat(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        int ctx = bytecode[pc++];
        registers[rd] = Stat.lstat((RuntimeScalar) registers[rs], ctx);
        return pc;
    }

    /**
     * Execute print operation.
     * Format: PRINT contentReg filehandleReg
     */
    public static int executePrint(short[] bytecode, int pc, RuntimeBase[] registers) {
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
    public static int executeSay(short[] bytecode, int pc, RuntimeBase[] registers) {
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
    public static int executeChomp(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        registers[rd] = registers[rs].chomp();
        return pc;
    }

    /**
     * Execute wantarray operation.
     * Format: WANTARRAY rd wantarrayReg
     */
    public static int executeWantarray(short[] bytecode, int pc, RuntimeBase[] registers) {
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
    public static int executeRequire(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        registers[rd] = ModuleOperators.require((RuntimeScalar) registers[rs]);
        return pc;
    }

    /**
     * Execute pos operation.
     * Format: POS rd rs
     */
    public static int executePos(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        registers[rd] = ((RuntimeScalar) registers[rs]).pos();
        return pc;
    }

    /**
     * Execute index operation.
     * Format: INDEX rd strReg substrReg posReg
     */
    public static int executeIndex(short[] bytecode, int pc, RuntimeBase[] registers) {
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
    public static int executeRindex(short[] bytecode, int pc, RuntimeBase[] registers) {
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
    public static int executePreAutoIncrement(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        ((RuntimeScalar) registers[rd]).preAutoIncrement();
        return pc;
    }

    /**
     * Execute post-increment operation.
     * Format: POST_AUTOINCREMENT rd rs
     */
    public static int executePostAutoIncrement(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        registers[rd] = ((RuntimeScalar) registers[rs]).postAutoIncrement();
        return pc;
    }

    /**
     * Execute pre-decrement operation.
     * Format: PRE_AUTODECREMENT rd
     */
    public static int executePreAutoDecrement(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        ((RuntimeScalar) registers[rd]).preAutoDecrement();
        return pc;
    }

    /**
     * Execute post-decrement operation.
     * Format: POST_AUTODECREMENT rd rs
     */
    public static int executePostAutoDecrement(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        registers[rd] = ((RuntimeScalar) registers[rs]).postAutoDecrement();
        return pc;
    }

    /**
     * Execute open operation.
     * Format: OPEN rd ctx argsReg
     */
    public static int executeOpen(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int ctx = bytecode[pc++];
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
    public static int executeReadline(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int fhReg = bytecode[pc++];
        int ctx = bytecode[pc++];
        registers[rd] = Readline.readline((RuntimeScalar) registers[fhReg], ctx);
        return pc;
    }

    /**
     * Execute match regex operation.
     * Format: MATCH_REGEX rd stringReg regexReg ctx
     */
    public static int executeMatchRegex(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int stringReg = bytecode[pc++];
        int regexReg = bytecode[pc++];
        int ctx = bytecode[pc++];
        registers[rd] = RuntimeRegex.matchRegex(
            (RuntimeScalar) registers[regexReg],  // quotedRegex first
            (RuntimeScalar) registers[stringReg], // string second
            ctx
        );
        return pc;
    }

    /**
     * Execute negated match regex operation.
     * Format: MATCH_REGEX_NOT rd stringReg regexReg ctx
     */
    public static int executeMatchRegexNot(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int stringReg = bytecode[pc++];
        int regexReg = bytecode[pc++];
        int ctx = bytecode[pc++];
        RuntimeBase matchResult = RuntimeRegex.matchRegex(
            (RuntimeScalar) registers[regexReg],  // quotedRegex first
            (RuntimeScalar) registers[stringReg], // string second
            ctx
        );
        // Negate the boolean result
        registers[rd] = new RuntimeScalar(matchResult.scalar().getBoolean() ? 0 : 1);
        return pc;
    }

    /**
     * Execute create closure operation.
     * Format: CREATE_CLOSURE rd template_idx num_captures reg1 reg2 ...
     */
    public static int executeCreateClosure(short[] bytecode, int pc, RuntimeBase[] registers, InterpretedCode code) {
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
        return pc;
    }

    /**
     * Execute iterator create operation.
     * Format: ITERATOR_CREATE rd rs
     */
    public static int executeIteratorCreate(short[] bytecode, int pc, RuntimeBase[] registers) {
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
    public static int executeIteratorHasNext(short[] bytecode, int pc, RuntimeBase[] registers) {
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
    public static int executeIteratorNext(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int iterReg = bytecode[pc++];

        RuntimeScalar iterScalar = (RuntimeScalar) registers[iterReg];
        @SuppressWarnings("unchecked")
        java.util.Iterator<RuntimeScalar> iterator =
            (java.util.Iterator<RuntimeScalar>) iterScalar.value;

        RuntimeScalar next = iterator.next();
        registers[rd] = next;
        return pc;
    }

    /**
     * Execute subtract assign operation.
     * Format: SUBTRACT_ASSIGN rd rs
     */
    public static int executeSubtractAssign(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];

        RuntimeBase val1 = registers[rd];
        RuntimeBase val2 = registers[rs];
        RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
        RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();

        registers[rd] = MathOperators.subtractAssign(s1, s2);
        return pc;
    }

    /**
     * Execute multiply assign operation.
     * Format: MULTIPLY_ASSIGN rd rs
     */
    public static int executeMultiplyAssign(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];

        RuntimeBase val1 = registers[rd];
        RuntimeBase val2 = registers[rs];
        RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
        RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();

        registers[rd] = MathOperators.multiplyAssign(s1, s2);
        return pc;
    }

    /**
     * Execute divide assign operation.
     * Format: DIVIDE_ASSIGN rd rs
     */
    public static int executeDivideAssign(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];

        RuntimeBase val1 = registers[rd];
        RuntimeBase val2 = registers[rs];
        RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
        RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();

        registers[rd] = MathOperators.divideAssign(s1, s2);
        return pc;
    }

    /**
     * Execute modulus assign operation.
     * Format: MODULUS_ASSIGN rd rs
     */
    public static int executeModulusAssign(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];

        RuntimeBase val1 = registers[rd];
        RuntimeBase val2 = registers[rs];
        RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
        RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();

        registers[rd] = MathOperators.modulusAssign(s1, s2);
        return pc;
    }
}
