package org.perlonjava.backend.bytecode;

import org.perlonjava.runtime.operators.*;
import org.perlonjava.runtime.runtimetypes.*;

import java.util.Map;

/**
 * Handler for rarely-used operations called directly by BytecodeInterpreter.
 *
 * <p>This class provides execution methods for "cold path" operations that are
 * called directly from BytecodeInterpreter helper methods (executeSliceOps,
 * executeListOps, executeSystemOps, executeMiscOps).</p>
 *
 * <h2>Architecture (Phase 5)</h2>
 * <p>As of Phase 5, this class uses <strong>direct method calls</strong> instead of
 * the old SLOWOP_* ID dispatch mechanism:</p>
 *
 * <pre>
 * BytecodeInterpreter:
 *   switch (opcode) {
 *     case Opcodes.DEREF_ARRAY:
 *       return SlowOpcodeHandler.executeDerefArray(bytecode, pc, registers);
 *     case Opcodes.ARRAY_SLICE:
 *       return SlowOpcodeHandler.executeArraySlice(bytecode, pc, registers);
 *     ...
 *   }
 * </pre>
 *
 * <h2>Benefits of Direct Calls</h2>
 * <ul>
 *   <li>Eliminates SLOWOP_* ID indirection (no ID lookup overhead)</li>
 *   <li>Simpler architecture: direct method calls instead of dispatch table</li>
 *   <li>Better JIT optimization: methods can be inlined by C2 compiler</li>
 *   <li>Clearer code: obvious which method handles each opcode</li>
 * </ul>
 *
 * <h2>Performance Characteristics</h2>
 * <p>Each operation adds one static method call (~5ns overhead) compared to
 * inline handling in main execute() method. This is acceptable since these
 * operations are rarely used (typically <1% of execution time).</p>
 *
 * <h2>Adding New Operations</h2>
 * <ol>
 *   <li>Add opcode constant in Opcodes.java (next available opcode)</li>
 *   <li>Add public static executeXxx() method in this class</li>
 *   <li>Add case in BytecodeInterpreter helper method to call executeXxx()</li>
 *   <li>Add disassembly case in InterpretedCode.java</li>
 *   <li>Add emission logic in BytecodeCompiler.java</li>
 * </ol>
 *
 * @see BytecodeInterpreter
 * @see Opcodes
 */
public class SlowOpcodeHandler {

    private static final boolean EVAL_TRACE =
            System.getenv("JPERL_EVAL_TRACE") != null;

    private SlowOpcodeHandler() {
        // Utility class - no instantiation
    }

    // =================================================================
    // SLICE AND DEREFERENCE OPERATIONS
    // =================================================================
    // =================================================================

    private static void evalTrace(String msg) {
        if (EVAL_TRACE) {
            System.err.println("[eval-trace] " + msg);
        }
    }

    /**
     * SLOW_GETPPID: rd = getppid()
     * Format: [SLOW_GETPPID] [rd]
     * Effect: Gets parent process ID
     */
    public static int executeGetppid(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];

