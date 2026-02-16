package org.perlonjava.interpreter;

/**
 * Bytecode opcodes for the PerlOnJava interpreter.
 *
 * Design: Pure register machine with 3-address code format.
 * Uses SHORT opcodes (0-32767) to support unlimited operation space.
 *
 * CRITICAL: Keep opcodes CONTIGUOUS within functional groups for JVM
 * tableswitch optimization (O(1) vs O(log n) lookupswitch).
 *
 * Register architecture is REQUIRED for control flow correctness:
 * Perl's GOTO/last/next/redo would corrupt a stack-based architecture.
 *
 * Opcode Ranges:
 * - 0-113: Core operations (current)
 * - 114-199: Reserved for expansion
 * - 200-299: Reserved
 * - 300+: Future operator promotions (CONTIGUOUS blocks!)
 *
 * Infrastructure: Bytecode already uses short[] array, compiler already
 * emits short values. Only the opcode type definitions changed.
 */
public class Opcodes {
    // =================================================================
    // CONTROL FLOW (0-4)
    // =================================================================

    /** No operation (padding/alignment) */
    public static final short NOP = 0;

    /** Return from subroutine: return rd
     * May return RuntimeControlFlowList for last/next/redo/goto */
    public static final short RETURN = 1;

    /** Unconditional jump: pc = offset (absolute bytecode offset) */
    public static final short GOTO = 2;

    /** Conditional jump: if (!rs) pc = offset */
    public static final short GOTO_IF_FALSE = 3;

    /** Conditional jump: if (rs) pc = offset */
    public static final short GOTO_IF_TRUE = 4;

    // =================================================================
    // REGISTER OPERATIONS (5-9)
    // =================================================================

    /** Register copy: rd = rs */
    public static final short MOVE = 5;

    /** Load from constant pool: rd = constants[index] */
    public static final short LOAD_CONST = 6;

    /** Load cached integer: rd = RuntimeScalarCache.getScalarInt(immediate32) */
    public static final short LOAD_INT = 7;

    /** Load string: rd = new RuntimeScalar(stringPool[index]) */
    public static final short LOAD_STRING = 8;

    /** Load undef: rd = new RuntimeScalar() */
    public static final short LOAD_UNDEF = 9;

    // =================================================================
    // VARIABLE ACCESS - GLOBAL (10-16)
    // =================================================================

    /** Load global scalar: rd = GlobalVariable.getGlobalScalar(stringPool[index]) */
    public static final short LOAD_GLOBAL_SCALAR = 10;

    /** Store global scalar: GlobalVariable.getGlobalScalar(stringPool[index]).set(rs) */
    public static final short STORE_GLOBAL_SCALAR = 11;

    /** Load global array: rd = GlobalVariable.getGlobalArray(stringPool[index]) */
    public static final short LOAD_GLOBAL_ARRAY = 12;

    /** Store global array: GlobalVariable.getGlobalArray(stringPool[index]).elements = rs */
    public static final short STORE_GLOBAL_ARRAY = 13;

    /** Load global hash: rd = GlobalVariable.getGlobalHash(stringPool[index]) */
    public static final short LOAD_GLOBAL_HASH = 14;

    /** Store global hash: GlobalVariable.getGlobalHash(stringPool[index]).elements = rs */
    public static final short STORE_GLOBAL_HASH = 15;

    /** Load global code: rd = GlobalVariable.getGlobalCodeRef(stringPool[index]) */
    public static final short LOAD_GLOBAL_CODE = 16;

    // =================================================================
    // ARITHMETIC OPERATORS (17-26) - call org.perlonjava.operators.MathOperators
    // =================================================================

    /** Addition: rd = MathOperators.add(rs1, rs2) */
    public static final short ADD_SCALAR = 17;

    /** Subtraction: rd = MathOperators.subtract(rs1, rs2) */
    public static final short SUB_SCALAR = 18;

    /** Multiplication: rd = MathOperators.multiply(rs1, rs2) */
    public static final short MUL_SCALAR = 19;

    /** Division: rd = MathOperators.divide(rs1, rs2) */
    public static final short DIV_SCALAR = 20;

    /** Modulus: rd = MathOperators.modulus(rs1, rs2) */
    public static final short MOD_SCALAR = 21;

    /** Exponentiation: rd = MathOperators.power(rs1, rs2) */
    public static final short POW_SCALAR = 22;

