package org.perlonjava.interpreter;

/**
 * Bytecode opcodes for the PerlOnJava interpreter.
 *
 * Design: Pure register machine with 3-address code format.
 * DENSE opcodes (0-74, NO GAPS) enable JVM tableswitch optimization.
 *
 * Register architecture is REQUIRED for control flow correctness:
 * Perl's GOTO/last/next/redo would corrupt a stack-based architecture.
 *
 * IMPORTANT: Opcodes are numbered sequentially 0,1,2,3... with NO GAPS
 * to ensure the JVM uses tableswitch (O(1) jump table) instead of
 * lookupswitch (O(log n) binary search). This gives ~10-15% speedup.
 */
public class Opcodes {
    // =================================================================
    // CONTROL FLOW (0-4)
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
    // REGISTER OPERATIONS (5-9)
    // =================================================================

    /** Register copy: rd = rs */
    public static final byte MOVE = 5;

    /** Load from constant pool: rd = constants[index] */
    public static final byte LOAD_CONST = 6;

    /** Load cached integer: rd = RuntimeScalarCache.getScalarInt(immediate32) */
    public static final byte LOAD_INT = 7;

    /** Load string: rd = new RuntimeScalar(stringPool[index]) */
    public static final byte LOAD_STRING = 8;

    /** Load undef: rd = new RuntimeScalar() */
    public static final byte LOAD_UNDEF = 9;

    // =================================================================
    // VARIABLE ACCESS - GLOBAL (10-16)
    // =================================================================

    /** Load global scalar: rd = GlobalVariable.getGlobalScalar(stringPool[index]) */
    public static final byte LOAD_GLOBAL_SCALAR = 10;

    /** Store global scalar: GlobalVariable.getGlobalScalar(stringPool[index]).set(rs) */
    public static final byte STORE_GLOBAL_SCALAR = 11;

    /** Load global array: rd = GlobalVariable.getGlobalArray(stringPool[index]) */
    public static final byte LOAD_GLOBAL_ARRAY = 12;

    /** Store global array: GlobalVariable.getGlobalArray(stringPool[index]).elements = rs */
    public static final byte STORE_GLOBAL_ARRAY = 13;

    /** Load global hash: rd = GlobalVariable.getGlobalHash(stringPool[index]) */
    public static final byte LOAD_GLOBAL_HASH = 14;

    /** Store global hash: GlobalVariable.getGlobalHash(stringPool[index]).elements = rs */
    public static final byte STORE_GLOBAL_HASH = 15;

    /** Load global code: rd = GlobalVariable.getGlobalCodeRef(stringPool[index]) */
    public static final byte LOAD_GLOBAL_CODE = 16;

    // =================================================================
    // ARITHMETIC OPERATORS (17-26) - call org.perlonjava.operators.MathOperators
    // =================================================================

    /** Addition: rd = MathOperators.add(rs1, rs2) */
    public static final byte ADD_SCALAR = 17;

    /** Subtraction: rd = MathOperators.subtract(rs1, rs2) */
    public static final byte SUB_SCALAR = 18;

    /** Multiplication: rd = MathOperators.multiply(rs1, rs2) */
    public static final byte MUL_SCALAR = 19;

    /** Division: rd = MathOperators.divide(rs1, rs2) */
    public static final byte DIV_SCALAR = 20;

    /** Modulus: rd = MathOperators.modulus(rs1, rs2) */
    public static final byte MOD_SCALAR = 21;

    /** Exponentiation: rd = MathOperators.power(rs1, rs2) */
    public static final byte POW_SCALAR = 22;

    /** Negation: rd = MathOperators.negate(rs) */
    public static final byte NEG_SCALAR = 23;

    // Specialized unboxed operations (optimized for pure int math)

    /** Addition with immediate: rd = rs + immediate32 (unboxed int fast path) */
    public static final byte ADD_SCALAR_INT = 24;

    /** Subtraction with immediate: rd = rs - immediate32 (unboxed int fast path) */
    public static final byte SUB_SCALAR_INT = 25;

    /** Multiplication with immediate: rd = rs * immediate32 (unboxed int fast path) */
    public static final byte MUL_SCALAR_INT = 26;

    // =================================================================
    // STRING OPERATORS (27-30) - call org.perlonjava.operators.StringOperators
    // =================================================================

    /** String concatenation: rd = StringOperators.concat(rs1, rs2) */
    public static final byte CONCAT = 27;

    /** String repetition: rd = StringOperators.repeat(rs1, rs2) */
    public static final byte REPEAT = 28;

