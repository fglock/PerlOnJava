package org.perlonjava.backend.bytecode;

import org.perlonjava.runtime.operators.*;
import org.perlonjava.runtime.runtimetypes.*;

/**
 * Inline opcode handlers for arithmetic, shift, collection, and list operations.
 * <p>
 * Extracted from BytecodeInterpreter.execute() to reduce method size
 * and keep it under the 8KB JIT compilation limit.
 * <p>
 * Handles: arithmetic ops, shift ops, integer assign ops, array/hash operations,
 * and list operations (CREATE_LIST, JOIN, SELECT, RANGE, MAP, GREP, SORT, etc.)
 */
public class InlineOpcodeHandler {

    // =========================================================================
    // Helper methods (duplicated from BytecodeInterpreter)
    // =========================================================================

    /**
     * Check if a value is an immutable proxy (RuntimeScalarReadOnly or ScalarSpecialVariable).
     * These cannot be mutated in place.
     */
    static boolean isImmutableProxy(RuntimeBase val) {
        return val instanceof RuntimeScalarReadOnly || val instanceof ScalarSpecialVariable;
    }

    /**
     * Create a mutable copy of a value if it is an immutable proxy.
     * For RuntimeScalarReadOnly: copies type and value into a fresh RuntimeScalar.
     * For ScalarSpecialVariable: resolves via getValueAsScalar(), then copies.
     * For anything else: casts directly to RuntimeScalar.
     */
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

    // =========================================================================
    // ARITHMETIC OPERATORS
    // =========================================================================

    /**
     * Addition: rd = rs1 + rs2
     * Format: ADD_SCALAR rd rs1 rs2
     */
    public static int executeAddScalar(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs1 = bytecode[pc++];
        int rs2 = bytecode[pc++];

        RuntimeBase val1 = registers[rs1];
        RuntimeBase val2 = registers[rs2];
        RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
        RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();

        registers[rd] = MathOperators.add(s1, s2);
        return pc;
    }

    /**
     * Subtraction: rd = rs1 - rs2
     * Format: SUB_SCALAR rd rs1 rs2
     */
    public static int executeSubScalar(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs1 = bytecode[pc++];
        int rs2 = bytecode[pc++];

        RuntimeBase val1 = registers[rs1];
        RuntimeBase val2 = registers[rs2];
        RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
        RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();

        registers[rd] = MathOperators.subtract(s1, s2);
        return pc;
    }

    /**
     * Multiplication: rd = rs1 * rs2
     * Format: MUL_SCALAR rd rs1 rs2
     */
    public static int executeMulScalar(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs1 = bytecode[pc++];
        int rs2 = bytecode[pc++];

        RuntimeBase val1 = registers[rs1];
        RuntimeBase val2 = registers[rs2];
        RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
        RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();

        registers[rd] = MathOperators.multiply(s1, s2);
        return pc;
    }

    /**
     * Division: rd = rs1 / rs2
     * Format: DIV_SCALAR rd rs1 rs2
     */
    public static int executeDivScalar(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs1 = bytecode[pc++];
        int rs2 = bytecode[pc++];

        RuntimeBase val1 = registers[rs1];
        RuntimeBase val2 = registers[rs2];
        RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
        RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();

        registers[rd] = MathOperators.divide(s1, s2);
        return pc;
    }

    /**
     * Modulus: rd = rs1 % rs2
     * Format: MOD_SCALAR rd rs1 rs2
     */
    public static int executeModScalar(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs1 = bytecode[pc++];
        int rs2 = bytecode[pc++];

        RuntimeBase val1 = registers[rs1];
        RuntimeBase val2 = registers[rs2];
        RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
        RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();

        registers[rd] = MathOperators.modulus(s1, s2);
        return pc;
    }

    /**
     * Exponentiation: rd = rs1 ** rs2
     * Format: POW_SCALAR rd rs1 rs2
     */
    public static int executePowScalar(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs1 = bytecode[pc++];
        int rs2 = bytecode[pc++];

        RuntimeBase val1 = registers[rs1];
        RuntimeBase val2 = registers[rs2];
        RuntimeScalar s1 = (val1 instanceof RuntimeScalar) ? (RuntimeScalar) val1 : val1.scalar();
        RuntimeScalar s2 = (val2 instanceof RuntimeScalar) ? (RuntimeScalar) val2 : val2.scalar();

        registers[rd] = MathOperators.pow(s1, s2);
        return pc;
    }

    /**
     * Negation: rd = -rs
     * Format: NEG_SCALAR rd rs
     */
    public static int executeNegScalar(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        registers[rd] = MathOperators.unaryMinus((RuntimeScalar) registers[rs]);
        return pc;
    }

    /**
     * Addition with immediate: rd = rs + immediate
     * Format: ADD_SCALAR_INT rd rs immediate
     */
    public static int executeAddScalarInt(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        int immediate = bytecode[pc];
        pc += 1;
        registers[rd] = MathOperators.add(
                (RuntimeScalar) registers[rs],
                immediate
        );
        return pc;
    }

    /**
     * String concatenation: rd = rs1 . rs2
     * Format: CONCAT rd rs1 rs2
     */
    public static int executeConcat(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs1 = bytecode[pc++];
        int rs2 = bytecode[pc++];
        RuntimeBase concatLeft = registers[rs1];
        RuntimeBase concatRight = registers[rs2];
        registers[rd] = StringOperators.stringConcat(
                concatLeft instanceof RuntimeScalar ? (RuntimeScalar) concatLeft : concatLeft.scalar(),
                concatRight instanceof RuntimeScalar ? (RuntimeScalar) concatRight : concatRight.scalar()
        );
        return pc;
    }

