package org.perlonjava.interpreter;

import org.perlonjava.runtime.*;

/**
 * Handler for rarely-used operations via SLOW_OP opcode.
 *
 * <p>This class handles "cold path" operations via a single SLOW_OP opcode (87)
 * that takes a slow_op_id byte parameter. This architecture:</p>
 *
 * <ul>
 *   <li>Uses only ONE opcode number for ALL rare operations (efficient!)</li>
 *   <li>Supports 256 slow operations via slow_op_id parameter</li>
 *   <li>Keeps main BytecodeInterpreter switch compact (~10-15% faster)</li>
 *   <li>Improves CPU instruction cache utilization (smaller main loop)</li>
 *   <li>Preserves valuable opcode space (88-255) for future fast operations</li>
 * </ul>
 *
 * <h2>TODO: Deprecate This Class</h2>
 * <p><strong>This architecture is scheduled for deprecation.</strong> All methods in this class
 * are compatible with range-based delegation (like BytecodeInterpreter.executeComparisons()).
 * Future refactoring will:</p>
 * <ul>
 *   <li>Convert SLOWOP operations to direct opcodes (114-127, negative range)</li>
 *   <li>Move methods to BytecodeInterpreter with range-based delegation</li>
 *   <li>Delete this file completely</li>
 * </ul>
 * <p>Benefits: -1 byte per operation, -1 indirection, consistent architecture.</p>
 * <p><strong>See:</strong> dev/prompts/TODO_DEPRECATE_SLOW_OP.md for migration plan</p>
 *
 * <h2>Bytecode Format</h2>
 * <pre>
 * [SLOW_OP] [slow_op_id] [operands...]
 *
 * Example: waitpid
 * [87] [1] [rd] [rs_pid] [rs_flags]
 *  ^    ^
 *  |    |__ SLOWOP_WAITPID (1)
 *  |_______ SLOW_OP opcode (87)
 * </pre>
 *
 * <h2>Performance Trade-off</h2>
 * <p>Adds ~5ns overhead per slow operation but keeps hot path ~10-15% faster
 * by maintaining compact main interpreter loop. Since these operations are
 * rarely used (typically <1% of execution), the trade-off is excellent.</p>
 *
 * <h2>Architecture</h2>
 * <pre>
 * BytecodeInterpreter main loop:
 *   switch (opcode) {
 *     case 0-86: Handle directly (hot path)
 *     case 87:   SlowOpcodeHandler.execute(...)  // All rare operations
 *     case 88-255: Future fast operations
 *   }
 * </pre>
 *
 * <h2>Adding New Slow Operations</h2>
 * <ol>
 *   <li>Add SLOWOP_XXX constant in Opcodes.java (next available ID)</li>
 *   <li>Add case in execute() method below</li>
 *   <li>Implement executeXxx() method</li>
 *   <li>Update getSlowOpName() for disassembler</li>
 * </ol>
 *
 * @see Opcodes#SLOW_OP
 * @see Opcodes#SLOWOP_CHOWN
 * @see BytecodeInterpreter
 * @deprecated Scheduled for removal. Will be replaced with direct opcodes and range-based delegation.
 */
public class SlowOpcodeHandler {