    /** Negation: rd = MathOperators.negate(rs) */
    public static final short NEG_SCALAR = 23;

    // Specialized unboxed operations (optimized for pure int math)

    /** Addition with immediate: rd = rs + immediate32 (unboxed int fast path) */
    public static final short ADD_SCALAR_INT = 24;

    /** Subtraction with immediate: rd = rs - immediate32 (unboxed int fast path) */
    public static final short SUB_SCALAR_INT = 25;

    /** Multiplication with immediate: rd = rs * immediate32 (unboxed int fast path) */
    public static final short MUL_SCALAR_INT = 26;

    // =================================================================
    // STRING OPERATORS (27-30) - call org.perlonjava.operators.StringOperators
    // =================================================================

    /** String concatenation: rd = StringOperators.concat(rs1, rs2) */
    public static final short CONCAT = 27;

    /** String repetition: rd = StringOperators.repeat(rs1, rs2) */
    public static final short REPEAT = 28;

    /** Substring: rd = StringOperators.substr(str_reg, offset_reg, length_reg) */
    public static final short SUBSTR = 29;

    /** String length: rd = StringOperators.length(rs) */
    public static final short LENGTH = 30;

    // =================================================================
    // COMPARISON OPERATORS (31-38) - call org.perlonjava.operators.CompareOperators
    // =================================================================

    /** Numeric comparison: rd = CompareOperators.compareNum(rs1, rs2) */
    public static final short COMPARE_NUM = 31;

    /** String comparison: rd = CompareOperators.compareStr(rs1, rs2) */
    public static final short COMPARE_STR = 32;

    /** Numeric equality: rd = CompareOperators.numericEqual(rs1, rs2) */
    public static final short EQ_NUM = 33;

    /** Numeric inequality: rd = CompareOperators.numericNotEqual(rs1, rs2) */
    public static final short NE_NUM = 34;

    /** Less than: rd = CompareOperators.numericLessThan(rs1, rs2) */
    public static final short LT_NUM = 35;

    /** Greater than: rd = CompareOperators.numericGreaterThan(rs1, rs2) */
    public static final short GT_NUM = 36;

    /** String equality: rd = CompareOperators.stringEqual(rs1, rs2) */
    public static final short EQ_STR = 37;

    /** String inequality: rd = CompareOperators.stringNotEqual(rs1, rs2) */
    public static final short NE_STR = 38;

    // =================================================================
    // LOGICAL OPERATORS (39-41)
    // =================================================================

    /** Logical NOT: rd = !rs */
    public static final short NOT = 39;

    /** Logical AND: rd = rs1 && rs2 (short-circuit handled in bytecode compiler) */
    public static final short AND = 40;

    /** Logical OR: rd = rs1 || rs2 (short-circuit handled in bytecode compiler) */
    public static final short OR = 41;

    // =================================================================
    // ARRAY OPERATIONS (42-49) - use RuntimeArray API
    // =================================================================

    /** Array element access: rd = array_reg.get(index_reg) */
    public static final short ARRAY_GET = 42;

    /** Array element store: array_reg.set(index_reg, value_reg) */
    public static final short ARRAY_SET = 43;

    /** Array push: array_reg.push(value_reg) */
    public static final short ARRAY_PUSH = 44;

    /** Array pop: rd = array_reg.pop() */
    public static final short ARRAY_POP = 45;

    /** Array shift: rd = array_reg.shift() */
    public static final short ARRAY_SHIFT = 46;

    /** Array unshift: array_reg.unshift(value_reg) */
    public static final short ARRAY_UNSHIFT = 47;

    /** Array size: rd = new RuntimeScalar(array_reg.size()) */
    public static final short ARRAY_SIZE = 48;

    /** Create array: rd = new RuntimeArray() */
    public static final short CREATE_ARRAY = 49;

    // =================================================================
    // HASH OPERATIONS (50-56) - use RuntimeHash API
    // =================================================================

    /** Hash element access: rd = hash_reg.get(key_reg) */
    public static final short HASH_GET = 50;

    /** Hash element store: hash_reg.put(key_reg, value_reg) */
    public static final short HASH_SET = 51;

    /** Hash exists: rd = hash_reg.exists(key_reg) */
    public static final short HASH_EXISTS = 52;

    /** Hash delete: rd = hash_reg.delete(key_reg) */
    public static final short HASH_DELETE = 53;