    /**
     * String/list repetition: rd = rs1 x rs2
     * Format: REPEAT rd rs1 rs2
     */
    public static int executeRepeat(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs1 = bytecode[pc++];
        int rs2 = bytecode[pc++];
        RuntimeBase countVal = registers[rs2];
        RuntimeScalar count = (countVal instanceof RuntimeScalar)
                ? (RuntimeScalar) countVal
                : countVal.scalar();
        int repeatCtx = (registers[rs1] instanceof RuntimeScalar)
                ? RuntimeContextType.SCALAR : RuntimeContextType.LIST;
        registers[rd] = Operator.repeat(registers[rs1], count, repeatCtx);
        return pc;
    }

    /**
     * String length: rd = length(rs)
     * Format: LENGTH rd rs
     */
    public static int executeLength(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        registers[rd] = StringOperators.length((RuntimeScalar) registers[rs]);
        return pc;
    }

    // =========================================================================
    // SHIFT OPERATIONS
    // =========================================================================

    /**
     * Left shift: rd = rs1 << rs2
     * Format: LEFT_SHIFT rd rs1 rs2
     */
    public static int executeLeftShift(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs1 = bytecode[pc++];
        int rs2 = bytecode[pc++];
        RuntimeScalar s1 = (RuntimeScalar) registers[rs1];
        RuntimeScalar s2 = (RuntimeScalar) registers[rs2];
        registers[rd] = BitwiseOperators.shiftLeft(s1, s2);
        return pc;
    }

    /**
     * Right shift: rd = rs1 >> rs2
     * Format: RIGHT_SHIFT rd rs1 rs2
     */
    public static int executeRightShift(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs1 = bytecode[pc++];
        int rs2 = bytecode[pc++];
        RuntimeScalar s1 = (RuntimeScalar) registers[rs1];
        RuntimeScalar s2 = (RuntimeScalar) registers[rs2];
        registers[rd] = BitwiseOperators.shiftRight(s1, s2);
        return pc;
    }

    /**
     * Integer left shift: rd = rs1 << rs2 (integer semantics)
     * Format: INTEGER_LEFT_SHIFT rd rs1 rs2
     */
    public static int executeIntegerLeftShift(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs1 = bytecode[pc++];
        int rs2 = bytecode[pc++];
        RuntimeScalar s1 = (registers[rs1] instanceof RuntimeScalar) ? (RuntimeScalar) registers[rs1] : registers[rs1].scalar();
        RuntimeScalar s2 = (registers[rs2] instanceof RuntimeScalar) ? (RuntimeScalar) registers[rs2] : registers[rs2].scalar();
        registers[rd] = BitwiseOperators.integerShiftLeft(s1, s2);
        return pc;
    }

    /**
     * Integer right shift: rd = rs1 >> rs2 (integer semantics)
     * Format: INTEGER_RIGHT_SHIFT rd rs1 rs2
     */
    public static int executeIntegerRightShift(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs1 = bytecode[pc++];
        int rs2 = bytecode[pc++];
        RuntimeScalar s1 = (registers[rs1] instanceof RuntimeScalar) ? (RuntimeScalar) registers[rs1] : registers[rs1].scalar();
        RuntimeScalar s2 = (registers[rs2] instanceof RuntimeScalar) ? (RuntimeScalar) registers[rs2] : registers[rs2].scalar();
        registers[rd] = BitwiseOperators.integerShiftRight(s1, s2);
        return pc;
    }

    /**
     * Integer division: rd = rs1 / rs2 (integer semantics)
     * Format: INTEGER_DIV rd rs1 rs2
     */
    public static int executeIntegerDiv(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs1 = bytecode[pc++];
        int rs2 = bytecode[pc++];
        RuntimeScalar s1 = (registers[rs1] instanceof RuntimeScalar) ? (RuntimeScalar) registers[rs1] : registers[rs1].scalar();
        RuntimeScalar s2 = (registers[rs2] instanceof RuntimeScalar) ? (RuntimeScalar) registers[rs2] : registers[rs2].scalar();
        registers[rd] = MathOperators.integerDivide(s1, s2);
        return pc;
    }

    /**
     * Integer modulus: rd = rs1 % rs2 (integer semantics)
     * Format: INTEGER_MOD rd rs1 rs2
     */
    public static int executeIntegerMod(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs1 = bytecode[pc++];
        int rs2 = bytecode[pc++];
        RuntimeScalar s1 = (registers[rs1] instanceof RuntimeScalar) ? (RuntimeScalar) registers[rs1] : registers[rs1].scalar();
        RuntimeScalar s2 = (registers[rs2] instanceof RuntimeScalar) ? (RuntimeScalar) registers[rs2] : registers[rs2].scalar();
        registers[rd] = MathOperators.integerModulus(s1, s2);
        return pc;
    }

    // =========================================================================
    // INTEGER ASSIGN OPERATIONS
    // =========================================================================

    /**
     * Integer left shift assign: rd <<= rs (integer semantics, in-place)
     * Format: INTEGER_LEFT_SHIFT_ASSIGN rd rs
     */
    public static int executeIntegerLeftShiftAssign(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        RuntimeScalar s1 = (RuntimeScalar) registers[rd];
        s1.set(BitwiseOperators.integerShiftLeft(s1, (RuntimeScalar) registers[rs]));
        return pc;
    }

    /**
     * Integer right shift assign: rd >>= rs (integer semantics, in-place)
     * Format: INTEGER_RIGHT_SHIFT_ASSIGN rd rs
     */
    public static int executeIntegerRightShiftAssign(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        RuntimeScalar s1 = (RuntimeScalar) registers[rd];
        s1.set(BitwiseOperators.integerShiftRight(s1, (RuntimeScalar) registers[rs]));
        return pc;
    }

    /**
     * Integer division assign: rd /= rs (integer semantics, in-place)
     * Format: INTEGER_DIV_ASSIGN rd rs
     */
    public static int executeIntegerDivAssign(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        RuntimeScalar s1 = (RuntimeScalar) registers[rd];
        RuntimeScalar s2 = (registers[rs] instanceof RuntimeScalar) ? (RuntimeScalar) registers[rs] : registers[rs].scalar();
        registers[rd] = MathOperators.integerDivideAssignWarn(s1, s2);
        return pc;
    }