        // Java 9+ has ProcessHandle.current().parent()
        // For now, return 1 (init process)
        registers[rd] = new RuntimeScalar(1);
        return pc;
    }

    /**
     * HASH_KEYVALUE_SLICE: rd = hash.getKeyValueSlice(keys_list)
     * Format: [HASH_KEYVALUE_SLICE] [rd] [hashReg] [keysListReg]
     * Effect: Returns alternating key/value pairs for the given keys.
     */
    public static int executeHashKeyValueSlice(
            int[] bytecode,
            int pc,
            RuntimeBase[] registers) {

        int rd = bytecode[pc++];
        int hashReg = bytecode[pc++];
        int keysListReg = bytecode[pc++];

        RuntimeHash hash = (RuntimeHash) registers[hashReg];
        RuntimeList keysList = (RuntimeList) registers[keysListReg];

        registers[rd] = hash.getKeyValueSlice(keysList);
        return pc;
    }

    /**
     * SLOW_FORK: rd = fork()
     * Format: [SLOW_FORK] [rd]
     * Effect: Forks process (not supported in Java)
     */
    public static int executeFork(int[] bytecode, int pc, RuntimeBase[] registers) {
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
    public static int executeSemget(int[] bytecode, int pc, RuntimeBase[] registers) {
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
    public static int executeSemop(int[] bytecode, int pc, RuntimeBase[] registers) {
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
    public static int executeMsgget(int[] bytecode, int pc, RuntimeBase[] registers) {
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
    public static int executeMsgsnd(int[] bytecode, int pc, RuntimeBase[] registers) {
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
    public static int executeMsgrcv(int[] bytecode, int pc, RuntimeBase[] registers) {
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
    public static int executeShmget(int[] bytecode, int pc, RuntimeBase[] registers) {
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
    public static int executeShmread(int[] bytecode, int pc, RuntimeBase[] registers) {
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
    public static int executeShmwrite(int[] bytecode, int pc, RuntimeBase[] registers) {
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
    public static int executeSyscall(int[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int numberReg = bytecode[pc++];
        int argCount = bytecode[pc++];

        // Collect arguments
        RuntimeBase[] args = new RuntimeBase[argCount + 1];
        args[0] = registers[numberReg]; // syscall number
        for (int i = 0; i < argCount; i++) {
            args[i + 1] = registers[bytecode[pc++]];
        }

        // Call SyscallOperator
        registers[rd] = SyscallOperator.syscall(RuntimeContextType.SCALAR, args);
        return pc;
    }

    /**
     * SLOW_EVAL_STRING: rd = eval(rs_string)
     * Format: [SLOW_EVAL_STRING] [rd] [rs_string]
     * Effect: Dynamically evaluates Perl code string
     */
    public static int executeEvalString(
            int[] bytecode,
            int pc,
            RuntimeBase[] registers,
            InterpretedCode code) {

        int rd = bytecode[pc++];
        int stringReg = bytecode[pc++];
        int evalCallContext = RuntimeContextType.SCALAR;
        if (pc < bytecode.length) {
            evalCallContext = bytecode[pc++];
        }
        if (evalCallContext == RuntimeContextType.RUNTIME) evalCallContext = ((RuntimeScalar) registers[2]).getInt();
        int evalSiteIndex = -1;
        if (pc < bytecode.length) {
            evalSiteIndex = bytecode[pc++];
        }

        // Look up per-eval-site variable registry (scope-correct mapping)
        Map<String, Integer> siteRegistry = null;
        if (evalSiteIndex >= 0 && code.evalSiteRegistries != null
                && evalSiteIndex < code.evalSiteRegistries.size()) {
            siteRegistry = code.evalSiteRegistries.get(evalSiteIndex);
        }

        // Look up per-eval-site pragma flags (strict/feature at compile time of eval site)
        int siteStrictOptions = -1;
        int siteFeatureFlags = -1;
        if (evalSiteIndex >= 0 && code.evalSitePragmaFlags != null
                && evalSiteIndex < code.evalSitePragmaFlags.size()) {
            int[] pragmaFlags = code.evalSitePragmaFlags.get(evalSiteIndex);
            siteStrictOptions = pragmaFlags[0];
            siteFeatureFlags = pragmaFlags[1];
        }

        RuntimeBase codeValue = registers[stringReg];
        RuntimeScalar codeScalar;
        if (codeValue instanceof RuntimeScalar) {
            codeScalar = (RuntimeScalar) codeValue;
        } else {
            codeScalar = codeValue.scalar();
        }
        String perlCode = codeScalar.toString();
        evalTrace("EVAL_STRING opcode enter rd=r" + rd + " strReg=r" + stringReg +
                " ctx=" + evalCallContext + " evalSite=" + evalSiteIndex +
                " src=" + (code != null ? code.sourceName : "null"));

        int callContext = evalCallContext;
        if (registers[2] instanceof RuntimeScalar rs) {
            if (callContext == 0 && rs.value != null) {
                callContext = rs.getInt();
            }
        }

        if (callContext == RuntimeContextType.LIST) {
            RuntimeList result = EvalStringHandler.evalStringList(
                    perlCode,
                    code,
                    registers,
                    code.sourceName,
                    code.sourceLine,
                    callContext,
                    siteRegistry,
                    siteStrictOptions,
                    siteFeatureFlags
            );
            registers[rd] = result;
            evalTrace("EVAL_STRING opcode exit LIST stored=" + (registers[rd] != null ? registers[rd].getClass().getSimpleName() : "null") +
                    " scalar=" + result.scalar().toString());
        } else {
            RuntimeScalar result = EvalStringHandler.evalStringList(
                    perlCode,
                    code,
                    registers,
                    code.sourceName,
                    code.sourceLine,
                    callContext,
                    siteRegistry,
                    siteStrictOptions,
                    siteFeatureFlags
            ).scalar();
            registers[rd] = result;
            evalTrace("EVAL_STRING opcode exit SCALAR/VOID stored=" + (registers[rd] != null ? registers[rd].getClass().getSimpleName() : "null") +
                    " val=" + result.toString() + " bool=" + result.getBoolean());
        }
        return pc;
    }

    /**
     * SLOW_SELECT: rd = select(listReg)
     * Format: [SLOW_SELECT] [rd] [rs_list]
     * Effect: Sets or gets the default output filehandle
     */
    public static int executeSelect(
            int[] bytecode,
            int pc,
            RuntimeBase[] registers) {

        int rd = bytecode[pc++];
        int listReg = bytecode[pc++];

        RuntimeList list = (RuntimeList) registers[listReg];

        // Call IOOperator.select() which handles the logic
        RuntimeScalar result = IOOperator.select(
                list,
                RuntimeContextType.SCALAR
        );

        registers[rd] = result;
        return pc;
    }

    /**
     * SLOW_LOAD_GLOB: rd = getGlobalIO(name).createDetachedCopy()
     * Format: [SLOW_LOAD_GLOB] [rd] [name_idx]
     * Effect: Loads a glob/filehandle from global variables
     *
     * <p><b>IMPORTANT:</b> This returns a detached copy of the glob.
     * This is crucial for the {@code do { local *FH; *FH }} pattern used to create anonymous
     * filehandles. The detached copy captures the current IO slot, so that when the local
     * scope ends and restores the global glob, the captured copy retains its IO.
     *
     * <p>If we returned the global glob directly, the copy would only be made when the
     * glob is assigned to a variable (in RuntimeScalar constructor), which happens AFTER
     * the local scope ends, and by that time the IO would have been restored to the original.
     */
    public static int executeLoadGlob(
            int[] bytecode,
            int pc,
            RuntimeBase[] registers,
            InterpretedCode code) {

        int rd = bytecode[pc++];
        int nameIdx = bytecode[pc++];

        String globName = code.stringPool[nameIdx];

        // Call GlobalVariable.getGlobalIO() to get the RuntimeGlob
        RuntimeGlob glob = GlobalVariable.getGlobalIO(globName);

        // Return a detached copy to preserve IO during local scope
        registers[rd] = glob.createDetachedCopy();
        return pc;
    }

    /**
     * LOAD_GLOB_DYNAMIC: rd = GlobalVariable.getGlobalIO(normalize(nameReg, pkg))
     * Format: LOAD_GLOB_DYNAMIC rd nameReg pkgIdx
     * Effect: Loads a glob by runtime name — used for *{"name"} = value symbolic assignment
     */
    public static int executeLoadGlobDynamic(
            int[] bytecode,
            int pc,
            RuntimeBase[] registers,
            InterpretedCode code) {

        int rd = bytecode[pc++];
        int nameReg = bytecode[pc++];
        int pkgIdx = bytecode[pc++];

        String pkg = code.stringPool[pkgIdx];
        String name = registers[nameReg].toString();
        String globalName = NameNormalizer.normalizeVariableName(name, pkg);

        registers[rd] = GlobalVariable.getGlobalIO(globalName);
        return pc;
    }

    /**
     * DEREF_SCALAR_STRICT: rd = rs.scalarDeref()
     * Format: DEREF_SCALAR_STRICT rd rs
     * Matches JVM path: scalarDeref() — throws for non-refs under strict refs.
     */
    public static int executeDerefScalarStrict(
            int[] bytecode,
            int pc,
            RuntimeBase[] registers) {

        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        registers[rd] = registers[rs].scalar().scalarDeref();
        return pc;
    }

    /**
     * DEREF_SCALAR_NONSTRICT: rd = rs.scalarDerefNonStrict(pkg)
     * Format: DEREF_SCALAR_NONSTRICT rd rs pkgIdx
     * Matches JVM path: scalarDerefNonStrict(pkg) — allows symbolic refs.
     */
    public static int executeDerefScalarNonStrict(
            int[] bytecode,
            int pc,
            RuntimeBase[] registers,
            InterpretedCode code) {

        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        int pkgIdx = bytecode[pc++];
        String pkg = code.stringPool[pkgIdx];
        registers[rd] = registers[rs].scalar().scalarDerefNonStrict(pkg);
        return pc;
    }

    /**
     * DEREF_GLOB: rd = rs.globDeref()
     * Format: DEREF_GLOB rd rs pkgIdx
     * Effect: Dereferences a scalar as a glob (strict refs — throws for strings)
     */
    public static int executeDerefGlob(
            int[] bytecode,
            int pc,
            RuntimeBase[] registers,
            InterpretedCode code) {

        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        int pkgIdx = bytecode[pc++];

        registers[rd] = registers[rs].scalar().globDeref();
        return pc;
    }

    /**
     * DEREF_GLOB_NONSTRICT: rd = rs.globDerefNonStrict(pkg)
     * Format: DEREF_GLOB_NONSTRICT rd rs pkgIdx
     * Effect: Dereferences a scalar as a glob (no strict refs — allows symbolic names)
     */
    public static int executeDerefGlobNonStrict(
            int[] bytecode,
            int pc,
            RuntimeBase[] registers,
            InterpretedCode code) {

        int rd = bytecode[pc++];
        int rs = bytecode[pc++];
        int pkgIdx = bytecode[pc++];
        String pkg = code.stringPool[pkgIdx];

        registers[rd] = registers[rs].scalar().globDerefNonStrict(pkg);
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
    public static int executeSleep(
            int[] bytecode,
            int pc,
            RuntimeBase[] registers) {

        int rd = bytecode[pc++];
        int secondsReg = bytecode[pc++];

        // Convert to scalar (handles both RuntimeScalar and RuntimeList)
        RuntimeBase secondsBase = registers[secondsReg];
        RuntimeScalar seconds = secondsBase.scalar();

        // Call Time.sleep()
        RuntimeScalar result = Time.sleep(seconds);

        registers[rd] = result;
        return pc;
    }

    public static int executeAlarm(
            int[] bytecode,
            int pc,
            RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int secondsReg = bytecode[pc++];
        RuntimeScalar seconds = registers[secondsReg].scalar();
        registers[rd] = Time.alarm(RuntimeContextType.SCALAR, seconds);
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
    public static int executeDerefArray(
            int[] bytecode,
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
        if (scalarBase instanceof RuntimeList) {
            RuntimeArray arr = new RuntimeArray();
            scalarBase.addToArray(arr);
            registers[rd] = arr;
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
    public static int executeRetrieveBeginScalar(
            int[] bytecode,
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
    public static int executeRetrieveBeginArray(
            int[] bytecode,
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
    public static int executeRetrieveBeginHash(
            int[] bytecode,
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
    public static int executeLocalScalar(
            int[] bytecode,
            int pc,
            RuntimeBase[] registers,
            InterpretedCode code) {

        int rd = bytecode[pc++];
        int nameIdx = bytecode[pc++];

        String varName = code.stringPool[nameIdx];
        RuntimeScalar result = GlobalRuntimeScalar.makeLocal(varName);

        registers[rd] = result;
        return pc;
    }

    /**
     * SLOWOP_SPLICE: Splice array operation
     * Format: [SLOWOP_SPLICE] [rd] [arrayReg] [argsReg] [context]
     * Effect: rd = Operator.splice(registers[arrayReg], registers[argsReg])
     * In scalar context, returns last element removed (or undef if no elements removed)
     */
    public static int executeSplice(
            int[] bytecode,
            int pc,
            RuntimeBase[] registers) {

        int rd = bytecode[pc++];
        int arrayReg = bytecode[pc++];
        int argsReg = bytecode[pc++];
        int context = bytecode[pc++];
        if (context == RuntimeContextType.RUNTIME) context = ((RuntimeScalar) registers[2]).getInt();

        RuntimeArray array = (RuntimeArray) registers[arrayReg];
        RuntimeList args = (RuntimeList) registers[argsReg];

        // For tied arrays, the user's SPLICE method returns its own scalar value
        // when called in scalar context (typically the last removed element,
        // already unwrapped by the handler). Pass context through so it sees the
        // right wantarray, and trust its scalar return directly rather than
        // taking "last element" of a list that was built from a scalar result.
        boolean isTied = array.type == RuntimeArray.TIED_ARRAY;

        RuntimeList result = Operator.splice(array, args, context);

        // In scalar context, return last element removed (Perl semantics).
        // For tied arrays in scalar context, the handler's scalar return
        // (wrapped as a one-element RuntimeList) is already the caller's value.
        if (context == RuntimeContextType.SCALAR) {
            if (isTied) {
                registers[rd] = result.elements.isEmpty()
                        ? new RuntimeScalar()
                        : result.elements.get(0).scalar();
            } else if (result.elements.isEmpty()) {
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
    public static int executeArraySlice(
            int[] bytecode,
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
    public static int executeReverse(
            int[] bytecode,
            int pc,
            RuntimeBase[] registers) {

        int rd = bytecode[pc++];
        int argsReg = bytecode[pc++];
        int ctx = bytecode[pc++];

        if (ctx == RuntimeContextType.RUNTIME) ctx = ((RuntimeScalar) registers[2]).getInt();

        RuntimeList argsList = (RuntimeList) registers[argsReg];
        RuntimeBase[] args = argsList.elements.toArray(new RuntimeBase[0]);

        RuntimeBase result = Operator.reverse(ctx, args);

        registers[rd] = result;
        return pc;
    }

    /**
     * SLOW_ARRAY_SLICE_SET: array.setSlice(indices, values)
     * Format: [SLOW_ARRAY_SLICE_SET] [arrayReg] [indicesReg] [valuesReg]
     * Effect: Sets array elements at indices to values
     */
    public static int executeArraySliceSet(
            int[] bytecode,
            int pc,
            RuntimeBase[] registers) {

        int arrayReg = bytecode[pc++];
        int indicesReg = bytecode[pc++];
        int valuesReg = bytecode[pc++];

        RuntimeArray array = (RuntimeArray) registers[arrayReg];
        RuntimeList indices = (RuntimeList) registers[indicesReg];
        RuntimeBase valuesBase = registers[valuesReg];

        // Materialize values into a flat list using addToArray (handles PerlRange, etc.)
        RuntimeArray valuesArray = new RuntimeArray();
        valuesBase.addToArray(valuesArray);
        RuntimeList values = new RuntimeList();
        values.elements.addAll(valuesArray.elements);

        array.setSlice(indices, values);

        return pc;
    }

    /**
     * SLOW_SPLIT: rd = Operator.split(pattern, args, ctx)
     * Format: [SLOW_SPLIT] [rd] [patternReg] [argsReg] [ctx]
     * Effect: rd = Operator.split(pattern, args, ctx)
     */
    public static int executeSplit(
            int[] bytecode,
            int pc,
            RuntimeBase[] registers) {

        int rd = bytecode[pc++];
        int patternReg = bytecode[pc++];
        int argsReg = bytecode[pc++];
        int ctx = bytecode[pc++];

        if (ctx == RuntimeContextType.RUNTIME) ctx = ((RuntimeScalar) registers[2]).getInt();

        RuntimeScalar pattern = (RuntimeScalar) registers[patternReg];
        RuntimeBase argsBase = registers[argsReg];
        RuntimeList args = (argsBase instanceof RuntimeList)
                ? (RuntimeList) argsBase
                : new RuntimeList(argsBase.scalar());

        RuntimeList result = Operator.split(pattern, args, ctx);

        registers[rd] = result;
        return pc;
    }

    /**
     * SLOW_EXISTS: rd = exists operand
     * Format: [SLOW_EXISTS] [rd] [operandReg]
     * Effect: rd = exists operand (fallback for non-simple cases like exists &sub)
     */
    public static int executeExists(
            int[] bytecode,
            int pc,
            RuntimeBase[] registers) {

        int rd = bytecode[pc++];
        int operandReg = bytecode[pc++];

        RuntimeBase operand = registers[operandReg];
        RuntimeScalar scalar = (operand instanceof RuntimeScalar)
                ? (RuntimeScalar) operand
                : operand.scalar();

        // Delegate to the same runtime method the JVM backend uses.
        // For CODE types, this checks RuntimeCode.defined().
        // For string types, this looks up in globalCodeRefs.
        registers[rd] = GlobalVariable.existsGlobalCodeRefAsScalar(scalar);
        return pc;
    }

    /**
     * SLOW_DELETE: rd = delete operand
     * Format: [SLOW_DELETE] [rd] [operandReg]
     * Effect: rd = delete operand (fallback for non-simple cases like delete &sub)
     */
    public static int executeDelete(
            int[] bytecode,
            int pc,
            RuntimeBase[] registers) {

        int rd = bytecode[pc++];
        int operandReg = bytecode[pc++];

        RuntimeBase operand = registers[operandReg];
        RuntimeScalar scalar = (operand instanceof RuntimeScalar)
                ? (RuntimeScalar) operand
                : operand.scalar();

        // Delegate to the same runtime method the JVM backend uses.
        registers[rd] = GlobalVariable.deleteGlobalCodeRefAsScalar(scalar);
        return pc;
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
    public static int executeDerefHash(
            int[] bytecode,
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

    public static int executeDerefHashNonStrict(int[] bytecode, int pc, RuntimeBase[] registers, InterpretedCode code) {
        int rd = bytecode[pc++];
        int scalarReg = bytecode[pc++];
        int pkgIdx = bytecode[pc++];
        String pkg = code.stringPool[pkgIdx];
        RuntimeBase scalarBase = registers[scalarReg];
        if (scalarBase instanceof RuntimeHash) {
            registers[rd] = scalarBase;
            return pc;
        }
        RuntimeScalar scalar = scalarBase.scalar();
        registers[rd] = scalar.hashDerefNonStrict(pkg);
        return pc;
    }

    public static int executeDerefArrayNonStrict(int[] bytecode, int pc, RuntimeBase[] registers, InterpretedCode code) {
        int rd = bytecode[pc++];
        int scalarReg = bytecode[pc++];
        int pkgIdx = bytecode[pc++];
        String pkg = code.stringPool[pkgIdx];
        RuntimeBase scalarBase = registers[scalarReg];
        if (scalarBase instanceof RuntimeArray) {
            registers[rd] = scalarBase;
            return pc;
        }
        if (scalarBase instanceof RuntimeList) {
            RuntimeArray arr = new RuntimeArray();
            scalarBase.addToArray(arr);
            registers[rd] = arr;
            return pc;
        }
        RuntimeScalar scalar = scalarBase.scalar();
        registers[rd] = scalar.arrayDerefNonStrict(pkg);
        return pc;
    }

    /**
     * SLOW_HASH_SLICE: rd = hash.getSlice(keys_list)
     * Format: [SLOW_HASH_SLICE] [rd] [hashReg] [keysListReg]
     * Effect: rd = RuntimeArray of values for the given keys
     */
    public static int executeHashSlice(
            int[] bytecode,
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
    public static int executeHashSliceDelete(
            int[] bytecode,
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
     * ARRAY_SLICE_DELETE: rd = array.deleteSlice(indices_list)
     * Format: [ARRAY_SLICE_DELETE] [rd] [arrayReg] [indicesListReg]
     * Effect: rd = RuntimeList of deleted values
     */
    public static int executeArraySliceDelete(
            int[] bytecode,
            int pc,
            RuntimeBase[] registers) {

        int rd = bytecode[pc++];
        int arrayReg = bytecode[pc++];
        int indicesListReg = bytecode[pc++];

        RuntimeArray array = (RuntimeArray) registers[arrayReg];
        RuntimeList indicesList = (RuntimeList) registers[indicesListReg];

        // Delete values for all indices and return them
        RuntimeList deletedValuesList = array.deleteSlice(indicesList);

        // Convert to RuntimeArray for array assignment
        RuntimeArray result = new RuntimeArray();
        for (RuntimeBase elem : deletedValuesList.elements) {
            result.elements.add(elem.scalar());
        }

        registers[rd] = result;
        return pc;
    }

    /**
     * HASH_SLICE_DELETE_LOCAL: rd = hash.deleteLocalSlice(keys_list)
     * Format: [HASH_SLICE_DELETE_LOCAL] [rd] [hashReg] [keysListReg]
     */
    public static int executeHashSliceDeleteLocal(
            int[] bytecode,
            int pc,
            RuntimeBase[] registers) {

        int rd = bytecode[pc++];
        int hashReg = bytecode[pc++];
        int keysListReg = bytecode[pc++];

        RuntimeHash hash = (RuntimeHash) registers[hashReg];
        RuntimeList keysList = (RuntimeList) registers[keysListReg];

        RuntimeList deletedValuesList = hash.deleteLocalSlice(keysList);

        RuntimeArray result = new RuntimeArray();
        for (RuntimeBase elem : deletedValuesList.elements) {
            result.elements.add(elem.scalar());
        }

        registers[rd] = result;
        return pc;
    }

    /**
     * ARRAY_SLICE_DELETE_LOCAL: rd = array.deleteLocalSlice(indices_list)
     * Format: [ARRAY_SLICE_DELETE_LOCAL] [rd] [arrayReg] [indicesListReg]
     */
    public static int executeArraySliceDeleteLocal(
            int[] bytecode,
            int pc,
            RuntimeBase[] registers) {

        int rd = bytecode[pc++];
        int arrayReg = bytecode[pc++];
        int indicesListReg = bytecode[pc++];

        RuntimeArray array = (RuntimeArray) registers[arrayReg];
        RuntimeList indicesList = (RuntimeList) registers[indicesListReg];

        RuntimeList deletedValuesList = array.deleteLocalSlice(indicesList);

        RuntimeArray result = new RuntimeArray();
        for (RuntimeBase elem : deletedValuesList.elements) {
            result.elements.add(elem.scalar());
        }

        registers[rd] = result;
        return pc;
    }

    /**
     * HASH_KV_SLICE_DELETE: rd = hash.deleteKeyValueSlice(keys_list)
     * Format: [HASH_KV_SLICE_DELETE] [rd] [hashReg] [keysListReg]
     * Effect: rd = RuntimeList of alternating keys and deleted values
     */
    public static int executeHashKVSliceDelete(
            int[] bytecode,
            int pc,
            RuntimeBase[] registers) {

        int rd = bytecode[pc++];
        int hashReg = bytecode[pc++];
        int keysListReg = bytecode[pc++];

        RuntimeHash hash = (RuntimeHash) registers[hashReg];
        RuntimeList keysList = (RuntimeList) registers[keysListReg];

        // Delete key-value pairs and return them
        RuntimeList deletedPairsList = hash.deleteKeyValueSlice(keysList);

        // Convert to RuntimeArray for array assignment
        RuntimeArray result = new RuntimeArray();
        for (RuntimeBase elem : deletedPairsList.elements) {
            result.elements.add(elem.scalar());
        }

        registers[rd] = result;
        return pc;
    }

    /**
     * ARRAY_KV_SLICE_DELETE: rd = array.deleteKeyValueSlice(indices_list)
     * Format: [ARRAY_KV_SLICE_DELETE] [rd] [arrayReg] [indicesListReg]
     * Effect: rd = RuntimeList of alternating indices and deleted values
     */
    public static int executeArrayKVSliceDelete(
            int[] bytecode,
            int pc,
            RuntimeBase[] registers) {

        int rd = bytecode[pc++];
        int arrayReg = bytecode[pc++];
        int indicesListReg = bytecode[pc++];

        RuntimeArray array = (RuntimeArray) registers[arrayReg];
        RuntimeList indicesList = (RuntimeList) registers[indicesListReg];

        // Delete key-value pairs and return them
        RuntimeList deletedPairsList = array.deleteKeyValueSlice(indicesList);

        // Convert to RuntimeArray for array assignment
        RuntimeArray result = new RuntimeArray();
        for (RuntimeBase elem : deletedPairsList.elements) {
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
    public static int executeHashSliceSet(
            int[] bytecode,
            int pc,
            RuntimeBase[] registers) {

        int hashReg = bytecode[pc++];
        int keysListReg = bytecode[pc++];
        int valuesListReg = bytecode[pc++];

        RuntimeHash hash = (RuntimeHash) registers[hashReg];
        RuntimeList keysList = (RuntimeList) registers[keysListReg];
        RuntimeBase valuesBase = registers[valuesListReg];

        // Materialize values into a flat list using addToArray (handles PerlRange, etc.)
        RuntimeArray valuesArray = new RuntimeArray();
        valuesBase.addToArray(valuesArray);
        RuntimeList valuesList = new RuntimeList();
        valuesList.elements.addAll(valuesArray.elements);

        // Set all key-value pairs
        hash.setSlice(keysList, valuesList);

        return pc;
    }

    /**
     * SLOWOP_LIST_SLICE_FROM: rd = list[start..]
     * Extract a slice from a list starting at given index to the end
     * Format: [SLOWOP_LIST_SLICE_FROM] [rd] [listReg] [startIndex]
     */
    public static int executeListSliceFrom(
            int[] bytecode,
            int pc,
            RuntimeBase[] registers) {

        int rd = bytecode[pc++];
        int listReg = bytecode[pc++];
        // Read startIndex as single int slot (emitInt writes one slot)
        int startIndex = bytecode[pc++];

        RuntimeBase listBase = registers[listReg];
        RuntimeList sourceList;

        // Convert to RuntimeList if needed
        if (listBase instanceof RuntimeList) {
            sourceList = (RuntimeList) listBase;
        } else if (listBase instanceof RuntimeArray) {
            // Convert RuntimeArray to RuntimeList
            sourceList = new RuntimeList();
            for (RuntimeScalar elem : listBase) {
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
    public static int executeLength(
            int[] bytecode,
            int pc,
            RuntimeBase[] registers) {

        int rd = bytecode[pc++];
        int stringReg = bytecode[pc++];

        RuntimeBase stringBase = registers[stringReg];
        RuntimeScalar stringScalar = stringBase.scalar();

        String str = stringScalar.toString();
        int length = str.codePointCount(0, str.length());
        registers[rd] = new RuntimeScalar(length);

        return pc;
    }

    /**
     * TR_TRANSLITERATE: rd = tr///pattern applied to target
     * Format: [TR_TRANSLITERATE] [rd] [searchReg] [replaceReg] [modifiersReg] [targetReg] [context]
     * Effect: Applies transliteration pattern to target variable
     * Returns: Count of transliterated characters
     */
    public static int executeTransliterate(
            int[] bytecode,
            int pc,
            RuntimeBase[] registers) {

        int rd = bytecode[pc++];
        int searchReg = bytecode[pc++];
        int replaceReg = bytecode[pc++];
        int modifiersReg = bytecode[pc++];
        int targetReg = bytecode[pc++];

        // Read context (1 int slot)
        int context = bytecode[pc++];
        if (context == RuntimeContextType.RUNTIME) context = ((RuntimeScalar) registers[2]).getInt();

        RuntimeScalar search = (RuntimeScalar) registers[searchReg];
        RuntimeScalar replace = (RuntimeScalar) registers[replaceReg];
        RuntimeScalar modifiers = (RuntimeScalar) registers[modifiersReg];
        RuntimeBase targetBase = registers[targetReg];
        if (BytecodeInterpreter.isImmutableProxy(targetBase)) {
            targetBase = BytecodeInterpreter.ensureMutableScalar(targetBase);
            registers[targetReg] = targetBase;
        }
        RuntimeScalar target = (RuntimeScalar) targetBase;

        // Compile and apply transliteration
        RuntimeTransliterate tr = RuntimeTransliterate.compile(search, replace, modifiers);
        registers[rd] = tr.transliterate(target, context);

        return pc;
    }

    /**
     * FILETEST_LASTHANDLE: rd = FileTestOperator.fileTestLastHandle(operator)
     * Format: [FILETEST_LASTHANDLE] [rd] [operator_string_idx]
     * Effect: Applies file test operator to cached filehandle from last stat/lstat
     */
    public static int executeFiletestLastHandle(
            int[] bytecode,
            int pc,
            RuntimeBase[] registers,
            InterpretedCode code) {

        int rd = bytecode[pc++];
        int operatorStrIndex = bytecode[pc++];

        String operator = code.stringPool[operatorStrIndex];

        // Call FileTestOperator.fileTestLastHandle() which uses cached handle
        registers[rd] = FileTestOperator.fileTestLastHandle(operator);

        return pc;
    }

    /**
     * GLOB_SLOT_GET: rd = glob.getGlobSlot(key)
     * Format: [GLOB_SLOT_GET] [rd] [globReg] [keyReg]
     * Effect: Access glob slot (like *X{HASH}) by calling getGlobSlot directly.
     * Uses the runtime current package for symbolic reference resolution if needed.
     */
    public static int executeGlobSlotGet(
            int[] bytecode,
            int pc,
            RuntimeBase[] registers) {

        int rd = bytecode[pc++];
        int globReg = bytecode[pc++];
        int keyReg = bytecode[pc++];

        RuntimeBase globBase = registers[globReg];
        RuntimeScalar key = (RuntimeScalar) registers[keyReg];

        // Convert to scalar if needed
        RuntimeScalar glob = globBase.scalar();

        // Call getGlobSlot directly on the RuntimeGlob for *glob{SLOT} access
        if (glob instanceof RuntimeGlob rg) {
            registers[rd] = rg.getGlobSlot(key);
        } else if (glob.type == RuntimeScalarType.GLOB && glob.value instanceof RuntimeGlob rg) {
            registers[rd] = rg.getGlobSlot(key);
        } else {
            // Fallback: use non-strict hash deref for symbolic glob references
            String pkg = InterpreterState.currentPackage.get().toString();
            registers[rd] = glob.hashDerefGetNonStrict(key, pkg);
        }

        return pc;
    }

    /**
     * CODE_DEREF_NONSTRICT: rd = value.codeDerefNonStrict(package)
     * For &{$name} - looks up code reference from symbolic name using specified package.
     * Format: [CODE_DEREF_NONSTRICT] [rd] [value_reg] [package_string_idx]
     */
    public static int executeCodeDerefNonStrict(int[] bytecode, int pc,
                                                 RuntimeBase[] registers, InterpretedCode code) {
        int rd = bytecode[pc++];
        int valueReg = bytecode[pc++];
        int pkgIdx = bytecode[pc++];

        RuntimeBase valueBase = registers[valueReg];
        RuntimeScalar value = (valueBase instanceof RuntimeScalar)
                ? (RuntimeScalar) valueBase
                : valueBase.scalar();

        String pkg = code.stringPool[pkgIdx];
        registers[rd] = value.codeDerefNonStrict(pkg);

        return pc;
    }

    /**
     * Dispatch MODIFY_*_ATTRIBUTES at runtime for my/state variable declarations.
     * Format: DISPATCH_VAR_ATTRS var_reg const_idx
     * const_idx points to Object[] in constant pool: [packageName, sigil, String[] attrs, fileName, lineNum]
     */
    public static int executeDispatchVarAttrs(int[] bytecode, int pc,
                                               RuntimeBase[] registers, Object[] constants) {
        int varReg = bytecode[pc++];
        int constIdx = bytecode[pc++];

        Object[] data = (Object[]) constants[constIdx];
        String packageName = (String) data[0];
        String sigil = (String) data[1];
        String[] attributes = (String[]) data[2];
        String fileName = (String) data[3];
        int lineNum = (Integer) data[4];

        org.perlonjava.runtime.perlmodule.Attributes.runtimeDispatchModifyVariableAttributes(
                packageName, registers[varReg], sigil, attributes, fileName, lineNum);

        return pc;
    }
}