    /**
     * Execute a slow operation.
     *
     * <p>Called from BytecodeInterpreter when SLOW_OP opcode (87) is encountered.
     * Reads slow_op_id byte and dispatches to appropriate handler.</p>
     *
     * @param bytecode  The bytecode array
     * @param pc        The program counter (pointing to slow_op_id byte)
     * @param registers The register file
     * @param code      The InterpretedCode being executed
     * @return The new program counter position (after consuming all operands)
     * @throws RuntimeException if the slow_op_id is unknown or execution fails
     */
    public static int execute(
            short[] bytecode,
            int pc,
            RuntimeBase[] registers,
            InterpretedCode code) {

        // Read slow operation ID
        int slowOpId = bytecode[pc++];

        switch (slowOpId) {
            case Opcodes.SLOWOP_CHOWN:
                return executeChown(bytecode, pc, registers);

            case Opcodes.SLOWOP_WAITPID:
                return executeWaitpid(bytecode, pc, registers);

            case Opcodes.SLOWOP_SETSOCKOPT:
                return executeSetsockopt(bytecode, pc, registers);

            case Opcodes.SLOWOP_GETSOCKOPT:
                return executeGetsockopt(bytecode, pc, registers);

            case Opcodes.SLOWOP_GETPRIORITY:
                return executeGetpriority(bytecode, pc, registers);

            case Opcodes.SLOWOP_SETPRIORITY:
                return executeSetpriority(bytecode, pc, registers);

            case Opcodes.SLOWOP_GETPGRP:
                return executeGetpgrp(bytecode, pc, registers);

            case Opcodes.SLOWOP_SETPGRP:
                return executeSetpgrp(bytecode, pc, registers);

            case Opcodes.SLOWOP_GETPPID:
                return executeGetppid(bytecode, pc, registers);

            case Opcodes.SLOWOP_FORK:
                return executeFork(bytecode, pc, registers);

            case Opcodes.SLOWOP_SEMGET:
                return executeSemget(bytecode, pc, registers);

            case Opcodes.SLOWOP_SEMOP:
                return executeSemop(bytecode, pc, registers);

            case Opcodes.SLOWOP_MSGGET:
                return executeMsgget(bytecode, pc, registers);

            case Opcodes.SLOWOP_MSGSND:
                return executeMsgsnd(bytecode, pc, registers);

            case Opcodes.SLOWOP_MSGRCV:
                return executeMsgrcv(bytecode, pc, registers);

            case Opcodes.SLOWOP_SHMGET:
                return executeShmget(bytecode, pc, registers);

            case Opcodes.SLOWOP_SHMREAD:
                return executeShmread(bytecode, pc, registers);

            case Opcodes.SLOWOP_SHMWRITE:
                return executeShmwrite(bytecode, pc, registers);

            case Opcodes.SLOWOP_SYSCALL:
                return executeSyscall(bytecode, pc, registers);

            case Opcodes.SLOWOP_EVAL_STRING:
                return executeEvalString(bytecode, pc, registers, code);

            case Opcodes.SLOWOP_SELECT:
                return executeSelect(bytecode, pc, registers);

            case Opcodes.SLOWOP_LOAD_GLOB:
                return executeLoadGlob(bytecode, pc, registers, code);

            case Opcodes.SLOWOP_SLEEP:
                return executeSleep(bytecode, pc, registers);

            case Opcodes.SLOWOP_DEREF_ARRAY:
                return executeDerefArray(bytecode, pc, registers);

            case Opcodes.SLOWOP_RETRIEVE_BEGIN_SCALAR:
                return executeRetrieveBeginScalar(bytecode, pc, registers, code);

            case Opcodes.SLOWOP_RETRIEVE_BEGIN_ARRAY:
                return executeRetrieveBeginArray(bytecode, pc, registers, code);

            case Opcodes.SLOWOP_RETRIEVE_BEGIN_HASH:
                return executeRetrieveBeginHash(bytecode, pc, registers, code);

            case Opcodes.SLOWOP_LOCAL_SCALAR:
                return executeLocalScalar(bytecode, pc, registers, code);

            case Opcodes.SLOWOP_SPLICE:
                return executeSplice(bytecode, pc, registers);

            case Opcodes.SLOWOP_ARRAY_SLICE:
                return executeArraySlice(bytecode, pc, registers);

            case Opcodes.SLOWOP_REVERSE:
                return executeReverse(bytecode, pc, registers);

            case Opcodes.SLOWOP_ARRAY_SLICE_SET:
                return executeArraySliceSet(bytecode, pc, registers);

            case Opcodes.SLOWOP_SPLIT:
                return executeSplit(bytecode, pc, registers);

            case Opcodes.SLOWOP_EXISTS:
                return executeExists(bytecode, pc, registers);

            case Opcodes.SLOWOP_DELETE:
                return executeDelete(bytecode, pc, registers);

            case Opcodes.SLOWOP_DEREF_HASH:
                return executeDerefHash(bytecode, pc, registers);

            case Opcodes.SLOWOP_HASH_SLICE:
                return executeHashSlice(bytecode, pc, registers);

            case Opcodes.SLOWOP_HASH_SLICE_DELETE:
                return executeHashSliceDelete(bytecode, pc, registers);

            case Opcodes.SLOWOP_HASH_SLICE_SET:
                return executeHashSliceSet(bytecode, pc, registers);

            case Opcodes.SLOWOP_LIST_SLICE_FROM:
                return executeListSliceFrom(bytecode, pc, registers);

            case Opcodes.SLOWOP_LENGTH:
                return executeLength(bytecode, pc, registers);

            default:
                throw new RuntimeException(
                    "Unknown slow operation ID: " + slowOpId +
                    " at " + code.sourceName + ":" + code.sourceLine
                );
        }
    }