    /**
     * Integer modulus assign: rd %= rs (integer semantics, in-place)
     * Format: INTEGER_MOD_ASSIGN rd rs
     */
    public static int executeIntegerModAssign(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        RuntimeScalar s1 = (RuntimeScalar) registers[rd];
        s1.set(MathOperators.integerModulus(s1, (RuntimeScalar) registers[rs]));
        return pc;
    }

    // =========================================================================
    // ARRAY OPERATIONS
    // =========================================================================

    /**
     * Array element access: rd = array[index]
     * Format: ARRAY_GET rd arrayReg indexReg
     */
    public static int executeArrayGet(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int arrayReg = bytecode[pc++];
        int indexReg = bytecode[pc++];

        RuntimeBase arrayBase = registers[arrayReg];
        RuntimeScalar idx = (RuntimeScalar) registers[indexReg];

        if (arrayBase instanceof RuntimeArray arr) {
            registers[rd] = arr.get(idx.getInt());
        } else if (arrayBase instanceof RuntimeList list) {
            int index = idx.getInt();
            if (index < 0) index = list.elements.size() + index;
            registers[rd] = (index >= 0 && index < list.elements.size())
                    ? list.elements.get(index)
                    : new RuntimeScalar();
        } else {
            throw new RuntimeException("ARRAY_GET: register " + arrayReg + " contains " +
                    (arrayBase == null ? "null" : arrayBase.getClass().getName()) +
                    " instead of RuntimeArray or RuntimeList");
        }
        return pc;
    }

    /**
     * Array element store: array[index] = value, returns the lvalue (element)
     * Format: ARRAY_SET rd arrayReg indexReg valueReg
     */
    public static int executeArraySet(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int arrayReg = bytecode[pc++];
        int indexReg = bytecode[pc++];
        int valueReg = bytecode[pc++];
        RuntimeArray arr = (RuntimeArray) registers[arrayReg];
        RuntimeScalar idx = (RuntimeScalar) registers[indexReg];
        RuntimeBase valueBase = registers[valueReg];
        RuntimeScalar val = (valueBase instanceof RuntimeScalar)
                ? (RuntimeScalar) valueBase : valueBase.scalar();
        RuntimeScalar element = arr.get(idx.getInt());
        element.set(val);
        registers[rd] = element;
        return pc;
    }

    /**
     * Array push: push(@array, value)
     * Format: ARRAY_PUSH arrayReg valueReg
     */
    public static int executeArrayPush(int[] bytecode, int pc, RuntimeBase[] registers) {
        int arrayReg = bytecode[pc++];
        int valueReg = bytecode[pc++];
        RuntimeArray arr = getArrayFromRegister(registers, arrayReg);
        RuntimeBase val = registers[valueReg];
        arr.push(val);
        return pc;
    }

    /**
     * Helper to get RuntimeArray from a register, handling RuntimeList conversion.
     */
    private static RuntimeArray getArrayFromRegister(RuntimeBase[] registers, int arrayReg) {
        RuntimeBase arrayBase = registers[arrayReg];
        if (arrayBase instanceof RuntimeArray) {
            return (RuntimeArray) arrayBase;
        } else if (arrayBase instanceof RuntimeList) {
            // Convert RuntimeList to RuntimeArray (defensive handling)
            RuntimeArray arr = new RuntimeArray();
            arrayBase.addToArray(arr);
            registers[arrayReg] = arr;
            return arr;
        } else {
            // Fallback: try to get as array via dereference
            RuntimeArray arr = arrayBase.scalar().arrayDeref();
            registers[arrayReg] = arr;
            return arr;
        }
    }

    /**
     * Array pop: rd = pop(@array)
     * Format: ARRAY_POP rd arrayReg
     */
    public static int executeArrayPop(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int arrayReg = bytecode[pc++];
        RuntimeArray arr = getArrayFromRegister(registers, arrayReg);
        registers[rd] = RuntimeArray.pop(arr);
        return pc;
    }

    /**
     * Array shift: rd = shift(@array)
     * Format: ARRAY_SHIFT rd arrayReg
     */
    public static int executeArrayShift(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int arrayReg = bytecode[pc++];
        RuntimeArray arr = getArrayFromRegister(registers, arrayReg);
        registers[rd] = RuntimeArray.shift(arr);
        return pc;
    }

    /**
     * Array unshift: unshift(@array, value)
     * Format: ARRAY_UNSHIFT arrayReg valueReg
     */
    public static int executeArrayUnshift(int[] bytecode, int pc, RuntimeBase[] registers) {
        int arrayReg = bytecode[pc++];
        int valueReg = bytecode[pc++];
        RuntimeArray arr = getArrayFromRegister(registers, arrayReg);
        RuntimeBase val = registers[valueReg];
        RuntimeArray.unshift(arr, val);
        return pc;
    }

    /**
     * Array size: rd = scalar(@array) or scalar(value)
     * Special case for RuntimeList: return size, not last element.
     * Format: ARRAY_SIZE rd operandReg
     */
    public static int executeArraySize(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int operandReg = bytecode[pc++];
        RuntimeBase operand = registers[operandReg];
        if (operand instanceof RuntimeList) {
            registers[rd] = new RuntimeScalar(((RuntimeList) operand).size());
        } else {
            registers[rd] = operand.scalar();
        }
        return pc;
    }

    /**
     * Set array last index: $#array = value
     * Format: SET_ARRAY_LAST_INDEX arrayReg valueReg
     */
    public static int executeSetArrayLastIndex(int[] bytecode, int pc, RuntimeBase[] registers) {
        int arrayReg = bytecode[pc++];
        int valueReg = bytecode[pc++];
        RuntimeArray.indexLastElem((RuntimeArray) registers[arrayReg])
                .set(((RuntimeScalar) registers[valueReg]));
        return pc;
    }