    /** Hash keys: rd = hash_reg.keys() */
    public static final short HASH_KEYS = 54;

    /** Hash values: rd = hash_reg.values() */
    public static final short HASH_VALUES = 55;

    /** Create hash reference from list: rd = RuntimeHash.createHash(rs_list).createReference() */
    public static final short CREATE_HASH = 56;

    // =================================================================
    // SUBROUTINE CALLS (57-59) - RuntimeCode.apply
    // =================================================================

    /** Call subroutine: rd = RuntimeCode.apply(coderef_reg, args_reg, context)
     * May return RuntimeControlFlowList for last/next/redo/goto */
    public static final short CALL_SUB = 57;

    /** Call method: rd = RuntimeCode.call(obj_reg, method_name, args_reg, context) */
    public static final short CALL_METHOD = 58;

    /** Call builtin: rd = BuiltinRegistry.call(builtin_id, args_reg, context) */
    public static final short CALL_BUILTIN = 59;

    // =================================================================
    // CONTEXT OPERATIONS (60-61)
    // =================================================================

    /** List to scalar: rd = list_reg.scalar() */
    public static final short LIST_TO_SCALAR = 60;

    /** Scalar to list: rd = new RuntimeList(scalar_reg) */
    public static final short SCALAR_TO_LIST = 61;

    // =================================================================
    // CONTROL FLOW - SPECIAL (62-67) - RuntimeControlFlowList
    // =================================================================

    /** Create LAST control flow: rd = new RuntimeControlFlowList(LAST, label_index) */
    public static final short CREATE_LAST = 62;

    /** Create NEXT control flow: rd = new RuntimeControlFlowList(NEXT, label_index) */
    public static final short CREATE_NEXT = 63;

    /** Create REDO control flow: rd = new RuntimeControlFlowList(REDO, label_index) */
    public static final short CREATE_REDO = 64;

    /** Create GOTO control flow: rd = new RuntimeControlFlowList(GOTO, label_index) */
    public static final short CREATE_GOTO = 65;

    /** Check if return value is control flow: rd = (rs instanceof RuntimeControlFlowList) */
    public static final short IS_CONTROL_FLOW = 66;

    /** Get control flow type: rd = ((RuntimeControlFlowList)rs).getControlFlowType().ordinal() */
    public static final short GET_CONTROL_FLOW_TYPE = 67;

    // =================================================================
    // REFERENCE OPERATIONS (68-70)
    // =================================================================

    /** Create scalar reference: rd = new RuntimeScalar(rs) */
    public static final short CREATE_REF = 68;

    /** Dereference: rd = rs.dereference() */
    public static final short DEREF = 69;

    /** Type check: rd = new RuntimeScalar(rs.type.name()) */
    public static final short GET_TYPE = 70;

    // =================================================================
    // MISCELLANEOUS (71-74)
    // =================================================================

    /** Print to filehandle: print(rs_content, rs_filehandle) */
    public static final short PRINT = 71;

    /** Say to filehandle: say(rs_content, rs_filehandle) */
    public static final short SAY = 72;

    /** Die with message: die(rs) */
    public static final short DIE = 73;

    /** Warn with message: warn(rs) */
    public static final short WARN = 74;

    // =================================================================
    // SUPERINSTRUCTIONS (75-90) - Combine common opcode sequences
    // These eliminate MOVE overhead by doing operation + store in one step
    // =================================================================

    /** Increment register in-place: rd = rd + 1 (combines ADD_SCALAR_INT + MOVE) */
    public static final short INC_REG = 75;

    /** Decrement register in-place: rd = rd - 1 (combines SUB_SCALAR_INT + MOVE) */
    public static final short DEC_REG = 76;

    /** Add and assign: rd = rd + rs (combines ADD_SCALAR + MOVE when dest == src1) */
    public static final short ADD_ASSIGN = 77;

    /** Add immediate and assign: rd = rd + imm (combines ADD_SCALAR_INT + MOVE when dest == src) */
    public static final short ADD_ASSIGN_INT = 78;

    /** Pre-increment: ++rd (calls RuntimeScalar.preAutoIncrement) */
    public static final short PRE_AUTOINCREMENT = 79;

    /** Post-increment: rd++ (calls RuntimeScalar.postAutoIncrement) */
    public static final short POST_AUTOINCREMENT = 80;

    /** Pre-decrement: --rd (calls RuntimeScalar.preAutoDecrement) */
    public static final short PRE_AUTODECREMENT = 81;