    /** Substring: rd = StringOperators.substr(str_reg, offset_reg, length_reg) */
    public static final byte SUBSTR = 29;

    /** String length: rd = StringOperators.length(rs) */
    public static final byte LENGTH = 30;

    // =================================================================
    // COMPARISON OPERATORS (31-38) - call org.perlonjava.operators.CompareOperators
    // =================================================================

    /** Numeric comparison: rd = CompareOperators.compareNum(rs1, rs2) */
    public static final byte COMPARE_NUM = 31;

    /** String comparison: rd = CompareOperators.compareStr(rs1, rs2) */
    public static final byte COMPARE_STR = 32;

    /** Numeric equality: rd = CompareOperators.numericEqual(rs1, rs2) */
    public static final byte EQ_NUM = 33;

    /** Numeric inequality: rd = CompareOperators.numericNotEqual(rs1, rs2) */
    public static final byte NE_NUM = 34;

    /** Less than: rd = CompareOperators.numericLessThan(rs1, rs2) */
    public static final byte LT_NUM = 35;

    /** Greater than: rd = CompareOperators.numericGreaterThan(rs1, rs2) */
    public static final byte GT_NUM = 36;

    /** String equality: rd = CompareOperators.stringEqual(rs1, rs2) */
    public static final byte EQ_STR = 37;

    /** String inequality: rd = CompareOperators.stringNotEqual(rs1, rs2) */
    public static final byte NE_STR = 38;

    // =================================================================
    // LOGICAL OPERATORS (39-41)
    // =================================================================

    /** Logical NOT: rd = !rs */
    public static final byte NOT = 39;

    /** Logical AND: rd = rs1 && rs2 (short-circuit handled in bytecode compiler) */
    public static final byte AND = 40;

    /** Logical OR: rd = rs1 || rs2 (short-circuit handled in bytecode compiler) */
    public static final byte OR = 41;

    // =================================================================
    // ARRAY OPERATIONS (42-49) - use RuntimeArray API
    // =================================================================

    /** Array element access: rd = array_reg.get(index_reg) */
    public static final byte ARRAY_GET = 42;

    /** Array element store: array_reg.set(index_reg, value_reg) */
    public static final byte ARRAY_SET = 43;

    /** Array push: array_reg.push(value_reg) */
    public static final byte ARRAY_PUSH = 44;

    /** Array pop: rd = array_reg.pop() */
    public static final byte ARRAY_POP = 45;

    /** Array shift: rd = array_reg.shift() */
    public static final byte ARRAY_SHIFT = 46;

    /** Array unshift: array_reg.unshift(value_reg) */
    public static final byte ARRAY_UNSHIFT = 47;

    /** Array size: rd = new RuntimeScalar(array_reg.size()) */
    public static final byte ARRAY_SIZE = 48;

    /** Create array: rd = new RuntimeArray() */
    public static final byte CREATE_ARRAY = 49;

    // =================================================================
    // HASH OPERATIONS (50-56) - use RuntimeHash API
    // =================================================================

    /** Hash element access: rd = hash_reg.get(key_reg) */
    public static final byte HASH_GET = 50;

    /** Hash element store: hash_reg.put(key_reg, value_reg) */
    public static final byte HASH_SET = 51;

    /** Hash exists: rd = hash_reg.exists(key_reg) */
    public static final byte HASH_EXISTS = 52;

    /** Hash delete: rd = hash_reg.delete(key_reg) */
    public static final byte HASH_DELETE = 53;

    /** Hash keys: rd = hash_reg.keys() */
    public static final byte HASH_KEYS = 54;

    /** Hash values: rd = hash_reg.values() */
    public static final byte HASH_VALUES = 55;

    /** Create hash: rd = new RuntimeHash() */
    public static final byte CREATE_HASH = 56;

    // =================================================================
    // SUBROUTINE CALLS (57-59) - RuntimeCode.apply
    // =================================================================

    /** Call subroutine: rd = RuntimeCode.apply(coderef_reg, args_reg, context)
     * May return RuntimeControlFlowList for last/next/redo/goto */
    public static final byte CALL_SUB = 57;

    /** Call method: rd = RuntimeCode.call(obj_reg, method_name, args_reg, context) */
    public static final byte CALL_METHOD = 58;

    /** Call builtin: rd = BuiltinRegistry.call(builtin_id, args_reg, context) */
    public static final byte CALL_BUILTIN = 59;

    // =================================================================
    // CONTEXT OPERATIONS (60-61)
    // =================================================================

    /** List to scalar: rd = list_reg.scalar() */
    public static final byte LIST_TO_SCALAR = 60;