    /**
     * Create array reference from list: rd = new RuntimeArray(rs_list).createReference()
     * Array literals always return references in Perl.
     * Format: CREATE_ARRAY rd listReg
     */
    public static int executeCreateArray(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int listReg = bytecode[pc++];

        RuntimeBase source = registers[listReg];
        RuntimeArray array;
        if (source instanceof RuntimeArray) {
            array = (RuntimeArray) source;
        } else {
            RuntimeList list = source.getList();
            array = new RuntimeArray(list);
        }

        registers[rd] = array.createReference();
        return pc;
    }

    // =========================================================================
    // HASH OPERATIONS
    // =========================================================================

    /**
     * Hash element access: rd = hash{key}
     * Format: HASH_GET rd hashReg keyReg
     */
    public static int executeHashGet(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int hashReg = bytecode[pc++];
        int keyReg = bytecode[pc++];
        RuntimeHash hash = (RuntimeHash) registers[hashReg];
        RuntimeScalar key = (RuntimeScalar) registers[keyReg];
        registers[rd] = hash.get(key);
        return pc;
    }

    /**
     * Hash element store: hash{key} = value, returns the lvalue (element)
     * Creates a fresh copy to prevent aliasing bugs.
     * Uses addToScalar to resolve special variables ($1, $2, etc.)
     * Format: HASH_SET rd hashReg keyReg valueReg
     */
    public static int executeHashSet(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int hashReg = bytecode[pc++];
        int keyReg = bytecode[pc++];
        int valueReg = bytecode[pc++];
        RuntimeHash hash = (RuntimeHash) registers[hashReg];
        RuntimeScalar key = (RuntimeScalar) registers[keyReg];
        RuntimeBase valBase = registers[valueReg];
        RuntimeScalar val = (valBase instanceof RuntimeScalar) ? (RuntimeScalar) valBase : valBase.scalar();
        RuntimeScalar copy = new RuntimeScalar();
        val.addToScalar(copy);
        hash.put(key.toString(), copy);
        registers[rd] = copy;
        return pc;
    }

    /**
     * Check if hash key exists: rd = exists $hash{key}
     * Format: HASH_EXISTS rd hashReg keyReg
     */
    public static int executeHashExists(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int hashReg = bytecode[pc++];
        int keyReg = bytecode[pc++];
        RuntimeHash hash = (RuntimeHash) registers[hashReg];
        RuntimeScalar key = (RuntimeScalar) registers[keyReg];
        registers[rd] = hash.exists(key);
        return pc;
    }

    /**
     * Delete hash key: rd = delete $hash{key}
     * Format: HASH_DELETE rd hashReg keyReg
     */
    public static int executeHashDelete(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int hashReg = bytecode[pc++];
        int keyReg = bytecode[pc++];
        RuntimeHash hash = (RuntimeHash) registers[hashReg];
        RuntimeScalar key = (RuntimeScalar) registers[keyReg];
        registers[rd] = hash.delete(key);
        return pc;
    }

    /**
     * Check if array index exists: rd = exists $array[index]
     * Format: ARRAY_EXISTS rd arrayReg indexReg
     */
    public static int executeArrayExists(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int arrayReg = bytecode[pc++];
        int indexReg = bytecode[pc++];
        RuntimeArray array = (RuntimeArray) registers[arrayReg];
        RuntimeScalar index = (RuntimeScalar) registers[indexReg];
        registers[rd] = array.exists(index);
        return pc;
    }

    /**
     * Delete array element: rd = delete $array[index]
     * Format: ARRAY_DELETE rd arrayReg indexReg
     */
    public static int executeArrayDelete(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int arrayReg = bytecode[pc++];
        int indexReg = bytecode[pc++];
        RuntimeArray array = (RuntimeArray) registers[arrayReg];
        RuntimeScalar index = (RuntimeScalar) registers[indexReg];
        registers[rd] = array.delete(index);
        return pc;
    }

    /**
     * Delete local hash key: rd = delete local $hash{key}
     * Format: HASH_DELETE_LOCAL rd hashReg keyReg
     */
    public static int executeHashDeleteLocal(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int hashReg = bytecode[pc++];
        int keyReg = bytecode[pc++];
        RuntimeHash hash = (RuntimeHash) registers[hashReg];
        RuntimeScalar key = (RuntimeScalar) registers[keyReg];
        registers[rd] = hash.deleteLocal(key);
        return pc;
    }

    /**
     * Delete local array element: rd = delete local $array[index]
     * Format: ARRAY_DELETE_LOCAL rd arrayReg indexReg
     */
    public static int executeArrayDeleteLocal(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int arrayReg = bytecode[pc++];
        int indexReg = bytecode[pc++];
        RuntimeArray array = (RuntimeArray) registers[arrayReg];
        RuntimeScalar index = (RuntimeScalar) registers[indexReg];
        registers[rd] = array.deleteLocal(index);
        return pc;
    }

    /**
     * Get hash keys: rd = keys %hash
     * Calls .keys() on RuntimeBase for proper error handling on non-hash types.
     * Format: HASH_KEYS rd hashReg
     */
    public static int executeHashKeys(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int hashReg = bytecode[pc++];
        registers[rd] = registers[hashReg].keys();
        return pc;
    }

    /**
     * Get hash values: rd = values %hash
     * Calls .values() on RuntimeBase for proper error handling on non-hash types.
     * Format: HASH_VALUES rd hashReg
     */
    public static int executeHashValues(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int hashReg = bytecode[pc++];
        registers[rd] = registers[hashReg].values();
        return pc;
    }

    // =========================================================================
    // LIST OPERATIONS
    // =========================================================================

    /**
     * Count the total flattened elements of a list/array/hash/scalar.
     * Uses countElements() for recursive flattening (hashes contribute 2*keys elements).
     * Format: LIST_TO_COUNT rd rs
     */
    public static int executeListToCount(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        RuntimeBase val = registers[rs];
        registers[rd] = new RuntimeScalar(val.countElements());
        return pc;
    }

