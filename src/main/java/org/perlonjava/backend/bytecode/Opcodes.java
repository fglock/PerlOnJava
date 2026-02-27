package org.perlonjava.backend.bytecode;

// To check for duplicate or out-of-order opcode numbers, run:
//   perl dev/tools/check_opcodes.pl src/main/java/org/perlonjava/backend/bytecode/Opcodes.java
// To renumber the absolute block (284+) to fill gaps, add --renumber.

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
    // ARITHMETIC OPERATORS (17-26) - call org.perlonjava.runtime.operators.MathOperators
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
    // STRING OPERATORS (27-30) - call org.perlonjava.runtime.operators.StringOperators
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
    // COMPARISON OPERATORS (31-38) - call org.perlonjava.runtime.operators.CompareOperators
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

    /** Convert list/array to count in scalar context: rd = list.size()
     * Format: LIST_TO_COUNT rd rs */
    public static final short LIST_TO_COUNT = 60;

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
    // ITERATOR OPERATIONS - For efficient foreach loops
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
    // PHASE 2: SLOW_OP PROMOTIONS (114-154) - CONTIGUOUS RANGES
    // =================================================================
    // These operations were previously handled by SLOW_OP (opcode 87).
    // Now promoted to direct opcodes for better performance (~5ns saved).
    // IMPORTANT: Keep ranges CONTIGUOUS for JVM tableswitch optimization!

    // Group 1: Dereferencing (114-115) - CONTIGUOUS
    /** Dereference array for multidimensional access: rd = deref_array(rs) */
    public static final short DEREF_ARRAY = 114;
    /** Dereference hash for hashref access: rd = deref_hash(rs) */
    public static final short DEREF_HASH = 115;

    // Group 2: Slice Operations (116-121) - CONTIGUOUS
    /** Array slice: rd = array.getSlice(indices_list) */
    public static final short ARRAY_SLICE = 116;
    /** Array slice assignment: array.setSlice(indices, values) */
    public static final short ARRAY_SLICE_SET = 117;
    /** Hash slice: rd = hash.getSlice(keys_list) */
    public static final short HASH_SLICE = 118;
    /** Hash slice assignment: hash.setSlice(keys, values) */
    public static final short HASH_SLICE_SET = 119;
    /** Hash slice delete: rd = hash.deleteSlice(keys_list) */
    public static final short HASH_SLICE_DELETE = 120;
    /** List slice from index: rd = list[start..] */
    public static final short LIST_SLICE_FROM = 121;

    // Group 3: Array/String Ops (122-125) - CONTIGUOUS
    /** Splice array: rd = Operator.splice(array, args_list) */
    public static final short SPLICE = 122;
    /** Reverse array or string: rd = Operator.reverse(ctx, args...) */
    public static final short REVERSE = 123;
    /** Split string into array: rd = Operator.split(pattern, args, ctx) */
    public static final short SPLIT = 124;
    /** String length: rd = length(string) */
    public static final short LENGTH_OP = 125;

    // Group 4: Exists/Delete (126-127) - CONTIGUOUS
    /** Exists operator: rd = exists(key) */
    public static final short EXISTS = 126;
    /** Delete operator: rd = delete(key) */
    public static final short DELETE = 127;

    // Group 5: Closure/Scope (128-131) - CONTIGUOUS
    /** Retrieve BEGIN scalar: rd = PersistentVariable.retrieveBeginScalar(var_name, begin_id) */
    public static final short RETRIEVE_BEGIN_SCALAR = 128;
    /** Retrieve BEGIN array: rd = PersistentVariable.retrieveBeginArray(var_name, begin_id) */
    public static final short RETRIEVE_BEGIN_ARRAY = 129;
    /** Retrieve BEGIN hash: rd = PersistentVariable.retrieveBeginHash(var_name, begin_id) */
    public static final short RETRIEVE_BEGIN_HASH = 130;
    /** Localize global variable: rd = GlobalRuntimeScalar.makeLocal(var_name) */
    public static final short LOCAL_SCALAR = 131;
    /** Localize global array: rd = GlobalVariable.getGlobalArray(var_name) (dynamicSaveState via DynamicVariableManager) */
    public static final short LOCAL_ARRAY = 345;
    /** Localize global hash: rd = GlobalVariable.getGlobalHash(var_name) (dynamicSaveState via DynamicVariableManager) */
    public static final short LOCAL_HASH = 346;

    // Group 6: System Calls (132-141) - CONTIGUOUS
    /** chown(list, uid, gid) */
    public static final short CHOWN = 132;
    /** rd = waitpid(pid, flags) */
    public static final short WAITPID = 133;
    /** rd = fork() */
    public static final short FORK = 134;
    /** rd = getppid() */
    public static final short GETPPID = 135;
    /** rd = getpgrp(pid) */
    public static final short GETPGRP = 136;
    /** setpgrp(pid, pgrp) */
    public static final short SETPGRP = 137;
    /** rd = getpriority(which, who) */
    public static final short GETPRIORITY = 138;
    /** setpriority(which, who, priority) */
    public static final short SETPRIORITY = 139;
    /** rd = getsockopt(socket, level, optname) */
    public static final short GETSOCKOPT = 140;
    /** setsockopt(socket, level, optname, optval) */
    public static final short SETSOCKOPT = 141;

    // Group 7: IPC Operations (142-148) - CONTIGUOUS
    /** rd = syscall(number, args...) */
    public static final short SYSCALL = 142;
    /** rd = semget(key, nsems, flags) */
    public static final short SEMGET = 143;
    /** rd = semop(semid, opstring) */
    public static final short SEMOP = 144;
    /** rd = msgget(key, flags) */
    public static final short MSGGET = 145;
    /** rd = msgsnd(id, msg, flags) */
    public static final short MSGSND = 146;
    /** rd = msgrcv(id, size, type, flags) */
    public static final short MSGRCV = 147;
    /** rd = shmget(key, size, flags) */
    public static final short SHMGET = 148;

    // Group 8: Shared Memory (149-150) - CONTIGUOUS
    /** rd = shmread(id, pos, size) */
    public static final short SHMREAD = 149;
    /** shmwrite(id, pos, string) */
    public static final short SHMWRITE = 150;

    // Group 9: Special I/O (151-154) - CONTIGUOUS
    /** rd = eval(string) - dynamic code evaluation */
    public static final short EVAL_STRING = 151;
    /** rd = select(list) - set/get default output filehandle */
    public static final short SELECT_OP = 152;
    /** rd = getGlobalIO(name) - load glob/filehandle from global variables */
    public static final short LOAD_GLOB = 153;
    /** rd = Time.sleep(seconds) - sleep for specified seconds */
    public static final short SLEEP_OP = 154;

    // =================================================================
    // PHASE 3: OPERATORHANDLER PROMOTIONS (400-499) - Math Operators
    // =================================================================
    // IMPORTANT: Keep CONTIGUOUS for JVM tableswitch optimization!

    // Math Operators (400-409) - CONTIGUOUS
    /** Power operator: rd = MathOperators.pow(rs1, rs2) - equivalent to rs1 ** rs2 */
    public static final short OP_POW = 155;
    /** Absolute value: rd = MathOperators.abs(rs) - equivalent to abs(rs) */
    public static final short OP_ABS = 156;
    /** Integer conversion: rd = MathOperators.integer(rs) - equivalent to int(rs) */
    public static final short OP_INT = 157;

    /** Prototype operator: rd = RuntimeCode.prototype(rs_coderef, package_name)
     * Format: PROTOTYPE rd rs package_name_idx(int) */
    public static final short PROTOTYPE = 158;

    /** Quote regex operator: rd = RuntimeRegex.getQuotedRegex(pattern_reg, flags_reg)
     * Format: QUOTE_REGEX rd pattern_reg flags_reg */
    public static final short QUOTE_REGEX = 159;

    /** Less than or equal: rd = CompareOperators.numericLessThanOrEqual(rs1, rs2) */
    public static final short LE_NUM = 160;

    /** Greater than or equal: rd = CompareOperators.numericGreaterThanOrEqual(rs1, rs2) */
    public static final short GE_NUM = 161;

    /** String concatenation assignment: rd .= rs (appends rs to rd)
     * Format: STRING_CONCAT_ASSIGN rd rs */
    public static final short STRING_CONCAT_ASSIGN = 162;

    /** Push variable to local stack: DynamicVariableManager.pushLocalVariable(rs)
     * Format: PUSH_LOCAL_VARIABLE rs */
    public static final short PUSH_LOCAL_VARIABLE = 163;

    /** Store to glob: glob.set(rs)
     * Format: STORE_GLOB globReg valueReg */
    public static final short STORE_GLOB = 164;

    /** Localize a typeglob: rd = DynamicVariableManager.pushLocalVariable(LOAD_GLOB(nameIdx))
     * Saves current glob state and returns the glob for potential assignment.
     * Format: LOCAL_GLOB rd nameIdx */
    public static final short LOCAL_GLOB = 342;

    /** Flip-flop operator: rd = ScalarFlipFlopOperator.evaluate(flipFlopId, rs1, rs2)
     * flipFlopId is a unique per-call-site int constant.
     * Format: FLIP_FLOP rd flipFlopId rs1 rs2 isExclusive */
    public static final short FLIP_FLOP = 343;

    /** Open file: rd = IOOperator.open(ctx, args...)
     * Format: OPEN rd ctx argsReg */
    public static final short OPEN = 165;

    /** Read line from filehandle: rd = Readline.readline(fh_ref, ctx)
     * Format: READLINE rd fhReg ctx */
    public static final short READLINE = 166;

    /** Match regex: rd = RuntimeRegex.matchRegex(string, regex, ctx)
     * Format: MATCH_REGEX rd stringReg regexReg ctx */
    public static final short MATCH_REGEX = 167;

    /** Chomp: rd = rs.chomp()
     * Format: CHOMP rd rs */
    public static final short CHOMP = 168;

    /** Get wantarray context: rd = Operator.wantarray(wantarrayReg)
     * Format: WANTARRAY rd wantarrayReg */
    public static final short WANTARRAY = 169;

    /** Require module or version: rd = ModuleOperators.require(rs)
     * Format: REQUIRE rd rs */
    public static final short REQUIRE = 170;

    /** Get regex position: rd = rs.pos() (returns lvalue for assignment)
     * Format: POS rd rs */
    public static final short POS = 171;

    /** Find substring position: rd = StringOperators.index(str, substr, pos)
     * Format: INDEX rd str substr pos */
    public static final short INDEX = 172;

    /** Find substring position from end: rd = StringOperators.rindex(str, substr, pos)
     * Format: RINDEX rd str substr pos */
    public static final short RINDEX = 173;

    /** Bitwise AND assignment: target &= value
     * Format: BITWISE_AND_ASSIGN target value */
    public static final short BITWISE_AND_ASSIGN = 174;

    /** Bitwise OR assignment: target |= value
     * Format: BITWISE_OR_ASSIGN target value */
    public static final short BITWISE_OR_ASSIGN = 175;

    /** Bitwise XOR assignment: target ^= value
     * Format: BITWISE_XOR_ASSIGN target value */
    public static final short BITWISE_XOR_ASSIGN = 176;

    /** String bitwise AND assignment: target &.= value
     * Format: STRING_BITWISE_AND_ASSIGN target value */
    public static final short STRING_BITWISE_AND_ASSIGN = 177;

    /** String bitwise OR assignment: target |.= value
     * Format: STRING_BITWISE_OR_ASSIGN target value */
    public static final short STRING_BITWISE_OR_ASSIGN = 178;

    /** String bitwise XOR assignment: target ^.= value
     * Format: STRING_BITWISE_XOR_ASSIGN target value */
    public static final short STRING_BITWISE_XOR_ASSIGN = 179;

    /** Numeric bitwise AND: rd = rs1 binary& rs2
     * Format: BITWISE_AND_BINARY rd rs1 rs2 */
    public static final short BITWISE_AND_BINARY = 180;

    /** Numeric bitwise OR: rd = rs1 binary| rs2
     * Format: BITWISE_OR_BINARY rd rs1 rs2 */
    public static final short BITWISE_OR_BINARY = 181;

    /** Numeric bitwise XOR: rd = rs1 binary^ rs2
     * Format: BITWISE_XOR_BINARY rd rs1 rs2 */
    public static final short BITWISE_XOR_BINARY = 182;

    /** String bitwise AND: rd = rs1 &. rs2
     * Format: STRING_BITWISE_AND rd rs1 rs2 */
    public static final short STRING_BITWISE_AND = 183;

    /** String bitwise OR: rd = rs1 |. rs2
     * Format: STRING_BITWISE_OR rd rs1 rs2 */
    public static final short STRING_BITWISE_OR = 184;

    /** String bitwise XOR: rd = rs1 ^. rs2
     * Format: STRING_BITWISE_XOR rd rs1 rs2 */
    public static final short STRING_BITWISE_XOR = 185;

    /** Numeric bitwise NOT: rd = binary~ rs
     * Format: BITWISE_NOT_BINARY rd rs */
    public static final short BITWISE_NOT_BINARY = 186;

    /** String bitwise NOT: rd = ~. rs
     * Format: BITWISE_NOT_STRING rd rs */
    public static final short BITWISE_NOT_STRING = 187;

    // =================================================================
    // FILE TEST AND STAT OPERATIONS
    // =================================================================

    /** stat operator: rd = stat(rs) [context]
     * Format: STAT rd rs ctx */
    public static final short STAT = 188;

    /** lstat operator: rd = lstat(rs) [context]
     * Format: LSTAT rd rs ctx */
    public static final short LSTAT = 189;

    // File test operators (unary operators returning boolean or value)
    /** -r FILE: readable */
    public static final short FILETEST_R = 190;
    /** -w FILE: writable */
    public static final short FILETEST_W = 191;
    /** -x FILE: executable */
    public static final short FILETEST_X = 192;
    /** -o FILE: owned by effective uid */
    public static final short FILETEST_O = 193;
    /** -R FILE: readable by real uid */
    public static final short FILETEST_R_REAL = 194;
    /** -W FILE: writable by real uid */
    public static final short FILETEST_W_REAL = 195;
    /** -X FILE: executable by real uid */
    public static final short FILETEST_X_REAL = 196;
    /** -O FILE: owned by real uid */
    public static final short FILETEST_O_REAL = 197;
    /** -e FILE: exists */
    public static final short FILETEST_E = 198;
    /** -z FILE: zero size */
    public static final short FILETEST_Z = 199;
    /** -s FILE: size in bytes */
    public static final short FILETEST_S = 200;
    /** -f FILE: plain file */
    public static final short FILETEST_F = 201;
    /** -d FILE: directory */
    public static final short FILETEST_D = 202;
    /** -l FILE: symbolic link */
    public static final short FILETEST_L = 203;
    /** -p FILE: named pipe */
    public static final short FILETEST_P = 204;
    /** -S FILE: socket */
    public static final short FILETEST_S_UPPER = 205;
    /** -b FILE: block special */
    public static final short FILETEST_B = 206;
    /** -c FILE: character special */
    public static final short FILETEST_C = 207;
    /** -t FILE: tty */
    public static final short FILETEST_T = 208;
    /** -u FILE: setuid */
    public static final short FILETEST_U = 209;
    /** -g FILE: setgid */
    public static final short FILETEST_G = 210;
    /** -k FILE: sticky bit */
    public static final short FILETEST_K = 211;
    /** -T FILE: text file */
    public static final short FILETEST_T_UPPER = 212;
    /** -B FILE: binary file */
    public static final short FILETEST_B_UPPER = 213;
    /** -M FILE: modification age (days) */
    public static final short FILETEST_M = 214;
    /** -A FILE: access age (days) */
    public static final short FILETEST_A = 215;
    /** -C FILE: inode change age (days) */
    public static final short FILETEST_C_UPPER = 216;

    /** Match regex (negated): rd = !RuntimeRegex.matchRegex(string, regex, ctx)
     * Format: MATCH_REGEX_NOT rd stringReg regexReg ctx */
    public static final short MATCH_REGEX_NOT = 217;

    // =================================================================
    // LOOP CONTROL OPERATIONS - last/next/redo
    // =================================================================

    /** Loop last: Jump to end of loop or return RuntimeControlFlowList for non-local
     * Format: LAST labelIndex
     * labelIndex: index into stringPool for label name (or -1 for unlabeled) */
    public static final short LAST = 218;

    /** Loop next: Jump to continue/next label or return RuntimeControlFlowList for non-local
     * Format: NEXT labelIndex
     * labelIndex: index into stringPool for label name (or -1 for unlabeled) */
    public static final short NEXT = 219;

    /** Loop redo: Jump to start of loop or return RuntimeControlFlowList for non-local
     * Format: REDO labelIndex
     * labelIndex: index into stringPool for label name (or -1 for unlabeled) */
    public static final short REDO = 220;

    /** Transliterate operator: Apply tr/// or y/// pattern to string
     * Format: TR_TRANSLITERATE rd searchReg replaceReg modifiersReg targetReg context
     * rd: destination register for result (count of transliterated characters)
     * searchReg: register containing search pattern (RuntimeScalar)
     * replaceReg: register containing replacement pattern (RuntimeScalar)
     * modifiersReg: register containing modifiers string (RuntimeScalar)
     * targetReg: register containing target variable to modify (RuntimeScalar)
     * context: call context (SCALAR/LIST/VOID) */
    public static final short TR_TRANSLITERATE = 221;

    // =================================================================
    // SHIFT AND COMPOUND ASSIGNMENT OPERATORS (222-229) - CONTIGUOUS
    // =================================================================

    /** Left shift: rd = rs1 << rs2 */
    public static final short LEFT_SHIFT = 222;

    /** Right shift: rd = rs1 >> rs2 */
    public static final short RIGHT_SHIFT = 223;

    /** String repetition assignment: target x= value */
    public static final short REPEAT_ASSIGN = 224;

    /** Exponentiation assignment: target **= value */
    public static final short POW_ASSIGN = 225;

    /** Left shift assignment: target <<= value */
    public static final short LEFT_SHIFT_ASSIGN = 226;

    /** Right shift assignment: target >>= value */
    public static final short RIGHT_SHIFT_ASSIGN = 227;

    /** Logical AND assignment: target &&= value */
    public static final short LOGICAL_AND_ASSIGN = 228;

    /** Logical OR assignment: target ||= value */
    public static final short LOGICAL_OR_ASSIGN = 229;

    // =================================================================
    // MANUAL OPCODE ADDITIONS
    // Add new custom opcodes HERE (before LASTOP), not in the generated section below.
    // The GENERATED_OPCODES section is automatically regenerated and will overwrite any manual additions.
    // After adding an opcode here, increment LASTOP to match the highest opcode number.
    // =================================================================

    /** Glob slot access: rd = glob.hashDerefGetNonStrict(key, "main")
     * Used for *X{HASH} style access to glob slots */
    public static final short GLOB_SLOT_GET = 230;

    /** Store via symbolic reference: GlobalVariable.getGlobalVariable(nameReg.toString()).set(valueReg)
     * Format: STORE_SYMBOLIC_SCALAR nameReg valueReg */
    public static final short STORE_SYMBOLIC_SCALAR = 231;

    /** Load via symbolic reference: rd = GlobalVariable.getGlobalVariable(nameReg.toString()).get()
     * Format: LOAD_SYMBOLIC_SCALAR rd nameReg */
    public static final short LOAD_SYMBOLIC_SCALAR = 232;

    /** File test on cached handle '_': rd = FileTestOperator.fileTestLastHandle(operator)
     * Format: FILETEST_LASTHANDLE rd operator_string_idx */
    public static final short FILETEST_LASTHANDLE = 233;

    /** sprintf($format, @args): rd = SprintfOperator.sprintf(formatReg, argsListReg)
     * Format: SPRINTF rd formatReg argsListReg */
    public static final short SPRINTF = 234;

    /** chop($x): rd = StringOperators.chopScalar(scalarReg) - modifies in place
     * Format: CHOP rd scalarReg */
    public static final short CHOP = 235;

    /** Get replacement regex: rd = RuntimeRegex.getReplacementRegex(pattern, replacement, flags)
     * Format: GET_REPLACEMENT_REGEX rd pattern_reg replacement_reg flags_reg */
    public static final short GET_REPLACEMENT_REGEX = 236;

    /** substr with variable args: rd = Operator.substr(ctx, args...)
     * Format: SUBSTR_VAR rd argsListReg ctx */
    public static final short SUBSTR_VAR = 237;

    /** tie($var, $classname, @args): rd = TieOperators.tie(ctx, argsListReg)
     * Format: TIE rd argsListReg context */
    public static final short TIE = 238;

    /** untie($var): rd = TieOperators.untie(ctx, argsListReg)
     * Format: UNTIE rd argsListReg context */
    public static final short UNTIE = 239;

    /** tied($var): rd = TieOperators.tied(ctx, argsListReg)
     * Format: TIED rd argsListReg context */
    public static final short TIED = 240;

    // =================================================================
    // BUILT-IN FUNCTION OPCODES (241-283)
    // Generated by dev/tools/generate_opcode_handlers.pl
    // DO NOT EDIT MANUALLY - regenerate using the tool

    // GENERATED_OPCODES_START

    // scalar binary operations (atan2, eq, ne, lt, le, gt, ge, cmp, etc.)
    public static final short ATAN2 = 241;
    public static final short BINARY_AND = 242;
    public static final short BINARY_OR = 243;
    public static final short BINARY_XOR = 244;
    public static final short EQ = 245;
    public static final short NE = 246;
    public static final short LT = 247;
    public static final short LE = 248;
    public static final short GT = 249;
    public static final short GE = 250;
    public static final short CMP = 251;
    public static final short X = 252;

    // scalar unary operations (chr, ord, abs, sin, cos, lc, uc, etc.)
    public static final short INT = 253;
    public static final short LOG = 254;
    public static final short SQRT = 255;
    public static final short COS = 256;
    public static final short SIN = 257;
    public static final short EXP = 258;
    public static final short ABS = 259;
    public static final short BINARY_NOT = 260;
    public static final short INTEGER_BITWISE_NOT = 261;
    public static final short ORD = 262;
    public static final short ORD_BYTES = 263;
    public static final short OCT = 264;
    public static final short HEX = 265;
    public static final short SRAND = 266;
    public static final short CHR = 267;
    public static final short CHR_BYTES = 268;
    public static final short LENGTH_BYTES = 269;
    public static final short QUOTEMETA = 270;
    public static final short FC = 271;
    public static final short LC = 272;
    public static final short LCFIRST = 273;
    public static final short UC = 274;
    public static final short UCFIRST = 275;
    public static final short SLEEP = 276;
    public static final short TELL = 277;
    public static final short RMDIR = 278;
    public static final short CLOSEDIR = 279;
    public static final short REWINDDIR = 280;
    public static final short TELLDIR = 281;
    public static final short CHDIR = 282;
    public static final short EXIT = 283;
    // GENERATED_OPCODES_END

    // Miscellaneous operators with context-sensitive signatures (284-301)
    // These use absolute numbers to avoid shifting generated opcodes
    public static final short CHMOD = 284;
    public static final short UNLINK = 285;
    public static final short UTIME = 286;
    public static final short RENAME = 287;
    public static final short LINK = 288;
    public static final short READLINK = 289;
    public static final short UMASK = 290;
    public static final short GETC = 291;
    public static final short FILENO = 292;
    public static final short QX = 293;  // backticks
    public static final short SYSTEM = 294;
    public static final short CALLER = 295;
    public static final short EACH = 296;
    public static final short PACK = 297;
    public static final short VEC = 298;
    public static final short LOCALTIME = 299;
    public static final short GMTIME = 300;
    public static final short CRYPT = 301;

    /** Superinstruction: save dynamic level BEFORE makeLocal, then localize global scalar.
     * Atomically: levelReg = getLocalLevel(), rd = makeLocal(stringPool[nameIdx]).
     * The saved pre-push level is used by POP_LOCAL_LEVEL after the loop to fully restore $_.
     * Format: LOCAL_SCALAR_SAVE_LEVEL rd levelReg nameIdx */
    public static final short LOCAL_SCALAR_SAVE_LEVEL = 302;

    /** Restore DynamicVariableManager to a previously saved local level.
     * Matches JVM compiler's DynamicVariableManager.popToLocalLevel(savedLevel) call.
     * Format: POP_LOCAL_LEVEL rs */
    public static final short POP_LOCAL_LEVEL = 303;

    /** Save current DynamicVariableManager local level into register rd.
     * Used to bracket scoped package blocks so local pushes (PUSH_PACKAGE etc) are restored.
     * Format: GET_LOCAL_LEVEL rd */
    public static final short GET_LOCAL_LEVEL = 341;

    /** Superinstruction: foreach loop step for a global loop variable (e.g. $_).
     * Combines: hasNext check, next() into varReg, aliasGlobalVariable(name, varReg), conditional exit.
     * If iterator has next: varReg = next(), aliasGlobalVariable(name, varReg), fall through.
     * If iterator exhausted: jump to exitTarget (absolute address).
     * Format: FOREACH_GLOBAL_NEXT_OR_EXIT varReg iterReg nameIdx exitTarget */
    public static final short FOREACH_GLOBAL_NEXT_OR_EXIT = 304;

    /** Unpack binary data into a list of scalars.
     * Format: UNPACK rd argsReg ctx */
    public static final short UNPACK = 305;

    /** Set current package at runtime (non-scoped: package Foo;).
     * Format: SET_PACKAGE nameIdx
     * Effect: Updates InterpreterState current frame's packageName to stringPool[nameIdx] */
    public static final short SET_PACKAGE = 306;

    // =================================================================
    // I/O OPERATORS (309-329) - truly new ones not already defined above
    // Note: OPEN=165, READLINE=166, TELL=LASTOP+37 already exist
    // =================================================================
    /** close FILEHANDLE: Format: CLOSE rd argsReg ctx */
    public static final short CLOSE = 309;
    /** binmode FILEHANDLE,LAYER: Format: BINMODE rd argsReg ctx */
    public static final short BINMODE = 312;
    /** seek FILEHANDLE,POS,WHENCE: Format: SEEK rd argsReg ctx */
    public static final short SEEK = 313;
    /** eof FILEHANDLE: Format: EOF_OP rd argsReg ctx */
    public static final short EOF_OP = 315;
    /** sysread FILEHANDLE,SCALAR,LENGTH: Format: SYSREAD rd argsReg ctx */
    public static final short SYSREAD = 316;
    /** syswrite FILEHANDLE,SCALAR: Format: SYSWRITE rd argsReg ctx */
    public static final short SYSWRITE = 317;
    /** sysopen FILEHANDLE,FILENAME,MODE: Format: SYSOPEN rd argsReg ctx */
    public static final short SYSOPEN = 318;
    /** socket SOCKET,DOMAIN,TYPE,PROTOCOL: Format: SOCKET rd argsReg ctx */
    public static final short SOCKET = 319;
    /** bind SOCKET,NAME: Format: BIND rd argsReg ctx */
    public static final short BIND = 320;
    /** connect SOCKET,NAME: Format: CONNECT rd argsReg ctx */
    public static final short CONNECT = 321;
    /** listen SOCKET,QUEUESIZE: Format: LISTEN rd argsReg ctx */
    public static final short LISTEN = 322;
    /** write FILEHANDLE: Format: WRITE rd argsReg ctx */
    public static final short WRITE = 323;
    /** formline PICTURE,LIST: Format: FORMLINE rd argsReg ctx */
    public static final short FORMLINE = 324;
    /** printf FILEHANDLE,FORMAT,LIST: Format: PRINTF rd argsReg ctx */
    public static final short PRINTF = 325;
    /** accept NEWSOCKET,GENERICSOCKET: Format: ACCEPT rd argsReg ctx */
    public static final short ACCEPT = 326;
    /** sysseek FILEHANDLE,POS,WHENCE: Format: SYSSEEK rd argsReg ctx */
    public static final short SYSSEEK = 327;
    /** truncate FILEHANDLE,LENGTH: Format: TRUNCATE rd argsReg ctx */
    public static final short TRUNCATE = 328;
    /** read FILEHANDLE,SCALAR,LENGTH: Format: READ rd argsReg ctx */
    public static final short READ = 329;

    /** opendir DIRHANDLE,EXPR: Format: OPENDIR rd argsReg ctx */
    public static final short OPENDIR = 330;
    /** readdir DIRHANDLE: Format: READDIR rd argsReg ctx */
    public static final short READDIR = 331;
    /** seekdir DIRHANDLE,POS: Format: SEEKDIR rd argsReg ctx */
    public static final short SEEKDIR = 332;

    /** Enter scoped package block (package Foo { ...).
     * Format: PUSH_PACKAGE nameIdx
     * Effect: Saves current packageName, sets new one */
    public static final short PUSH_PACKAGE = 307;

    /** Exit scoped package block (closing } of package Foo { ...).
     * Format: POP_PACKAGE
     * Effect: Restores previous packageName */
    public static final short POP_PACKAGE = 308;

    /** Dereference a scalar as a glob: rd = rs.globDerefNonStrict(currentPackage)
     * Used for $ref->** postfix glob deref
     * Format: DEREF_GLOB rd rs nameIdx(currentPackage) */
    public static final short DEREF_GLOB = 333;

    /** Load glob by runtime name (symbolic ref): rd = GlobalVariable.getGlobalIO(normalize(nameReg, pkg))
     * Used for *{"name"} = value typeglob assignment with dynamic name
     * Format: LOAD_GLOB_DYNAMIC rd nameReg pkgIdx */
    public static final short LOAD_GLOB_DYNAMIC = 334;

    /** Scalar dereference (strict refs): rd = rs.scalarDeref()
     * Throws "Can't use string as a SCALAR ref while strict refs in use" for non-refs.
     * Matches JVM path: scalarDeref() — used when strict refs is enabled.
     * Format: DEREF_SCALAR_STRICT rd rs */
    public static final short DEREF_SCALAR_STRICT = 335;

    /** Scalar dereference (no strict refs): rd = rs.scalarDerefNonStrict(pkg)
     * Allows symbolic references (string name -> global variable lookup).
     * Matches JVM path: scalarDerefNonStrict(pkg) — used when strict refs is disabled.
     * Format: DEREF_SCALAR_NONSTRICT rd rs pkgIdx */
    public static final short DEREF_SCALAR_NONSTRICT = 336;

    /** Load v-string literal: rd = new RuntimeScalar(stringPool[index]) with type=VSTRING
     * Mirrors JVM EmitLiteral handling of isVString nodes.
     * Format: LOAD_VSTRING rd strIndex */
    public static final short LOAD_VSTRING = 337;

    /** Convert list/array to its last element in scalar context: rd = list.scalar()
     * A list in scalar context returns its last element (Perl semantics).
     * Contrast with LIST_TO_COUNT which returns list size.
     * Format: LIST_TO_SCALAR rd rs */
    public static final short LIST_TO_SCALAR = 338;

    /** Glob operator: rd = ScalarGlobOperator.evaluate(globId, patternReg, ctx)
     * Mirrors JVM EmitOperator.handleGlobBuiltin — uses a per-call-site globId for
     * scalar-context iteration state across calls.
     * Format: GLOB_OP rd globId patternReg ctx */
    public static final short GLOB_OP = 339;

    /** Execute a file: rd = ModuleOperators.doFile(fileReg, ctx)
     * Implements Perl's do FILE operator.
     * Format: DO_FILE rd fileReg ctx */
    public static final short DO_FILE = 340;

    /** Hash key/value slice: rd = hash.getKeyValueSlice(keys_list)
     * Perl: %hash{keys} returns alternating key/value pairs.
     * Format: HASH_KEYVALUE_SLICE rd hashReg keysListReg */
    public static final short HASH_KEYVALUE_SLICE = 344;

    /** Set $#array = value: Format: SET_ARRAY_LAST_INDEX arrayReg valueReg */
    public static final short SET_ARRAY_LAST_INDEX = 347;

    /** Logical xor: rd = left xor right. Format: XOR_LOGICAL rd rs1 rs2 */
    public static final short XOR_LOGICAL = 348;

    /** Defined-or assignment: rd //= rs. Format: DEFINED_OR_ASSIGN rd rs */
    public static final short DEFINED_OR_ASSIGN = 349;

    /** stat _ (use cached stat buffer): rd = Stat.statLastHandle()
     * Format: STAT_LASTHANDLE rd ctx */
    public static final short STAT_LASTHANDLE = 350;

    /** lstat _ (use cached stat buffer): rd = Stat.lstatLastHandle()
     * Format: LSTAT_LASTHANDLE rd ctx */
    public static final short LSTAT_LASTHANDLE = 351;

    private Opcodes() {} // Utility class - no instantiation
}