    /** Post-decrement: rd-- (calls RuntimeScalar.postAutoDecrement) */
    public static final short POST_AUTODECREMENT = 82;

    // =================================================================
    // EVAL BLOCK SUPPORT (83-85) - Exception handling for eval blocks
    // =================================================================

    /**
     * EVAL_TRY: Mark start of eval block with exception handling
     * Format: [EVAL_TRY] [catch_offset_high] [catch_offset_low]
     * Effect: Sets up exception handler. If exception occurs, jump to catch_offset.
     *         At start: Set $@ = ""
     */
    public static final short EVAL_TRY = 83;

    /**
     * EVAL_CATCH: Mark start of catch block
     * Format: [EVAL_CATCH] [rd]
     * Effect: Exception object is captured, WarnDie.catchEval() is called to set $@,
     *         and undef is stored in rd as the eval result.
     */
    public static final short EVAL_CATCH = 84;

    /**
     * EVAL_END: Mark end of successful eval block
     * Format: [EVAL_END]
     * Effect: Clear $@ = "" (nested evals may have set it)
     */
    public static final short EVAL_END = 85;

    /**
     * CREATE_LIST: Create RuntimeList from registers
     * Format: [CREATE_LIST] [rd] [count] [rs1] [rs2] ... [rsN]
     * Effect: rd = new RuntimeList(registers[rs1], registers[rs2], ..., registers[rsN])
     *
     * Highly optimized for common cases:
     * - count=0: Creates empty RuntimeList
     * - count=1: Creates RuntimeList with single element
     * - count>1: Creates RuntimeList and adds all elements
     *
     * This is the most performance-critical opcode for list operations.
     */
    public static final short CREATE_LIST = 86;

    // =================================================================
    // SLOW OPERATIONS (87) - Single opcode for rarely-used operations
    // =================================================================

    /**
     * SLOW_OP: Dispatch to rarely-used operation handler
     * Format: [SLOW_OP] [slow_op_id] [operands...]
     * Effect: Dispatches to SlowOpcodeHandler based on slow_op_id
     *
     * This uses only ONE opcode number but supports 256 slow operations
     * via the slow_op_id byte parameter. Keeps main switch compact for
     * CPU i-cache optimization while allowing unlimited rare operations.
     *
     * Philosophy:
     * - Fast operations (0-90): Direct opcodes in main switch
     * - Slow operations (via SLOW_OP): Delegated to SlowOpcodeHandler
     *
     * Performance: Adds ~5ns overhead but keeps main loop ~10-15% faster.
     */
    public static final short SLOW_OP = 87;

    // =================================================================
    // STRING OPERATIONS (88)
    // =================================================================

    /** Join list elements with separator: rd = join(rs_separator, rs_list) */
    public static final short JOIN = 88;

    // =================================================================
    // I/O OPERATIONS (89)
    // =================================================================

    /** Select default output filehandle: rd = IOOperator.select(rs_list, SCALAR) */
    public static final short SELECT = 89;

    /** Create range: rd = PerlRange.createRange(rs_start, rs_end) */
    public static final short RANGE = 90;

    /** Random number: rd = Random.rand(rs_max) */
    public static final short RAND = 91;

    /** Map operator: rd = ListOperators.map(list_reg, closure_reg, context) */
    public static final short MAP = 92;

    /** Create empty array: rd = new RuntimeArray() */
    public static final short NEW_ARRAY = 93;

    /** Create empty hash: rd = new RuntimeHash() */
    public static final short NEW_HASH = 94;

    /** Set array from list: array_reg.setFromList(list_reg) */
    public static final short ARRAY_SET_FROM_LIST = 95;

    /** Set hash from list: hash_reg = RuntimeHash.createHash(list_reg) then copy elements */
    public static final short HASH_SET_FROM_LIST = 96;

    /** Store global code: GlobalVariable.getGlobalCodeRef().put(stringPool[nameIdx], codeRef) */
    public static final short STORE_GLOBAL_CODE = 97;

    /** Create closure with captured variables: rd = createClosure(template, registers[rs1], registers[rs2], ...)
     * Format: CREATE_CLOSURE rd template_const_idx num_captures reg1 reg2 ... */
    public static final short CREATE_CLOSURE = 98;