    /**
     * Convert list to scalar context: returns last element (Perl list-in-scalar semantics).
     * Format: LIST_TO_SCALAR rd rs
     */
    public static int executeListToScalar(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        registers[rd] = registers[rs].scalar();
        return pc;
    }

    /**
     * Convert value to RuntimeList, preserving aggregate types (PerlRange, RuntimeArray)
     * so that consumers like Pack.pack() can iterate them via RuntimeList's iterator.
     * Format: SCALAR_TO_LIST rd rs
     */
    public static int executeScalarToList(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        RuntimeBase val = registers[rs];
        if (val instanceof RuntimeList) {
            registers[rd] = val;
        } else if (val instanceof RuntimeScalar) {
            RuntimeList list = new RuntimeList();
            list.elements.add(val);
            registers[rd] = list;
        } else {
            // RuntimeArray, PerlRange, etc. - wrap in list, preserving type
            RuntimeList list = new RuntimeList();
            list.elements.add(val);
            registers[rd] = list;
        }
        return pc;
    }

    /**
     * Create RuntimeList from registers.
     * Format: CREATE_LIST rd count rs1 rs2 ... rsN
     */
    public static int executeCreateList(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int count = bytecode[pc++];

        if (count == 0) {
            // Empty list - fastest path
            registers[rd] = new RuntimeList();
        } else if (count == 1) {
            // Single element - avoid loop overhead
            int rs = bytecode[pc++];
            RuntimeList list = new RuntimeList();
            list.add(registers[rs]);
            registers[rd] = list;
        } else {
            // Multiple elements - preallocate and populate
            RuntimeList list = new RuntimeList();

            for (int i = 0; i < count; i++) {
                int rs = bytecode[pc++];
                list.add(registers[rs]);
            }

            registers[rd] = list;
        }
        return pc;
    }

    /**
     * Convert array/hash to scalar if wantarray indicates scalar context.
     * Format: SCALAR_IF_WANTARRAY rd rs wantarray_reg
     * This mirrors the JVM backend's emitRuntimeContextConversion() exactly.
     */
    public static int executeScalarIfWantarray(int[] bytecode, int pc, RuntimeBase[] registers, int callContext) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        // wantarray_reg is not used - we use callContext directly (same as JVM's ILOAD 2)
        pc++; // Skip wantarray_reg operand

        RuntimeBase val = registers[rs];

