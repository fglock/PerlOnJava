package org.perlonjava.interpreter;

/**
 * Bytecode opcodes for the PerlOnJava interpreter.
 *
 * Design: Pure register machine with 3-address code format.
 * Dense opcodes (0-255) enable JVM tableswitch optimization.
 *
 * Register architecture is REQUIRED for control flow correctness:
 * Perl's GOTO/last/next/redo would corrupt a stack-based architecture.
 */
public class Opcodes {
    // =================================================================
    // CONTROL FLOW
    // =================================================================

    /** No operation (padding/alignment) */
    public static final byte NOP = 0;

    /** Return from subroutine: return rd
     * May return RuntimeControlFlowList for last/next/redo/goto */
    public static final byte RETURN = 1;

    /** Unconditional jump: pc = offset (absolute bytecode offset) */
    public static final byte GOTO = 2;

    /** Conditional jump: if (!rs) pc = offset */
    public static final byte GOTO_IF_FALSE = 3;

    /** Conditional jump: if (rs) pc = offset */
    public static final byte GOTO_IF_TRUE = 4;

    // =================================================================
    // REGISTER OPERATIONS
    // =================================================================

    /** Register copy: rd = rs */
    public static final byte MOVE = 10;

    /** Load from constant pool: rd = constants[index] */
    public static final byte LOAD_CONST = 11;

    /** Load cached integer: rd = RuntimeScalarCache.getScalarInt(immediate32) */
    public static final byte LOAD_INT = 12;

    /** Load string: rd = new RuntimeScalar(stringPool[index]) */
    public static final byte LOAD_STRING = 13;

    /** Load undef: rd = new RuntimeScalar() */
    public static final byte LOAD_UNDEF = 14;

    // =================================================================
    // VARIABLE ACCESS - GLOBAL
    // =================================================================

    /** Load global scalar: rd = GlobalVariable.getGlobalScalar(stringPool[index]) */
    public static final byte LOAD_GLOBAL_SCALAR = 20;

    /** Store global scalar: GlobalVariable.getGlobalScalar(stringPool[index]).set(rs) */
    public static final byte STORE_GLOBAL_SCALAR = 21;

    /** Load global array: rd = GlobalVariable.getGlobalArray(stringPool[index]) */
    public static final byte LOAD_GLOBAL_ARRAY = 22;

    /** Store global array: GlobalVariable.getGlobalArray(stringPool[index]).elements = rs */
    public static final byte STORE_GLOBAL_ARRAY = 23;

    /** Load global hash: rd = GlobalVariable.getGlobalHash(stringPool[index]) */
    public static final byte LOAD_GLOBAL_HASH = 24;

    /** Store global hash: GlobalVariable.getGlobalHash(stringPool[index]).elements = rs */
    public static final byte STORE_GLOBAL_HASH = 25;

    /** Load global code: rd = GlobalVariable.getGlobalCodeRef(stringPool[index]) */
    public static final byte LOAD_GLOBAL_CODE = 26;

    // =================================================================
    // ARITHMETIC OPERATORS (call org.perlonjava.operators.MathOperators)
    // =================================================================

    /** Addition: rd = MathOperators.add(rs1, rs2) */
    public static final byte ADD_SCALAR = 30;

    /** Subtraction: rd = MathOperators.subtract(rs1, rs2) */
    public static final byte SUB_SCALAR = 31;

    /** Multiplication: rd = MathOperators.multiply(rs1, rs2) */
    public static final byte MUL_SCALAR = 32;

    /** Division: rd = MathOperators.divide(rs1, rs2) */
    public static final byte DIV_SCALAR = 33;

    /** Modulus: rd = MathOperators.modulus(rs1, rs2) */
    public static final byte MOD_SCALAR = 34;

    /** Exponentiation: rd = MathOperators.power(rs1, rs2) */
    public static final byte POW_SCALAR = 35;

    /** Negation: rd = MathOperators.negate(rs) */
    public static final byte NEG_SCALAR = 36;

    // Specialized unboxed operations (rare optimizations)

    /** Addition with immediate: rd = MathOperators.add(rs, immediate32) */
    public static final byte ADD_SCALAR_INT = 40;

    /** Subtraction with immediate: rd = MathOperators.subtract(rs, immediate32) */
    public static final byte SUB_SCALAR_INT = 41;

    /** Multiplication with immediate: rd = MathOperators.multiply(rs, immediate32) */
    public static final byte MUL_SCALAR_INT = 42;

    // =================================================================
    // STRING OPERATORS (call org.perlonjava.operators.StringOperators)
    // =================================================================

    /** String concatenation: rd = StringOperators.concat(rs1, rs2) */
    public static final byte CONCAT = 50;

    /** String repetition: rd = StringOperators.repeat(rs1, rs2) */
    public static final byte REPEAT = 51;

    /** Substring: rd = StringOperators.substr(str_reg, offset_reg, length_reg) */
    public static final byte SUBSTR = 52;

    /** String length: rd = StringOperators.length(rs) */
    public static final byte LENGTH = 53;

    // =================================================================
    // COMPARISON OPERATORS (call org.perlonjava.operators.CompareOperators)
    // =================================================================

    /** Numeric comparison: rd = CompareOperators.compareNum(rs1, rs2) */
    public static final byte COMPARE_NUM = 60;

    /** String comparison: rd = CompareOperators.compareStr(rs1, rs2) */
    public static final byte COMPARE_STR = 61;

    /** Numeric equality: rd = CompareOperators.numericEqual(rs1, rs2) */
    public static final byte EQ_NUM = 62;

    /** Numeric inequality: rd = CompareOperators.numericNotEqual(rs1, rs2) */
    public static final byte NE_NUM = 63;