    /**
     * Get human-readable name for slow operation ID.
     * Used by disassembler.
     */
    public static String getSlowOpName(int slowOpId) {
        return switch (slowOpId) {
            case Opcodes.SLOWOP_CHOWN -> "chown";
            case Opcodes.SLOWOP_WAITPID -> "waitpid";
            case Opcodes.SLOWOP_SETSOCKOPT -> "setsockopt";
            case Opcodes.SLOWOP_GETSOCKOPT -> "getsockopt";
            case Opcodes.SLOWOP_GETPRIORITY -> "getpriority";
            case Opcodes.SLOWOP_SETPRIORITY -> "setpriority";
            case Opcodes.SLOWOP_GETPGRP -> "getpgrp";
            case Opcodes.SLOWOP_SETPGRP -> "setpgrp";
            case Opcodes.SLOWOP_GETPPID -> "getppid";
            case Opcodes.SLOWOP_FORK -> "fork";
            case Opcodes.SLOWOP_SEMGET -> "semget";
            case Opcodes.SLOWOP_SEMOP -> "semop";
            case Opcodes.SLOWOP_MSGGET -> "msgget";
            case Opcodes.SLOWOP_MSGSND -> "msgsnd";
            case Opcodes.SLOWOP_MSGRCV -> "msgrcv";
            case Opcodes.SLOWOP_SHMGET -> "shmget";
            case Opcodes.SLOWOP_SHMREAD -> "shmread";
            case Opcodes.SLOWOP_SHMWRITE -> "shmwrite";
            case Opcodes.SLOWOP_SYSCALL -> "syscall";
            case Opcodes.SLOWOP_EVAL_STRING -> "eval";
            case Opcodes.SLOWOP_SELECT -> "select";
            case Opcodes.SLOWOP_LOAD_GLOB -> "load_glob";
            case Opcodes.SLOWOP_SLEEP -> "sleep";
            case Opcodes.SLOWOP_DEREF_ARRAY -> "deref_array";
            case Opcodes.SLOWOP_RETRIEVE_BEGIN_SCALAR -> "retrieve_begin_scalar";
            case Opcodes.SLOWOP_RETRIEVE_BEGIN_ARRAY -> "retrieve_begin_array";
            case Opcodes.SLOWOP_RETRIEVE_BEGIN_HASH -> "retrieve_begin_hash";
            case Opcodes.SLOWOP_LOCAL_SCALAR -> "local_scalar";
            case Opcodes.SLOWOP_SPLICE -> "splice";
            case Opcodes.SLOWOP_ARRAY_SLICE -> "array_slice";
            case Opcodes.SLOWOP_REVERSE -> "reverse";
            case Opcodes.SLOWOP_ARRAY_SLICE_SET -> "array_slice_set";
            case Opcodes.SLOWOP_SPLIT -> "split";
            case Opcodes.SLOWOP_EXISTS -> "exists";
            case Opcodes.SLOWOP_DELETE -> "delete";
            case Opcodes.SLOWOP_DEREF_HASH -> "deref_hash";
            case Opcodes.SLOWOP_HASH_SLICE -> "hash_slice";
            case Opcodes.SLOWOP_HASH_SLICE_DELETE -> "hash_slice_delete";
            case Opcodes.SLOWOP_HASH_SLICE_SET -> "hash_slice_set";
            case Opcodes.SLOWOP_LIST_SLICE_FROM -> "list_slice_from";
            case Opcodes.SLOWOP_LENGTH -> "length";
            default -> "slowop_" + slowOpId;
        };
    }

    // =================================================================
    // IMPLEMENTATION METHODS
    // =================================================================

    /**
     * SLOW_CHOWN: chown(list, uid, gid)
     * Format: [SLOW_CHOWN] [rs_list] [rs_uid] [rs_gid]
     * Effect: Changes ownership of files in list
     */
    private static int executeChown(short[] bytecode, int pc, RuntimeBase[] registers) {
        int listReg = bytecode[pc++];
        int uidReg = bytecode[pc++];
        int gidReg = bytecode[pc++];

        // TODO: Implement chown via JNI or ProcessBuilder
        // For now, throw unsupported operation exception
        throw new UnsupportedOperationException(
            "chown() not yet implemented in interpreter"
        );
    }

    /**
     * SLOW_WAITPID: rd = waitpid(pid, flags)
     * Format: [SLOW_WAITPID] [rd] [rs_pid] [rs_flags]
     * Effect: Waits for child process and returns status
     */
    private static int executeWaitpid(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int pidReg = bytecode[pc++];
        int flagsReg = bytecode[pc++];

        RuntimeScalar pid = (RuntimeScalar) registers[pidReg];
        RuntimeScalar flags = (RuntimeScalar) registers[flagsReg];

        // TODO: Implement waitpid via JNI or ProcessBuilder
        // For now, return -1 (error)
        registers[rd] = new RuntimeScalar(-1);
        return pc;
    }

    /**
     * SLOW_SETSOCKOPT: setsockopt(socket, level, optname, optval)
     * Format: [SLOW_SETSOCKOPT] [rs_socket] [rs_level] [rs_optname] [rs_optval]
     * Effect: Sets socket option
     */
    private static int executeSetsockopt(short[] bytecode, int pc, RuntimeBase[] registers) {
        int socketReg = bytecode[pc++];
        int levelReg = bytecode[pc++];
        int optnameReg = bytecode[pc++];
        int optvalReg = bytecode[pc++];

        // TODO: Implement via java.nio.channels or JNI
        throw new UnsupportedOperationException(
            "setsockopt() not yet implemented in interpreter"
        );
    }

    /**
     * SLOW_GETSOCKOPT: rd = getsockopt(socket, level, optname)
     * Format: [SLOW_GETSOCKOPT] [rd] [rs_socket] [rs_level] [rs_optname]
     * Effect: Gets socket option value
     */
    private static int executeGetsockopt(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int socketReg = bytecode[pc++];
        int levelReg = bytecode[pc++];
        int optnameReg = bytecode[pc++];

        // TODO: Implement via java.nio.channels or JNI
        throw new UnsupportedOperationException(
            "getsockopt() not yet implemented in interpreter"
        );
    }

    /**
     * SLOW_GETPRIORITY: rd = getpriority(which, who)
     * Format: [SLOW_GETPRIORITY] [rd] [rs_which] [rs_who]
     * Effect: Gets process priority
     */
    private static int executeGetpriority(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int whichReg = bytecode[pc++];
        int whoReg = bytecode[pc++];

        // TODO: Implement via JNI
        registers[rd] = new RuntimeScalar(0); // Default priority
        return pc;
    }

    /**
     * SLOW_SETPRIORITY: setpriority(which, who, priority)
     * Format: [SLOW_SETPRIORITY] [rs_which] [rs_who] [rs_priority]
     * Effect: Sets process priority
     */
    private static int executeSetpriority(short[] bytecode, int pc, RuntimeBase[] registers) {
        int whichReg = bytecode[pc++];
        int whoReg = bytecode[pc++];
        int priorityReg = bytecode[pc++];

        // TODO: Implement via JNI
        // For now, silently succeed
        return pc;
    }