        // If scalar context and value is array or hash, call .scalar()
        if (callContext == RuntimeContextType.SCALAR &&
                (val instanceof RuntimeArray || val instanceof RuntimeHash)) {
            registers[rd] = val.scalar();
        } else {
            registers[rd] = val;
        }
        return pc;
    }

    /**
     * String join: rd = join(separator, list)
     * Format: JOIN rd separatorReg listReg
     */
    public static int executeJoin(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int separatorReg = bytecode[pc++];
        int listReg = bytecode[pc++];

        RuntimeBase separatorBase = registers[separatorReg];
        RuntimeScalar separator = (separatorBase instanceof RuntimeScalar)
                ? (RuntimeScalar) separatorBase
                : separatorBase.scalar();

        RuntimeBase list = registers[listReg];

        registers[rd] = StringOperators.joinForInterpolation(separator, list);
        return pc;
    }

    /**
     * String join without overload dispatch: rd = joinNoOverload(separator, list)
     * Format: JOIN_NO_OVERLOAD rd separatorReg listReg
     * Used when 'no overloading' is in effect at compile time.
     */
    public static int executeJoinNoOverload(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int separatorReg = bytecode[pc++];
        int listReg = bytecode[pc++];

        RuntimeBase separatorBase = registers[separatorReg];
        RuntimeScalar separator = (separatorBase instanceof RuntimeScalar)
                ? (RuntimeScalar) separatorBase
                : separatorBase.scalar();

        RuntimeBase list = registers[listReg];

        registers[rd] = StringOperators.joinNoOverload(separator, list);
        return pc;
    }

    /**
     * String concatenation without overload dispatch: rd = stringConcatNoOverload(rs1, rs2)
     * Format: CONCAT_NO_OVERLOAD rd rs1 rs2
     * Used when 'no overloading' is in effect at compile time.
     */
    public static int executeConcatNoOverload(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs1 = bytecode[pc++];
        int rs2 = bytecode[pc++];
        RuntimeBase concatLeft = registers[rs1];
        RuntimeBase concatRight = registers[rs2];
        registers[rd] = StringOperators.stringConcatNoOverload(
                concatLeft instanceof RuntimeScalar ? (RuntimeScalar) concatLeft : concatLeft.scalar(),
                concatRight instanceof RuntimeScalar ? (RuntimeScalar) concatRight : concatRight.scalar()
        );
        return pc;
    }

    /**
     * Select default output filehandle: rd = IOOperator.select(list, SCALAR)
     * Format: SELECT rd listReg
     */
    public static int executeSelect(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int listReg = bytecode[pc++];

        RuntimeBase listBase = registers[listReg];
        RuntimeList list = (listBase instanceof RuntimeList rl)
                ? rl : listBase.getList();
        RuntimeScalar result = IOOperator.select(list, RuntimeContextType.SCALAR);
        registers[rd] = result;
        return pc;
    }

    /**
     * Create range: rd = PerlRange.createRange(rs_start, rs_end)
     * Format: RANGE rd startReg endReg
     */
    public static int executeRange(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int startReg = bytecode[pc++];
        int endReg = bytecode[pc++];

        RuntimeBase startBase = registers[startReg];
        RuntimeBase endBase = registers[endReg];

        RuntimeScalar start = (startBase instanceof RuntimeScalar) ? (RuntimeScalar) startBase :
                (startBase == null) ? new RuntimeScalar() : startBase.scalar();
        RuntimeScalar end = (endBase instanceof RuntimeScalar) ? (RuntimeScalar) endBase :
                (endBase == null) ? new RuntimeScalar() : endBase.scalar();

        PerlRange range = PerlRange.createRange(start, end);
        registers[rd] = range;
        return pc;
    }

    /**
     * Create hash reference from list: rd = RuntimeHash.createHash(rs_list).createReference()
     * Hash literals always return references in Perl.
     * Format: CREATE_HASH rd listReg
     */
    public static int executeCreateHash(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int listReg = bytecode[pc++];

        RuntimeBase list = registers[listReg];
        RuntimeHash hash = RuntimeHash.createHash(list);

        registers[rd] = hash.createReference();
        return pc;
    }

    /**
     * Random number: rd = Random.rand(max)
     * Format: RAND rd maxReg
     */
    public static int executeRand(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int maxReg = bytecode[pc++];

        RuntimeScalar max = (RuntimeScalar) registers[maxReg];
        registers[rd] = Random.rand(max);
        return pc;
    }

    /**
     * Map operator: rd = ListOperators.map(list, closure, ctx)
     * Format: MAP rd listReg closureReg ctx
     */
    public static int executeMap(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int listReg = bytecode[pc++];
        int closureReg = bytecode[pc++];
        int ctx = bytecode[pc++];

        if (ctx == RuntimeContextType.RUNTIME) ctx = ((RuntimeScalar) registers[2]).getInt();

        RuntimeBase listBase = registers[listReg];
        RuntimeList list = listBase.getList();
        RuntimeScalar closure = (RuntimeScalar) registers[closureReg];
        RuntimeList result = ListOperators.map(list, closure, ctx);
        registers[rd] = result;
        return pc;
    }

    /**
     * Grep operator: rd = ListOperators.grep(list, closure, ctx)
     * Format: GREP rd listReg closureReg ctx
     */
    public static int executeGrep(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int listReg = bytecode[pc++];
        int closureReg = bytecode[pc++];
        int ctx = bytecode[pc++];

        if (ctx == RuntimeContextType.RUNTIME) ctx = ((RuntimeScalar) registers[2]).getInt();

        RuntimeBase listBase = registers[listReg];
        RuntimeList list = listBase.getList();
        RuntimeScalar closure = (RuntimeScalar) registers[closureReg];
        RuntimeList result = ListOperators.grep(list, closure, ctx);
        registers[rd] = result;
        return pc;
    }

    /**
     * Sort operator: rd = ListOperators.sort(list, closure, package)
     * Needs InterpretedCode for string pool access.
     * Format: SORT rd listReg closureReg packageIdx(int)
     */
    public static int executeSort(int[] bytecode, int pc, RuntimeBase[] registers, InterpretedCode code) {
        int rd = bytecode[pc++];
        int listReg = bytecode[pc++];
        int closureReg = bytecode[pc++];
        int packageIdx = bytecode[pc];
        pc += 1;

        RuntimeBase listBase = registers[listReg];
        RuntimeList list = listBase.getList();
        RuntimeScalar closure = (RuntimeScalar) registers[closureReg];
        String packageName = code.stringPool[packageIdx];
        RuntimeList result = ListOperators.sort(list, closure, packageName);
        registers[rd] = result;
        return pc;
    }

    /**
     * Create empty array: rd = new RuntimeArray()
     * Format: NEW_ARRAY rd
     */
    public static int executeNewArray(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        registers[rd] = new RuntimeArray();
        return pc;
    }

    /**
     * Create empty hash: rd = new RuntimeHash()
     * Format: NEW_HASH rd
     */
    public static int executeNewHash(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        registers[rd] = new RuntimeHash();
        return pc;
    }

    /**
     * Set array content from list: array_reg.setFromList(list_reg)
     * Format: ARRAY_SET_FROM_LIST arrayReg listReg
     */
    public static int executeArraySetFromList(int[] bytecode, int pc, RuntimeBase[] registers) {
        int arrayReg = bytecode[pc++];
        int listReg = bytecode[pc++];

        RuntimeArray array = (RuntimeArray) registers[arrayReg];
        RuntimeBase listBase = registers[listReg];
        RuntimeList list = listBase.getList();

        array.setFromList(list);
        return pc;
    }

    /**
     * List assignment: rd = lhsList.setFromList(rhsList)
     * Format: SET_FROM_LIST rd lhsReg rhsReg
     */
    public static int executeSetFromList(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int lhsReg = bytecode[pc++];
        int rhsReg = bytecode[pc++];
        RuntimeList lhsList = (RuntimeList) registers[lhsReg];
        RuntimeBase rhsBase = registers[rhsReg];
        RuntimeList rhsList = (rhsBase instanceof RuntimeList rl) ? rl : rhsBase.getList();
        RuntimeArray result = lhsList.setFromList(rhsList);
        registers[rd] = result;
        return pc;
    }

    /**
     * Set hash content from list using setFromList for correct warnings.
     * Format: HASH_SET_FROM_LIST hashReg listReg
     */
    public static int executeHashSetFromList(int[] bytecode, int pc, RuntimeBase[] registers) {
        int hashReg = bytecode[pc++];
        int listReg = bytecode[pc++];

        RuntimeHash hash = (RuntimeHash) registers[hashReg];
        RuntimeBase listBase = registers[listReg];
        RuntimeList list = (listBase instanceof RuntimeList rl) ? rl : listBase.getList();
        hash.setFromList(list);
        return pc;
    }

    // =========================================================================
    // CONTROL FLOW - SPECIAL (RuntimeControlFlowList)
    // =========================================================================

    public static int executeCreateLast(int[] bytecode, int pc, RuntimeBase[] registers, InterpretedCode code) {
        int rd = bytecode[pc++];
        int labelIdx = bytecode[pc++];
        String label = labelIdx == 255 ? null : code.stringPool[labelIdx];
        registers[rd] = new RuntimeControlFlowList(ControlFlowType.LAST, label, code.sourceName, code.sourceLine);
        return pc;
    }

    public static int executeCreateNext(int[] bytecode, int pc, RuntimeBase[] registers, InterpretedCode code) {
        int rd = bytecode[pc++];
        int labelIdx = bytecode[pc++];
        String label = labelIdx == 255 ? null : code.stringPool[labelIdx];
        registers[rd] = new RuntimeControlFlowList(ControlFlowType.NEXT, label, code.sourceName, code.sourceLine);
        return pc;
    }

    public static int executeCreateRedo(int[] bytecode, int pc, RuntimeBase[] registers, InterpretedCode code) {
        int rd = bytecode[pc++];
        int labelIdx = bytecode[pc++];
        String label = labelIdx == 255 ? null : code.stringPool[labelIdx];
        registers[rd] = new RuntimeControlFlowList(ControlFlowType.REDO, label, code.sourceName, code.sourceLine);
        return pc;
    }

    public static int executeCreateGoto(int[] bytecode, int pc, RuntimeBase[] registers, InterpretedCode code) {
        int rd = bytecode[pc++];
        int labelIdx = bytecode[pc++];
        String label = labelIdx == 255 ? null : code.stringPool[labelIdx];
        registers[rd] = new RuntimeControlFlowList(ControlFlowType.GOTO, label, code.sourceName, code.sourceLine);
        return pc;
    }

    public static int executeIsControlFlow(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        registers[rd] = (registers[rs] instanceof RuntimeControlFlowList) ?
                RuntimeScalarCache.scalarTrue : RuntimeScalarCache.scalarFalse;
        return pc;
    }

    // =========================================================================
    // SUPERINSTRUCTIONS
    // =========================================================================

    public static int executeIncReg(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        RuntimeBase incResult = MathOperators.add((RuntimeScalar) registers[rd], 1);
        registers[rd] = isImmutableProxy(incResult) ? ensureMutableScalar(incResult) : incResult;
        return pc;
    }

    public static int executeDecReg(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        RuntimeBase decResult = MathOperators.subtract((RuntimeScalar) registers[rd], 1);
        registers[rd] = isImmutableProxy(decResult) ? ensureMutableScalar(decResult) : decResult;
        return pc;
    }

    public static int executeAddAssign(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        if (isImmutableProxy(registers[rd])) {
            registers[rd] = ensureMutableScalar(registers[rd]);
        }
        // Note: += does NOT warn for uninitialized values in Perl
        MathOperators.addAssign((RuntimeScalar) registers[rd], (RuntimeScalar) registers[rs]);
        return pc;
    }

    public static int executeAddAssignInt(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int immediate = bytecode[pc];
        pc += 1;
        if (isImmutableProxy(registers[rd])) {
            registers[rd] = ensureMutableScalar(registers[rd]);
        }
        RuntimeScalar result = MathOperators.add((RuntimeScalar) registers[rd], immediate);
        ((RuntimeScalar) registers[rd]).set(result);
        return pc;
    }

    public static int executeXorLogical(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs1 = bytecode[pc++];
        int rs2 = bytecode[pc++];
        registers[rd] = Operator.xor((RuntimeScalar) registers[rs1], (RuntimeScalar) registers[rs2]);
        return pc;
    }

    // =========================================================================
    // ERROR HANDLING
    // =========================================================================

    public static int executeDie(int[] bytecode, int pc, RuntimeBase[] registers, InterpretedCode code) {
        int msgReg = bytecode[pc++];
        int locationReg = bytecode[pc++];
        RuntimeBase message = registers[msgReg];
        RuntimeScalar where = (RuntimeScalar) registers[locationReg];
        WarnDie.die(message, where, code.sourceName, code.sourceLine);
        throw new RuntimeException("die() did not throw exception");
    }

    public static int executeWarn(int[] bytecode, int pc, RuntimeBase[] registers, InterpretedCode code) {
        int msgReg = bytecode[pc++];
        int locationReg = bytecode[pc++];
        RuntimeBase message = registers[msgReg];
        RuntimeScalar where = (RuntimeScalar) registers[locationReg];
        WarnDie.warn(message, where, code.sourceName, code.sourceLine);
        return pc;
    }

    // =========================================================================
    // REFERENCE OPERATIONS
    // =========================================================================

    public static int executeCreateRef(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        RuntimeBase value = registers[rs];
        if (value == null) {
            registers[rd] = RuntimeScalarCache.scalarUndef;
        } else if (value instanceof RuntimeList list) {
            // \(LIST) semantics: create individual refs for each element
            registers[rd] = list.createListReference();
        } else {
            registers[rd] = value.createReference();
        }
        return pc;
    }

    public static int executeDeref(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        RuntimeBase value = registers[rs];
        if (value instanceof RuntimeScalar scalar) {
            if (scalar.type == RuntimeScalarType.REFERENCE) {
                registers[rd] = scalar.scalarDeref();
            } else {
                registers[rd] = value;
            }
        } else {
            registers[rd] = value;
        }
        return pc;
    }

    public static int executeGetType(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        registers[rd] = new RuntimeScalar(((RuntimeScalar) registers[rs]).type);
        return pc;
    }

    // =========================================================================
    // SYMBOLIC REFERENCES
    // =========================================================================

    public static int executeStoreSymbolicScalar(int[] bytecode, int pc, RuntimeBase[] registers) {
        int nameReg = bytecode[pc++];
        int valueReg = bytecode[pc++];
        RuntimeScalar nameScalar = (RuntimeScalar) registers[nameReg];
        String normalizedName = NameNormalizer.normalizeVariableName(nameScalar.toString(), "main");
        RuntimeScalar globalVar = GlobalVariable.getGlobalVariable(normalizedName);
        globalVar.set(registers[valueReg]);
        return pc;
    }

    public static int executeLoadSymbolicScalar(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int nameReg = bytecode[pc++];
        RuntimeScalar nameScalar = (RuntimeScalar) registers[nameReg];
        if (nameScalar.type == RuntimeScalarType.REFERENCE) {
            registers[rd] = nameScalar.scalarDeref();
        } else {
            String normalizedName = NameNormalizer.normalizeVariableName(nameScalar.toString(), "main");
            registers[rd] = GlobalVariable.getGlobalVariable(normalizedName);
        }
        return pc;
    }

    // =========================================================================
    // TIE OPERATIONS
    // =========================================================================

    public static int executeTie(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int argsReg = bytecode[pc++];
        int ctx = bytecode[pc++];

        if (ctx == RuntimeContextType.RUNTIME) ctx = ((RuntimeScalar) registers[2]).getInt();
        RuntimeList tieArgs = (RuntimeList) registers[argsReg];
        registers[rd] = TieOperators.tie(ctx, tieArgs.elements.toArray(new RuntimeBase[0]));
        return pc;
    }

    public static int executeUntie(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int argsReg = bytecode[pc++];
        int ctx = bytecode[pc++];

        if (ctx == RuntimeContextType.RUNTIME) ctx = ((RuntimeScalar) registers[2]).getInt();
        RuntimeList untieArgs = (RuntimeList) registers[argsReg];
        registers[rd] = TieOperators.untie(ctx, untieArgs.elements.toArray(new RuntimeBase[0]));
        return pc;
    }

    public static int executeTied(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int argsReg = bytecode[pc++];
        int ctx = bytecode[pc++];

        if (ctx == RuntimeContextType.RUNTIME) ctx = ((RuntimeScalar) registers[2]).getInt();
        RuntimeList tiedArgs = (RuntimeList) registers[argsReg];
        registers[rd] = TieOperators.tied(ctx, tiedArgs.elements.toArray(new RuntimeBase[0]));
        return pc;
    }

    // =========================================================================
    // MISC COLD OPS
    // =========================================================================

    public static int executeStoreGlob(int[] bytecode, int pc, RuntimeBase[] registers) {
        int globReg = bytecode[pc++];
        int valueReg = bytecode[pc++];
        Object val = registers[valueReg];
        RuntimeScalar scalarVal = (val instanceof RuntimeScalar)
                ? (RuntimeScalar) val : ((RuntimeList) val).scalar();
        ((RuntimeGlob) registers[globReg]).set(scalarVal);
        return pc;
    }

    public static int executePushLocalVariable(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rs = bytecode[pc++];
        DynamicVariableManager.pushLocalVariable(registers[rs]);
        return pc;
    }

    public static int executeFlipFlop(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int flipFlopId = bytecode[pc++];
        int rs1 = bytecode[pc++];
        int rs2 = bytecode[pc++];
        registers[rd] = ScalarFlipFlopOperator.evaluate(
                flipFlopId,
                registers[rs1].scalar(),
                registers[rs2].scalar());
        return pc;
    }

    public static int executeLocalGlob(int[] bytecode, int pc, RuntimeBase[] registers, InterpretedCode code) {
        int rd = bytecode[pc++];
        int nameIdx = bytecode[pc++];
        String name = code.stringPool[nameIdx];
        RuntimeGlob glob = GlobalVariable.getGlobalIO(name);
        // pushLocalVariable returns the NEW glob (installed by dynamicSaveState),
        // ensuring \do { local *FH } captures a unique glob per call (Perl 5 parity).
        registers[rd] = DynamicVariableManager.pushLocalVariable(glob);
        return pc;
    }

    public static int executeLocalGlobDynamic(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int nameReg = bytecode[pc++];
        RuntimeScalar nameScalar = registers[nameReg].scalar();
        String pkg = InterpreterState.currentPackage.get().toString();
        String normalizedName = NameNormalizer.normalizeVariableName(nameScalar.toString(), pkg);
        RuntimeGlob glob = GlobalVariable.getGlobalIO(normalizedName);
        registers[rd] = DynamicVariableManager.pushLocalVariable(glob);
        return pc;
    }

    public static int executeGetLocalLevel(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        registers[rd] = new RuntimeScalar(DynamicVariableManager.getLocalLevel());
        return pc;
    }

    public static int executeDoFile(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int fileReg = bytecode[pc++];
        int ctx = bytecode[pc++];

        if (ctx == RuntimeContextType.RUNTIME) ctx = ((RuntimeScalar) registers[2]).getInt();
        RuntimeScalar file = registers[fileReg].scalar();
        registers[rd] = ModuleOperators.doFile(file, ctx);
        return pc;
    }

    public static int executePushPackage(int[] bytecode, int pc, RuntimeBase[] registers, InterpretedCode code) {
        int nameIdx = bytecode[pc++];
        DynamicVariableManager.pushLocalVariable(InterpreterState.currentPackage.get());
        InterpreterState.currentPackage.get().set(code.stringPool[nameIdx]);
        return pc;
    }

    public static int executeGlobOp(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int globId = bytecode[pc++];
        int patternReg = bytecode[pc++];
        int ctx = bytecode[pc++];

        if (ctx == RuntimeContextType.RUNTIME) ctx = ((RuntimeScalar) registers[2]).getInt();
        registers[rd] = ScalarGlobOperator.evaluate(globId, (RuntimeScalar) registers[patternReg], ctx);
        return pc;
    }

    public static int executeUndefineScalar(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        if (isImmutableProxy(registers[rd])) {
            registers[rd] = ensureMutableScalar(registers[rd]);
        }
        registers[rd].undefine();
        return pc;
    }

    /**
     * Create a DeferBlock from a RuntimeScalar code reference and push it onto the DVM stack.
     * Format: PUSH_DEFER code_reg args_reg
     * Creates DeferBlock with captured @_ and pushes: DynamicVariableManager.pushLocalVariable(new DeferBlock(codeRef, args))
     */
    public static int executePushDefer(int[] bytecode, int pc, RuntimeBase[] registers) {
        int codeReg = bytecode[pc++];
        int argsReg = bytecode[pc++];
        RuntimeScalar codeRef = (RuntimeScalar) registers[codeReg];
        RuntimeArray args = (RuntimeArray) registers[argsReg];
        DeferBlock deferBlock = new DeferBlock(codeRef, args);
        DynamicVariableManager.pushLocalVariable(deferBlock);
        return pc;
    }
}