    /** Set scalar value: ((RuntimeScalar)registers[rd]).set((RuntimeScalar)registers[rs])
     * Format: SET_SCALAR rd rs
     * Used to set the value in a persistent scalar without overwriting the reference */
    public static final short SET_SCALAR = 99;

    /** Grep operator: rd = ListOperators.grep(list_reg, closure_reg, context) */
    public static final short GREP = 100;

    /** Sort operator: rd = ListOperators.sort(list_reg, closure_reg, package_name) */
    public static final short SORT = 101;

    /** Defined operator: rd = defined(rs) - check if value is defined */
    public static final short DEFINED = 102;

    /** Ref operator: rd = ref(rs) - get reference type as string */
    public static final short REF = 103;

    /** Bless operator: rd = bless(rs_ref, rs_package) - bless a reference into a package */
    public static final short BLESS = 104;

    /** ISA operator: rd = isa(rs_obj, rs_package) - check if object is instance of package */
    public static final short ISA = 105;

    // =================================================================
    // ITERATOR OPERATIONS (106-108) - For efficient foreach loops
    // =================================================================

    /** Create iterator: rd = rs.iterator() - get Iterator from Iterable */
    public static final short ITERATOR_CREATE = 106;

    /** Check iterator: rd = iterator.hasNext() - returns boolean as RuntimeScalar */
    public static final short ITERATOR_HAS_NEXT = 107;

    /** Get next element: rd = iterator.next() - returns RuntimeScalar */
    public static final short ITERATOR_NEXT = 108;

    /** Superinstruction for foreach loops: check hasNext, get next element, or jump to target if done
     * Format: FOREACH_NEXT_OR_EXIT rd iter_reg exit_target(int)
     * If iterator.hasNext(): rd = iterator.next(), continue to next instruction
     * Else: pc = exit_target (absolute address, like GOTO) */
    public static final short FOREACH_NEXT_OR_EXIT = 109;

    // Compound assignment operators with overload support
    public static final short SUBTRACT_ASSIGN = 110;
    public static final short MULTIPLY_ASSIGN = 111;
    public static final short DIVIDE_ASSIGN = 112;
    public static final short MODULUS_ASSIGN = 113;

    // =================================================================
    // Slow Operation IDs (0-255)
    // =================================================================
    // These are NOT opcodes - they are sub-operation identifiers
    // used by the SLOW_OP opcode.

    /** Slow op ID: chown(rs_list, rs_uid, rs_gid) */
    public static final int SLOWOP_CHOWN = 0;

    /** Slow op ID: rd = waitpid(rs_pid, rs_flags) */
    public static final int SLOWOP_WAITPID = 1;

    /** Slow op ID: setsockopt(rs_socket, rs_level, rs_optname, rs_optval) */
    public static final int SLOWOP_SETSOCKOPT = 2;

    /** Slow op ID: rd = getsockopt(rs_socket, rs_level, rs_optname) */
    public static final int SLOWOP_GETSOCKOPT = 3;

    /** Slow op ID: rd = getpriority(rs_which, rs_who) */
    public static final int SLOWOP_GETPRIORITY = 4;

    /** Slow op ID: setpriority(rs_which, rs_who, rs_priority) */
    public static final int SLOWOP_SETPRIORITY = 5;

    /** Slow op ID: rd = getpgrp(rs_pid) */
    public static final int SLOWOP_GETPGRP = 6;

    /** Slow op ID: setpgrp(rs_pid, rs_pgrp) */
    public static final int SLOWOP_SETPGRP = 7;

    /** Slow op ID: rd = getppid() */
    public static final int SLOWOP_GETPPID = 8;

    /** Slow op ID: rd = fork() */
    public static final int SLOWOP_FORK = 9;

    /** Slow op ID: rd = semget(rs_key, rs_nsems, rs_flags) */
    public static final int SLOWOP_SEMGET = 10;

    /** Slow op ID: rd = semop(rs_semid, rs_opstring) */
    public static final int SLOWOP_SEMOP = 11;

    /** Slow op ID: rd = msgget(rs_key, rs_flags) */
    public static final int SLOWOP_MSGGET = 12;

    /** Slow op ID: rd = msgsnd(rs_id, rs_msg, rs_flags) */
    public static final int SLOWOP_MSGSND = 13;

    /** Slow op ID: rd = msgrcv(rs_id, rs_size, rs_type, rs_flags) */
    public static final int SLOWOP_MSGRCV = 14;