    /**
     * SLOW_GETPGRP: rd = getpgrp(pid)
     * Format: [SLOW_GETPGRP] [rd] [rs_pid]
     * Effect: Gets process group ID
     */
    private static int executeGetpgrp(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int pidReg = bytecode[pc++];

        // TODO: Implement via JNI
        registers[rd] = new RuntimeScalar(0);
        return pc;
    }

    /**
     * SLOW_SETPGRP: setpgrp(pid, pgrp)
     * Format: [SLOW_SETPGRP] [rs_pid] [rs_pgrp]
     * Effect: Sets process group ID
     */
    private static int executeSetpgrp(short[] bytecode, int pc, RuntimeBase[] registers) {
        int pidReg = bytecode[pc++];
        int pgrpReg = bytecode[pc++];

        // TODO: Implement via JNI
        return pc;
    }

    /**
     * SLOW_GETPPID: rd = getppid()
     * Format: [SLOW_GETPPID] [rd]
     * Effect: Gets parent process ID
     */
    private static int executeGetppid(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];

        // Java 9+ has ProcessHandle.current().parent()
        // For now, return 1 (init process)
        registers[rd] = new RuntimeScalar(1);
        return pc;
    }

    /**
     * SLOW_FORK: rd = fork()
     * Format: [SLOW_FORK] [rd]
     * Effect: Forks process (not supported in Java)
     */
    private static int executeFork(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];

        // fork() is not supported in Java - return -1 (error)
        // Real implementation would need JNI or native library
        registers[rd] = new RuntimeScalar(-1);
        GlobalVariable.getGlobalVariable("main::!").set("fork not supported on this platform");
        return pc;
    }

    /**
     * SLOW_SEMGET: rd = semget(key, nsems, flags)
     * Format: [SLOW_SEMGET] [rd] [rs_key] [rs_nsems] [rs_flags]
     * Effect: Gets semaphore set identifier
     */
    private static int executeSemget(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int keyReg = bytecode[pc++];
        int nsemsReg = bytecode[pc++];
        int flagsReg = bytecode[pc++];

        // TODO: Implement via JNI or java.nio
        throw new UnsupportedOperationException("semget() not yet implemented");
    }

    /**
     * SLOW_SEMOP: rd = semop(semid, opstring)
     * Format: [SLOW_SEMOP] [rd] [rs_semid] [rs_opstring]
     * Effect: Performs semaphore operations
     */
    private static int executeSemop(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int semidReg = bytecode[pc++];
        int opstringReg = bytecode[pc++];

        // TODO: Implement via JNI
        throw new UnsupportedOperationException("semop() not yet implemented");
    }

    /**
     * SLOW_MSGGET: rd = msgget(key, flags)
     * Format: [SLOW_MSGGET] [rd] [rs_key] [rs_flags]
     * Effect: Gets message queue identifier
     */
    private static int executeMsgget(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int keyReg = bytecode[pc++];
        int flagsReg = bytecode[pc++];

        // TODO: Implement via JNI
        throw new UnsupportedOperationException("msgget() not yet implemented");
    }

    /**
     * SLOW_MSGSND: rd = msgsnd(id, msg, flags)
     * Format: [SLOW_MSGSND] [rd] [rs_id] [rs_msg] [rs_flags]
     * Effect: Sends message to queue
     */
    private static int executeMsgsnd(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int idReg = bytecode[pc++];
        int msgReg = bytecode[pc++];
        int flagsReg = bytecode[pc++];

        // TODO: Implement via JNI
        throw new UnsupportedOperationException("msgsnd() not yet implemented");
    }

    /**
     * SLOW_MSGRCV: rd = msgrcv(id, size, type, flags)
     * Format: [SLOW_MSGRCV] [rd] [rs_id] [rs_size] [rs_type] [rs_flags]
     * Effect: Receives message from queue
     */
    private static int executeMsgrcv(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int idReg = bytecode[pc++];
        int sizeReg = bytecode[pc++];
        int typeReg = bytecode[pc++];
        int flagsReg = bytecode[pc++];

        // TODO: Implement via JNI
        throw new UnsupportedOperationException("msgrcv() not yet implemented");
    }

    /**
     * SLOW_SHMGET: rd = shmget(key, size, flags)
     * Format: [SLOW_SHMGET] [rd] [rs_key] [rs_size] [rs_flags]
     * Effect: Gets shared memory segment
     */
    private static int executeShmget(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int keyReg = bytecode[pc++];
        int sizeReg = bytecode[pc++];
        int flagsReg = bytecode[pc++];

        // TODO: Implement via JNI or java.nio.MappedByteBuffer
        throw new UnsupportedOperationException("shmget() not yet implemented");
    }

    /**
     * SLOW_SHMREAD: rd = shmread(id, pos, size)
     * Format: [SLOW_SHMREAD] [rd] [rs_id] [rs_pos] [rs_size]
     * Effect: Reads from shared memory
     */
    private static int executeShmread(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int idReg = bytecode[pc++];
        int posReg = bytecode[pc++];
        int sizeReg = bytecode[pc++];

        // TODO: Implement via JNI
        throw new UnsupportedOperationException("shmread() not yet implemented");
    }

    /**
     * SLOW_SHMWRITE: shmwrite(id, pos, string)
     * Format: [SLOW_SHMWRITE] [rs_id] [rs_pos] [rs_string]
     * Effect: Writes to shared memory
     */
    private static int executeShmwrite(short[] bytecode, int pc, RuntimeBase[] registers) {
        int idReg = bytecode[pc++];
        int posReg = bytecode[pc++];
        int stringReg = bytecode[pc++];

        // TODO: Implement via JNI
        throw new UnsupportedOperationException("shmwrite() not yet implemented");
    }

    /**
     * SLOW_SYSCALL: rd = syscall(number, args...)
     * Format: [SLOW_SYSCALL] [rd] [rs_number] [arg_count] [rs_arg1] [rs_arg2] ...
     * Effect: Makes arbitrary system call
     */
    private static int executeSyscall(short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int numberReg = bytecode[pc++];
        int argCount = bytecode[pc++];

        // Skip argument registers
        for (int i = 0; i < argCount; i++) {
            pc++; // Skip each argument register
        }

        // TODO: Implement via JNI or Panama FFM API
        throw new UnsupportedOperationException("syscall() not yet implemented");
    }

    /**
     * SLOW_EVAL_STRING: rd = eval(rs_string)
     * Format: [SLOW_EVAL_STRING] [rd] [rs_string]
     * Effect: Dynamically evaluates Perl code string
     */
    private static int executeEvalString(
            short[] bytecode,
            int pc,
            RuntimeBase[] registers,
            InterpretedCode code) {

        int rd = bytecode[pc++];
        int stringReg = bytecode[pc++];

        // Get the code string - handle both RuntimeScalar and RuntimeList (from string interpolation)
        RuntimeBase codeValue = registers[stringReg];
        RuntimeScalar codeScalar;
        if (codeValue instanceof RuntimeScalar) {
            codeScalar = (RuntimeScalar) codeValue;
        } else {
            // Convert list to scalar (e.g., from string interpolation)
            codeScalar = codeValue.scalar();
        }
        String perlCode = codeScalar.toString();

        // Call EvalStringHandler to parse, compile, and execute
        RuntimeScalar result = EvalStringHandler.evalString(
            perlCode,
            code,           // Current InterpretedCode for context
            registers,      // Current registers for variable access
            code.sourceName,
            code.sourceLine
        );

        registers[rd] = result;
        return pc;
    }

    /**
     * SLOW_SELECT: rd = select(listReg)
     * Format: [SLOW_SELECT] [rd] [rs_list]
     * Effect: Sets or gets the default output filehandle
     */
    private static int executeSelect(
            short[] bytecode,
            int pc,
            RuntimeBase[] registers) {

        int rd = bytecode[pc++];
        int listReg = bytecode[pc++];

        RuntimeList list = (RuntimeList) registers[listReg];

        // Call IOOperator.select() which handles the logic
        RuntimeScalar result = org.perlonjava.operators.IOOperator.select(
            list,
            org.perlonjava.runtime.RuntimeContextType.SCALAR
        );

        registers[rd] = result;
        return pc;
    }

    /**
     * SLOW_LOAD_GLOB: rd = getGlobalIO(name)
     * Format: [SLOW_LOAD_GLOB] [rd] [name_idx]
     * Effect: Loads a glob/filehandle from global variables
     */
    private static int executeLoadGlob(
            short[] bytecode,
            int pc,
            RuntimeBase[] registers,
            InterpretedCode code) {

        int rd = bytecode[pc++];
        int nameIdx = bytecode[pc++];

        String globName = code.stringPool[nameIdx];

        // Call GlobalVariable.getGlobalIO() to get the RuntimeGlob
        RuntimeGlob glob = org.perlonjava.runtime.GlobalVariable.getGlobalIO(globName);

        registers[rd] = glob;
        return pc;
    }

    /**
     * Sleep for specified seconds.
     * Format: [rd] [rs_seconds]
     *
     * @param bytecode  The bytecode array
     * @param pc        The program counter
     * @param registers The register file
     * @return The new program counter
     */
    private static int executeSleep(
            short[] bytecode,
            int pc,
            RuntimeBase[] registers) {

        int rd = bytecode[pc++];
        int secondsReg = bytecode[pc++];

        // Convert to scalar (handles both RuntimeScalar and RuntimeList)
        RuntimeBase secondsBase = registers[secondsReg];
        RuntimeScalar seconds = secondsBase.scalar();

        // Call Time.sleep()
        RuntimeScalar result = org.perlonjava.operators.Time.sleep(seconds);

        registers[rd] = result;
        return pc;
    }

    /**
     * Dereference array reference for multidimensional array access.
     * Handles: $array[0][1] which is really $array[0]->[1]
     *
     * @param bytecode  The bytecode array
     * @param pc        Program counter (points after slowOpId)
     * @param registers Register array
     * @return Updated program counter
     */
    private static int executeDerefArray(
            short[] bytecode,
            int pc,
            RuntimeBase[] registers) {

        int rd = bytecode[pc++];
        int scalarReg = bytecode[pc++];

        RuntimeBase scalarBase = registers[scalarReg];

        // If it's already an array, use it directly
        if (scalarBase instanceof RuntimeArray) {
            registers[rd] = scalarBase;
            return pc;
        }

        // Otherwise, dereference as array reference
        RuntimeScalar scalar = scalarBase.scalar();

        // Get the dereferenced array using Perl's array dereference semantics
        RuntimeArray array = scalar.arrayDeref();

        registers[rd] = array;
        return pc;
    }

    /**
     * SLOWOP_RETRIEVE_BEGIN_SCALAR: Retrieve persistent scalar from BEGIN block
     * Format: [SLOWOP_RETRIEVE_BEGIN_SCALAR] [rd] [nameIdx] [begin_id]
     * Effect: rd = PersistentVariable.retrieveBeginScalar(stringPool[nameIdx], begin_id)
     */
    private static int executeRetrieveBeginScalar(
            short[] bytecode,
            int pc,
            RuntimeBase[] registers,
            InterpretedCode code) {

        int rd = bytecode[pc++];
        int nameIdx = bytecode[pc++];
        int beginId = bytecode[pc++];

        String varName = code.stringPool[nameIdx];
        RuntimeScalar result = PersistentVariable.retrieveBeginScalar(varName, beginId);

        registers[rd] = result;
        return pc;
    }

    /**
     * SLOWOP_RETRIEVE_BEGIN_ARRAY: Retrieve persistent array from BEGIN block
     * Format: [SLOWOP_RETRIEVE_BEGIN_ARRAY] [rd] [nameIdx] [begin_id]
     * Effect: rd = PersistentVariable.retrieveBeginArray(stringPool[nameIdx], begin_id)
     */
    private static int executeRetrieveBeginArray(
            short[] bytecode,
            int pc,
            RuntimeBase[] registers,
            InterpretedCode code) {

        int rd = bytecode[pc++];
        int nameIdx = bytecode[pc++];
        int beginId = bytecode[pc++];

        String varName = code.stringPool[nameIdx];
        RuntimeArray result = PersistentVariable.retrieveBeginArray(varName, beginId);

        registers[rd] = result;
        return pc;
    }

    /**
     * SLOWOP_RETRIEVE_BEGIN_HASH: Retrieve persistent hash from BEGIN block
     * Format: [SLOWOP_RETRIEVE_BEGIN_HASH] [rd] [nameIdx] [begin_id]
     * Effect: rd = PersistentVariable.retrieveBeginHash(stringPool[nameIdx], begin_id)
     */
    private static int executeRetrieveBeginHash(
            short[] bytecode,
            int pc,
            RuntimeBase[] registers,
            InterpretedCode code) {

        int rd = bytecode[pc++];
        int nameIdx = bytecode[pc++];
        int beginId = bytecode[pc++];

        String varName = code.stringPool[nameIdx];
        RuntimeHash result = PersistentVariable.retrieveBeginHash(varName, beginId);

        registers[rd] = result;
        return pc;
    }

    /**
     * SLOWOP_LOCAL_SCALAR: Temporarily localize a global scalar variable
     * Format: [SLOWOP_LOCAL_SCALAR] [rd] [nameIdx]
     * Effect: rd = GlobalRuntimeScalar.makeLocal(stringPool[nameIdx])
     */
    private static int executeLocalScalar(
            short[] bytecode,
            int pc,
            RuntimeBase[] registers,
            InterpretedCode code) {

        int rd = bytecode[pc++];
        int nameIdx = bytecode[pc++];

        String varName = code.stringPool[nameIdx];
        RuntimeScalar result = org.perlonjava.runtime.GlobalRuntimeScalar.makeLocal(varName);

        registers[rd] = result;
        return pc;
    }

    /**
     * SLOWOP_SPLICE: Splice array operation
     * Format: [SLOWOP_SPLICE] [rd] [arrayReg] [argsReg] [context]
     * Effect: rd = Operator.splice(registers[arrayReg], registers[argsReg])
     * In scalar context, returns last element removed (or undef if no elements removed)
     */
    private static int executeSplice(
            short[] bytecode,
            int pc,
            RuntimeBase[] registers) {

        int rd = bytecode[pc++];
        int arrayReg = bytecode[pc++];
        int argsReg = bytecode[pc++];
        int context = bytecode[pc++];

        RuntimeArray array = (RuntimeArray) registers[arrayReg];
        RuntimeList args = (RuntimeList) registers[argsReg];

        RuntimeList result = org.perlonjava.operators.Operator.splice(array, args);

        // In scalar context, return last element removed (Perl semantics)
        if (context == RuntimeContextType.SCALAR) {
            if (result.elements.isEmpty()) {
                registers[rd] = new RuntimeScalar();  // undef
            } else {
                registers[rd] = result.elements.get(result.elements.size() - 1);
            }
        } else {
            registers[rd] = result;
        }
        return pc;
    }

    /**
     * SLOWOP_ARRAY_SLICE: Get array slice
     * Format: [SLOWOP_ARRAY_SLICE] [rd] [arrayReg] [indicesReg]
     * Effect: rd = array.getSlice(indices)
     */
    private static int executeArraySlice(
            short[] bytecode,
            int pc,
            RuntimeBase[] registers) {

        int rd = bytecode[pc++];
        int arrayReg = bytecode[pc++];
        int indicesReg = bytecode[pc++];

        RuntimeArray array = (RuntimeArray) registers[arrayReg];
        RuntimeList indices = (RuntimeList) registers[indicesReg];

        RuntimeList result = array.getSlice(indices);

        registers[rd] = result;
        return pc;
    }

    /**
     * SLOW_REVERSE: rd = Operator.reverse(ctx, args...)
     * Format: [SLOW_REVERSE] [rd] [argsReg] [ctx]
     * Effect: rd = Operator.reverse(ctx, args...)
     */
    private static int executeReverse(
            short[] bytecode,
            int pc,
            RuntimeBase[] registers) {

        int rd = bytecode[pc++];
        int argsReg = bytecode[pc++];
        int ctx = bytecode[pc++];

        RuntimeList argsList = (RuntimeList) registers[argsReg];
        RuntimeBase[] args = argsList.elements.toArray(new RuntimeBase[0]);

        RuntimeBase result = org.perlonjava.operators.Operator.reverse(ctx, args);

        registers[rd] = result;
        return pc;
    }

    /**
     * SLOW_ARRAY_SLICE_SET: array.setSlice(indices, values)
     * Format: [SLOW_ARRAY_SLICE_SET] [arrayReg] [indicesReg] [valuesReg]
     * Effect: Sets array elements at indices to values
     */
    private static int executeArraySliceSet(
            short[] bytecode,
            int pc,
            RuntimeBase[] registers) {

        int arrayReg = bytecode[pc++];
        int indicesReg = bytecode[pc++];
        int valuesReg = bytecode[pc++];

        RuntimeArray array = (RuntimeArray) registers[arrayReg];
        RuntimeList indices = (RuntimeList) registers[indicesReg];
        RuntimeList values = (RuntimeList) registers[valuesReg];

        array.setSlice(indices, values);

        return pc;
    }

    /**
     * SLOW_SPLIT: rd = Operator.split(pattern, args, ctx)
     * Format: [SLOW_SPLIT] [rd] [patternReg] [argsReg] [ctx]
     * Effect: rd = Operator.split(pattern, args, ctx)
     */
    private static int executeSplit(
            short[] bytecode,
            int pc,
            RuntimeBase[] registers) {

        int rd = bytecode[pc++];
        int patternReg = bytecode[pc++];
        int argsReg = bytecode[pc++];
        int ctx = bytecode[pc++];

        RuntimeScalar pattern = (RuntimeScalar) registers[patternReg];
        RuntimeList args = (RuntimeList) registers[argsReg];

        RuntimeList result = org.perlonjava.operators.Operator.split(pattern, args, ctx);

        registers[rd] = result;
        return pc;
    }

    /**
     * SLOW_EXISTS: rd = exists operand
     * Format: [SLOW_EXISTS] [rd] [operandReg]
     * Effect: rd = exists operand (fallback for non-simple cases)
     */
    private static int executeExists(
            short[] bytecode,
            int pc,
            RuntimeBase[] registers) {

        int rd = bytecode[pc++];
        int operandReg = bytecode[pc++];

        // For now, throw unsupported - basic exists should use fast path
        throw new UnsupportedOperationException(
            "exists() slow path not yet implemented in interpreter. " +
            "Use simple hash access: exists $hash{key}"
        );
    }

    /**
     * SLOW_DELETE: rd = delete operand
     * Format: [SLOW_DELETE] [rd] [operandReg]
     * Effect: rd = delete operand (fallback for non-simple cases)
     */
    private static int executeDelete(
            short[] bytecode,
            int pc,
            RuntimeBase[] registers) {

        int rd = bytecode[pc++];
        int operandReg = bytecode[pc++];

        // For now, throw unsupported - basic delete should use fast path
        throw new UnsupportedOperationException(
            "delete() slow path not yet implemented in interpreter. " +
            "Use simple hash access: delete $hash{key}"
        );
    }

    /**
     * Dereference hash reference for hashref access.
     * Handles: $hashref->{key} where $hashref contains a hash reference
     *
     * @param bytecode  The bytecode array
     * @param pc        Program counter (points after slowOpId)
     * @param registers Register array
     * @return Updated program counter
     */
    private static int executeDerefHash(
            short[] bytecode,
            int pc,
            RuntimeBase[] registers) {

        int rd = bytecode[pc++];
        int scalarReg = bytecode[pc++];

        RuntimeBase scalarBase = registers[scalarReg];

        // If it's already a hash, use it directly
        if (scalarBase instanceof RuntimeHash) {
            registers[rd] = scalarBase;
            return pc;
        }

        // Otherwise, dereference as hash reference
        RuntimeScalar scalar = scalarBase.scalar();

        // Get the dereferenced hash using Perl's hash dereference semantics
        RuntimeHash hash = scalar.hashDeref();

        registers[rd] = hash;
        return pc;
    }

    /**
     * SLOW_HASH_SLICE: rd = hash.getSlice(keys_list)
     * Format: [SLOW_HASH_SLICE] [rd] [hashReg] [keysListReg]
     * Effect: rd = RuntimeArray of values for the given keys
     */
    private static int executeHashSlice(
            short[] bytecode,
            int pc,
            RuntimeBase[] registers) {

        int rd = bytecode[pc++];
        int hashReg = bytecode[pc++];
        int keysListReg = bytecode[pc++];

        RuntimeHash hash = (RuntimeHash) registers[hashReg];
        RuntimeList keysList = (RuntimeList) registers[keysListReg];

        // Get values for all keys
        RuntimeList valuesList = hash.getSlice(keysList);

        // Convert to RuntimeArray for array assignment
        RuntimeArray result = new RuntimeArray();
        for (RuntimeBase elem : valuesList.elements) {
            result.elements.add(elem.scalar());
        }

        registers[rd] = result;
        return pc;
    }

    /**
     * SLOW_HASH_SLICE_DELETE: rd = hash.deleteSlice(keys_list)
     * Format: [SLOW_HASH_SLICE_DELETE] [rd] [hashReg] [keysListReg]
     * Effect: rd = RuntimeList of deleted values
     */
    private static int executeHashSliceDelete(
            short[] bytecode,
            int pc,
            RuntimeBase[] registers) {

        int rd = bytecode[pc++];
        int hashReg = bytecode[pc++];
        int keysListReg = bytecode[pc++];

        RuntimeHash hash = (RuntimeHash) registers[hashReg];
        RuntimeList keysList = (RuntimeList) registers[keysListReg];

        // Delete values for all keys and return them
        RuntimeList deletedValuesList = hash.deleteSlice(keysList);

        // Convert to RuntimeArray for array assignment
        RuntimeArray result = new RuntimeArray();
        for (RuntimeBase elem : deletedValuesList.elements) {
            result.elements.add(elem.scalar());
        }

        registers[rd] = result;
        return pc;
    }

    /**
     * SLOW_HASH_SLICE_SET: hash.setSlice(keys_list, values_list)
     * Format: [SLOW_HASH_SLICE_SET] [hashReg] [keysListReg] [valuesListReg]
     * Effect: Assign values to multiple hash keys (slice assignment)
     */
    private static int executeHashSliceSet(
            short[] bytecode,
            int pc,
            RuntimeBase[] registers) {

        int hashReg = bytecode[pc++];
        int keysListReg = bytecode[pc++];
        int valuesListReg = bytecode[pc++];

        RuntimeHash hash = (RuntimeHash) registers[hashReg];
        RuntimeList keysList = (RuntimeList) registers[keysListReg];
        RuntimeBase valuesBase = registers[valuesListReg];

        // Convert values to RuntimeList if needed
        RuntimeList valuesList;
        if (valuesBase instanceof RuntimeList) {
            valuesList = (RuntimeList) valuesBase;
        } else if (valuesBase instanceof RuntimeArray) {
            // Convert RuntimeArray to RuntimeList
            valuesList = new RuntimeList();
            for (RuntimeScalar elem : (RuntimeArray) valuesBase) {
                valuesList.elements.add(elem);
            }
        } else {
            // Single value - wrap in list
            valuesList = new RuntimeList();
            valuesList.elements.add(valuesBase.scalar());
        }

        // Set all key-value pairs
        hash.setSlice(keysList, valuesList);

        return pc;
    }

    /**
     * SLOWOP_LIST_SLICE_FROM: rd = list[start..]
     * Extract a slice from a list starting at given index to the end
     * Format: [SLOWOP_LIST_SLICE_FROM] [rd] [listReg] [startIndex]
     */
    private static int executeListSliceFrom(
            short[] bytecode,
            int pc,
            RuntimeBase[] registers) {

        int rd = bytecode[pc++];
        int listReg = bytecode[pc++];
        // Read startIndex as 2 shorts (int = high 16 bits + low 16 bits)
        int high = bytecode[pc++] & 0xFFFF;
        int low = bytecode[pc++] & 0xFFFF;
        int startIndex = (high << 16) | low;

        RuntimeBase listBase = registers[listReg];
        RuntimeList sourceList;

        // Convert to RuntimeList if needed
        if (listBase instanceof RuntimeList) {
            sourceList = (RuntimeList) listBase;
        } else if (listBase instanceof RuntimeArray) {
            // Convert RuntimeArray to RuntimeList
            sourceList = new RuntimeList();
            for (RuntimeScalar elem : (RuntimeArray) listBase) {
                sourceList.elements.add(elem);
            }
        } else {
            // Single value - wrap in list
            sourceList = new RuntimeList();
            sourceList.elements.add(listBase.scalar());
        }

        // Extract slice from startIndex to end
        RuntimeList result = new RuntimeList();
        int size = sourceList.elements.size();

        // Handle negative indices
        if (startIndex < 0) {
            startIndex = size + startIndex;
        }

        // Clamp to valid range
        if (startIndex < 0) {
            startIndex = 0;
        }
        if (startIndex > size) {
            startIndex = size;
        }

        // Copy elements from startIndex to end
        for (int i = startIndex; i < size; i++) {
            result.elements.add(sourceList.elements.get(i));
        }

        registers[rd] = result;
        return pc;
    }

    /**
     * SLOWOP_LENGTH: rd = length(string)
     * Get the length of a string
     * Format: [SLOWOP_LENGTH] [rd] [stringReg]
     */
    private static int executeLength(
            short[] bytecode,
            int pc,
            RuntimeBase[] registers) {

        int rd = bytecode[pc++];
        int stringReg = bytecode[pc++];

        RuntimeBase stringBase = registers[stringReg];
        RuntimeScalar stringScalar = stringBase.scalar();

        int length = stringScalar.toString().length();
        registers[rd] = new RuntimeScalar(length);

        return pc;
    }

    private SlowOpcodeHandler() {
        // Utility class - no instantiation
    }
}