    /** Scalar to list: rd = new RuntimeList(scalar_reg) */
    public static final byte SCALAR_TO_LIST = 61;

    // =================================================================
    // CONTROL FLOW - SPECIAL (62-67) - RuntimeControlFlowList
    // =================================================================

    /** Create LAST control flow: rd = new RuntimeControlFlowList(LAST, label_index) */
    public static final byte CREATE_LAST = 62;

    /** Create NEXT control flow: rd = new RuntimeControlFlowList(NEXT, label_index) */
    public static final byte CREATE_NEXT = 63;

    /** Create REDO control flow: rd = new RuntimeControlFlowList(REDO, label_index) */
    public static final byte CREATE_REDO = 64;

    /** Create GOTO control flow: rd = new RuntimeControlFlowList(GOTO, label_index) */
    public static final byte CREATE_GOTO = 65;

    /** Check if return value is control flow: rd = (rs instanceof RuntimeControlFlowList) */
    public static final byte IS_CONTROL_FLOW = 66;

    /** Get control flow type: rd = ((RuntimeControlFlowList)rs).getControlFlowType().ordinal() */
    public static final byte GET_CONTROL_FLOW_TYPE = 67;

    // =================================================================
    // REFERENCE OPERATIONS (68-70)
    // =================================================================

    /** Create scalar reference: rd = new RuntimeScalar(rs) */
    public static final byte CREATE_REF = 68;

    /** Dereference: rd = rs.dereference() */
    public static final byte DEREF = 69;

    /** Type check: rd = new RuntimeScalar(rs.type.name()) */
    public static final byte GET_TYPE = 70;

    // =================================================================
    // MISCELLANEOUS (71-74)
    // =================================================================

    /** Print to STDOUT: print(rs) */
    public static final byte PRINT = 71;

    /** Say to STDOUT: say(rs) */
    public static final byte SAY = 72;

    /** Die with message: die(rs) */
    public static final byte DIE = 73;

    /** Warn with message: warn(rs) */
    public static final byte WARN = 74;

    // =================================================================
    // SUPERINSTRUCTIONS (75-90) - Combine common opcode sequences
    // These eliminate MOVE overhead by doing operation + store in one step
    // =================================================================

    /** Increment register in-place: rd = rd + 1 (combines ADD_SCALAR_INT + MOVE) */
    public static final byte INC_REG = 75;

    /** Decrement register in-place: rd = rd - 1 (combines SUB_SCALAR_INT + MOVE) */
    public static final byte DEC_REG = 76;

    /** Add and assign: rd = rd + rs (combines ADD_SCALAR + MOVE when dest == src1) */
    public static final byte ADD_ASSIGN = 77;

    /** Add immediate and assign: rd = rd + imm (combines ADD_SCALAR_INT + MOVE when dest == src) */
    public static final byte ADD_ASSIGN_INT = 78;

    /** Pre-increment: ++rd (calls RuntimeScalar.preAutoIncrement) */
    public static final byte PRE_AUTOINCREMENT = 79;

    /** Post-increment: rd++ (calls RuntimeScalar.postAutoIncrement) */
    public static final byte POST_AUTOINCREMENT = 80;

    /** Pre-decrement: --rd (calls RuntimeScalar.preAutoDecrement) */
    public static final byte PRE_AUTODECREMENT = 81;

    /** Post-decrement: rd-- (calls RuntimeScalar.postAutoDecrement) */
    public static final byte POST_AUTODECREMENT = 82;

    // =================================================================
    // EVAL BLOCK SUPPORT (83-85) - Exception handling for eval blocks
    // =================================================================

    /**
     * EVAL_TRY: Mark start of eval block with exception handling
     * Format: [EVAL_TRY] [catch_offset_high] [catch_offset_low]
     * Effect: Sets up exception handler. If exception occurs, jump to catch_offset.
     *         At start: Set $@ = ""
     */
    public static final byte EVAL_TRY = 83;

    /**
     * EVAL_CATCH: Mark start of catch block
     * Format: [EVAL_CATCH] [rd]
     * Effect: Exception object is captured, WarnDie.catchEval() is called to set $@,
     *         and undef is stored in rd as the eval result.
     */
    public static final byte EVAL_CATCH = 84;

    /**
     * EVAL_END: Mark end of successful eval block
     * Format: [EVAL_END]
     * Effect: Clear $@ = "" (nested evals may have set it)
     */
    public static final byte EVAL_END = 85;

    private Opcodes() {} // Utility class - no instantiation
}