    /** Slow op ID: rd = shmget(rs_key, rs_size, rs_flags) */
    public static final int SLOWOP_SHMGET = 15;

    /** Slow op ID: rd = shmread(rs_id, rs_pos, rs_size) */
    public static final int SLOWOP_SHMREAD = 16;

    /** Slow op ID: shmwrite(rs_id, rs_pos, rs_string) */
    public static final int SLOWOP_SHMWRITE = 17;

    /** Slow op ID: rd = syscall(rs_number, rs_args...) */
    public static final int SLOWOP_SYSCALL = 18;

    /** Slow op ID: rd = eval(rs_string) - dynamic code evaluation */
    public static final int SLOWOP_EVAL_STRING = 19;

    /** Slow op ID: rd = select(rs_list) - set/get default output filehandle */
    public static final int SLOWOP_SELECT = 20;

    /** Slow op ID: rd = getGlobalIO(name) - load glob/filehandle from global variables */
    public static final int SLOWOP_LOAD_GLOB = 21;

    /** Slow op ID: rd = Time.sleep(seconds) - sleep for specified seconds */
    public static final int SLOWOP_SLEEP = 22;

    /** Slow op ID: rd = deref_array(scalar_ref) - dereference array reference for multidimensional access */
    public static final int SLOWOP_DEREF_ARRAY = 23;

    /** Slow op ID: rd = PersistentVariable.retrieveBeginScalar(var_name, begin_id) - retrieve BEGIN scalar */
    public static final int SLOWOP_RETRIEVE_BEGIN_SCALAR = 24;

    /** Slow op ID: rd = PersistentVariable.retrieveBeginArray(var_name, begin_id) - retrieve BEGIN array */
    public static final int SLOWOP_RETRIEVE_BEGIN_ARRAY = 25;

    /** Slow op ID: rd = PersistentVariable.retrieveBeginHash(var_name, begin_id) - retrieve BEGIN hash */
    public static final int SLOWOP_RETRIEVE_BEGIN_HASH = 26;

    /** Slow op ID: rd = GlobalRuntimeScalar.makeLocal(var_name) - temporarily localize global variable */
    public static final int SLOWOP_LOCAL_SCALAR = 27;

    /** Slow op ID: rd = Operator.splice(array, args_list) - splice array operation */
    public static final int SLOWOP_SPLICE = 28;

    /** Slow op ID: rd = array.getSlice(indices_list) - array slice operation */
    public static final int SLOWOP_ARRAY_SLICE = 29;

    /** Slow op ID: rd = Operator.reverse(ctx, args...) - reverse array or string */
    public static final int SLOWOP_REVERSE = 30;

    /** Slow op ID: array.setSlice(indices, values) - array slice assignment */
    public static final int SLOWOP_ARRAY_SLICE_SET = 31;

    /** Slow op ID: rd = Operator.split(pattern, args, ctx) - split string into array */
    public static final int SLOWOP_SPLIT = 32;

    /** Slow opcode for exists operator (fallback) */
    public static final int SLOWOP_EXISTS = 33;

    /** Slow opcode for delete operator (fallback) */
    public static final int SLOWOP_DELETE = 34;

    /** Slow op ID: rd = deref_hash(scalar_ref) - dereference hash reference for hashref access */
    public static final int SLOWOP_DEREF_HASH = 35;

    /** Slow op ID: rd = hash.getSlice(keys_list) - hash slice operation @hash{keys} */
    public static final int SLOWOP_HASH_SLICE = 36;

    /** Slow op ID: rd = hash.deleteSlice(keys_list) - hash slice delete operation delete @hash{keys} */
    public static final int SLOWOP_HASH_SLICE_DELETE = 37;

    /** Slow op ID: hash.setSlice(keys_list, values_list) - hash slice assignment @hash{keys} = values */
    public static final int SLOWOP_HASH_SLICE_SET = 38;

    /** Slow op ID: rd = list[start..] - extract list slice from start index to end */
    public static final int SLOWOP_LIST_SLICE_FROM = 39;

    /** Slow op ID: rd = length(string) - get string length */
    public static final int SLOWOP_LENGTH = 40;

    // =================================================================
    // OPCODES 93-255: RESERVED FOR FUTURE FAST OPERATIONS
    // =================================================================
    // This range is reserved for frequently-used operations that benefit
    // from being in the main interpreter switch for optimal CPU i-cache usage.

    private Opcodes() {} // Utility class - no instantiation
}