    /** Less than: rd = CompareOperators.numericLessThan(rs1, rs2) */
    public static final byte LT_NUM = 64;

    /** Greater than: rd = CompareOperators.numericGreaterThan(rs1, rs2) */
    public static final byte GT_NUM = 65;

    /** String equality: rd = CompareOperators.stringEqual(rs1, rs2) */
    public static final byte EQ_STR = 66;

    /** String inequality: rd = CompareOperators.stringNotEqual(rs1, rs2) */
    public static final byte NE_STR = 67;

    // =================================================================
    // LOGICAL OPERATORS
    // =================================================================

    /** Logical NOT: rd = !rs */
    public static final byte NOT = 70;

    /** Logical AND: rd = rs1 && rs2 (short-circuit handled in bytecode compiler) */
    public static final byte AND = 71;

    /** Logical OR: rd = rs1 || rs2 (short-circuit handled in bytecode compiler) */
    public static final byte OR = 72;

    // =================================================================
    // ARRAY OPERATIONS (use RuntimeArray API)
    // =================================================================

    /** Array element access: rd = array_reg.get(index_reg) */
    public static final byte ARRAY_GET = 80;

    /** Array element store: array_reg.set(index_reg, value_reg) */
    public static final byte ARRAY_SET = 81;

    /** Array push: array_reg.push(value_reg) */
    public static final byte ARRAY_PUSH = 82;

    /** Array pop: rd = array_reg.pop() */
    public static final byte ARRAY_POP = 83;

    /** Array shift: rd = array_reg.shift() */
    public static final byte ARRAY_SHIFT = 84;

    /** Array unshift: array_reg.unshift(value_reg) */
    public static final byte ARRAY_UNSHIFT = 85;

    /** Array size: rd = new RuntimeScalar(array_reg.size()) */
    public static final byte ARRAY_SIZE = 86;

    /** Create array: rd = new RuntimeArray() */
    public static final byte CREATE_ARRAY = 87;

    // =================================================================
    // HASH OPERATIONS (use RuntimeHash API)
    // =================================================================

    /** Hash element access: rd = hash_reg.get(key_reg) */
    public static final byte HASH_GET = 90;

    /** Hash element store: hash_reg.put(key_reg, value_reg) */
    public static final byte HASH_SET = 91;

    /** Hash exists: rd = hash_reg.exists(key_reg) */
    public static final byte HASH_EXISTS = 92;

    /** Hash delete: rd = hash_reg.delete(key_reg) */
    public static final byte HASH_DELETE = 93;

    /** Hash keys: rd = hash_reg.keys() */
    public static final byte HASH_KEYS = 94;

    /** Hash values: rd = hash_reg.values() */
    public static final byte HASH_VALUES = 95;

    /** Create hash: rd = new RuntimeHash() */
    public static final byte CREATE_HASH = 96;

    // =================================================================
    // SUBROUTINE CALLS (RuntimeCode.apply)
    // =================================================================

    /** Call subroutine: rd = RuntimeCode.apply(coderef_reg, args_reg, context)
     * May return RuntimeControlFlowList for last/next/redo/goto */
    public static final byte CALL_SUB = 100;

    /** Call method: rd = RuntimeCode.call(obj_reg, method_name, args_reg, context) */
    public static final byte CALL_METHOD = 101;

    /** Call builtin: rd = BuiltinRegistry.call(builtin_id, args_reg, context) */
    public static final byte CALL_BUILTIN = 102;

    // =================================================================
    // CONTEXT OPERATIONS
    // =================================================================

    /** List to scalar: rd = list_reg.scalar() */
    public static final byte LIST_TO_SCALAR = 110;

    /** Scalar to list: rd = new RuntimeList(scalar_reg) */
    public static final byte SCALAR_TO_LIST = 111;

    // =================================================================
    // CONTROL FLOW - SPECIAL (RuntimeControlFlowList)
    // =================================================================

    /** Create LAST control flow: rd = new RuntimeControlFlowList(LAST, label_index) */
    public static final byte CREATE_LAST = 120;

    /** Create NEXT control flow: rd = new RuntimeControlFlowList(NEXT, label_index) */
    public static final byte CREATE_NEXT = 121;

    /** Create REDO control flow: rd = new RuntimeControlFlowList(REDO, label_index) */
    public static final byte CREATE_REDO = 122;

    /** Create GOTO control flow: rd = new RuntimeControlFlowList(GOTO, label_index) */
    public static final byte CREATE_GOTO = 123;

    /** Check if return value is control flow: rd = (rs instanceof RuntimeControlFlowList) */
    public static final byte IS_CONTROL_FLOW = 124;

    /** Get control flow type: rd = ((RuntimeControlFlowList)rs).getControlFlowType().ordinal() */
    public static final byte GET_CONTROL_FLOW_TYPE = 125;

    // =================================================================
    // REFERENCE OPERATIONS
    // =================================================================

    /** Create scalar reference: rd = new RuntimeScalar(rs) */
    public static final byte CREATE_REF = (byte) 130;

    /** Dereference: rd = rs.dereference() */
    public static final byte DEREF = (byte) 131;

    /** Type check: rd = new RuntimeScalar(rs.type.name()) */
    public static final byte GET_TYPE = (byte) 132;

    // =================================================================
    // MISCELLANEOUS
    // =================================================================

    /** Print to STDOUT: print(rs) */
    public static final byte PRINT = (byte) 140;

    /** Say to STDOUT: say(rs) */
    public static final byte SAY = (byte) 141;

    /** Die with message: die(rs) */
    public static final byte DIE = (byte) 142;

    /** Warn with message: warn(rs) */
    public static final byte WARN = (byte) 143;

    private Opcodes() {} // Utility class - no instantiation
}
