package org.perlonjava.runtime.operators;

import org.perlonjava.backend.bytecode.InterpreterState;
import org.perlonjava.backend.jvm.ByteCodeSourceMapper;
import org.perlonjava.runtime.perlmodule.Universal;
import org.perlonjava.runtime.perlmodule.Warnings;
import org.perlonjava.runtime.runtimetypes.*;

import java.util.HashMap;

import static org.perlonjava.runtime.runtimetypes.GlobalVariable.*;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarUndef;
import static org.perlonjava.runtime.runtimetypes.SpecialBlock.runEndBlocks;

/**
 * The WarnDie class provides implementations for the warn, die, and exit operations,
 * which are used to issue warnings and terminate execution with an error message,
 * respectively. These operations can trigger custom signal handlers if defined.
 */
public class WarnDie {

    private static Throwable unwrapException(Throwable throwable) {
        Throwable current = throwable;

        // Unwrap RuntimeExceptions that just wrap other exceptions
        while (current instanceof RuntimeException && current.getCause() != null) {
            Throwable cause = current.getCause();

            // Stop unwrapping if we find a meaningful exception
            if (cause instanceof PerlDieException pde) {
                return pde;
            }
            if (cause instanceof PerlCompilerException pc) {
                return pc;
            }
            current = cause;
        }
        return throwable;
    }

    /**
     * Catches the exception in an eval-block.
     * Note: PerlExitException should NEVER be caught by eval{} - it always propagates.
     */
    public static RuntimeScalar catchEval(Throwable e) {
        e = unwrapException(e);
        
        // exit() should never be caught by eval{} - re-throw it
        if (e instanceof PerlExitException) {
            throw (PerlExitException) e;
        }
        
        RuntimeScalar err = getGlobalVariable("main::@");

        if (e instanceof PerlDieException pde) {
            RuntimeBase payload = pde.getPayload();
            if (payload != null) {
                err.set(payload.getFirst());
            }
            // die() already invokes $SIG{__DIE__} (when defined). Perl's eval
            // should not invoke it again while catching the exception.
            return scalarUndef;
        } else {
            if (!(e instanceof PerlCompilerException) || !err.getBoolean()) {
                err.set(new RuntimeScalar(ErrorMessageUtil.stringifyException(e)));
            }
        }

        RuntimeScalar sig = getGlobalHash("main::SIG").get("__DIE__");
        if (sig.getDefinedBoolean()) {
            RuntimeArray args = new RuntimeArray();
            RuntimeArray.push(args, new RuntimeScalar(err));

            RuntimeScalar sigHandler = new RuntimeScalar(sig);

            // Undefine $SIG{__DIE__} before calling the handler to avoid infinite recursion
            int level = DynamicVariableManager.getLocalLevel();
            DynamicVariableManager.pushLocalVariable(sig);

            // Temporarily restore eval depth so $^S reads 1 inside the handler.
            // By the time we reach catchEval(), evalDepth has already been decremented
            // by the eval catch block, but the handler should see $^S=1 since we are
            // conceptually still inside eval (Perl 5 calls the handler before unwinding).
            RuntimeCode.evalDepth++;
            try {
                RuntimeCode.apply(sigHandler, args, RuntimeContextType.SCALAR);
            } catch (Throwable handlerException) {
                // Unwrap RuntimeException to get to the real exception
                handlerException = unwrapException(handlerException);

                // If the handler dies, use its payload as the new error
                if (handlerException instanceof PerlDieException pde) {
                    RuntimeBase handlerPayload = pde.getPayload();
                    if (handlerPayload != null) {
                        err.set(handlerPayload.getFirst());
                    }
                } else {
                    // If the handler throws any other exception, stringify it
                    err.set(new RuntimeScalar(ErrorMessageUtil.stringifyException(handlerException)));
                }
            } finally {
                RuntimeCode.evalDepth--;
                // Restore $SIG{__DIE__}
                DynamicVariableManager.popToLocalLevel(level);
            }
        }
        return scalarUndef;
    }

    /**
     * Issues a warning message. If a custom warning handler is defined in the
     * global %SIG hash under the "__WARN__" key, it will be invoked with the
     * warning message. Otherwise, the message is printed to standard error.
     *
     * @param message The warning message to be issued.
     * @param where   Additional context or location information to append to the message.
     * @return A RuntimeBase representing the result of the warning operation.
     */
    public static RuntimeBase warn(RuntimeBase message, RuntimeScalar where) {
        return warn(message, where, null, 0);
    }

    public static RuntimeBase warn(RuntimeBase message, RuntimeScalar where, String fileName, int lineNumber) {
        RuntimeScalar sig = getGlobalHash("main::SIG").get("__WARN__");

        // If message is empty or just whitespace, handle special cases
        String messageStr = message.toString();
        RuntimeScalar finalMessage;

        if (messageStr.isEmpty()) {
            RuntimeScalar err = getGlobalVariable("main::@");
            // Resolve tied $@ once to avoid double FETCH (Perl 5 fetches $@ exactly once)
            if (err.type == RuntimeScalarType.TIED_SCALAR) {
                err = err.tiedFetch();
            }
            if (err.getDefinedBoolean()) {
                // If $@ is a reference, pass it directly to the signal handler
                if (RuntimeScalarType.isReference(err)) {
                    finalMessage = new RuntimeScalar(err);
                } else {
                    // String in $@, append "...caught" with location
                    String errStr = err.toString();
                    if (!errStr.endsWith("\n")) {
                        errStr += "\n";
                    }
                    finalMessage = new RuntimeScalar(errStr + "\t...caught" + where.toString());
                }
            } else {
                finalMessage = new RuntimeScalar("Warning: something's wrong" + where.toString());
            }
        } else {
            // Handle non-empty message
            if (RuntimeScalarType.isReference(message.getFirst())) {
                // Message is a reference, pass it as-is
                finalMessage = new RuntimeScalar(message.getFirst());
            } else {
                // String message
                String out = messageStr;
                if (!out.endsWith("\n")) {
                    String whereStr = where.toString();
                    // If no explicit location provided, derive from Perl call stack
                    if (whereStr.isEmpty() && (fileName == null || fileName.isEmpty())) {
                        whereStr = getPerlLocationFromStack();
                    }
                    out += whereStr;
                    // Add period and newline if location info was added
                    if (!whereStr.isEmpty()) {
                        out += ".\n";
                    } else if (!out.endsWith("\n")) {
                        out += "\n";
                    }
                }
                finalMessage = new RuntimeScalar(out);
            }
        }

        if (sig.getDefinedBoolean()) {
            RuntimeArray args = new RuntimeArray();
            RuntimeArray.push(args, finalMessage);

            RuntimeScalar sigHandler = new RuntimeScalar(sig);

            // Undefine $SIG{__WARN__} before calling the handler to avoid infinite recursion
            int level = DynamicVariableManager.getLocalLevel();
            DynamicVariableManager.pushLocalVariable(sig);

            RuntimeList res = RuntimeCode.apply(sigHandler, args, RuntimeContextType.SCALAR);

            // Handle TAILCALL with trampoline loop (for goto &sub in __WARN__ handlers)
            while (res.isNonLocalGoto()) {
                RuntimeControlFlowList flow = (RuntimeControlFlowList) res;
                if (flow.getControlFlowType() == ControlFlowType.TAILCALL) {
                    RuntimeScalar codeRef = flow.getTailCallCodeRef();
                    RuntimeArray callArgs = flow.getTailCallArgs();
                    res = RuntimeCode.apply(codeRef, "tailcall", callArgs, RuntimeContextType.SCALAR);
                } else {
                    break;
                }
            }

            // Restore $SIG{__WARN__}
            DynamicVariableManager.popToLocalLevel(level);

            return new RuntimeScalar(1);  // Perl's warn() always returns 1
        }

        // Get the RuntimeIO for STDERR and write the message
        RuntimeIO stderrIO = getGlobalIO("main::STDERR").getRuntimeIO();
        stderrIO.write(finalMessage.toString());

        return new RuntimeScalar(1);  // Perl's warn() always returns 1
    }

    /**
     * Issues a warning message with category checking.
     * - If the warning category is not enabled in the caller's scope, suppresses the warning.
     * - If the warning category is suppressed at runtime (via "no warnings"), suppresses it.
     * - If the warning category is FATAL in the caller's scope, throws an exception instead.
     *
     * @param message  The warning message to be issued.
     * @param where    Additional context or location information.
     * @param category The warning category (e.g., "uninitialized", "numeric").
     * @return A RuntimeBase representing the result of the warning operation.
     */
    public static RuntimeBase warnWithCategory(RuntimeBase message, RuntimeScalar where, String category) {
        return warnWithCategory(message, where, category, null, 0);
    }

    public static RuntimeBase warnWithCategory(RuntimeBase message, RuntimeScalar where, String category,
                                                String fileName, int lineNumber) {
        // Get the warning bits for the current Perl execution context.
        // We scan the Java call stack for the nearest Perl frame (org.perlonjava.anon* or perlmodule)
        // and look up its warning bits in WarningBitsRegistry.
        // NOTE: We do NOT use getCallSiteBits() here because it is a ThreadLocal that
        // persists across function calls and would leak the caller's warning scope into
        // the callee (e.g., pack.t's "use warnings" would leak into test.pl's skip()
        // function even with "local $^W = 0"). callSiteBits is only for caller()[9].
        String warningBits = getWarningBitsFromCurrentContext();
        
        // If no bits from direct stack scan, check the current context stack (pushed on sub entry)
        if (warningBits == null) {
            warningBits = org.perlonjava.runtime.WarningBitsRegistry.getCurrent();
        }
        
        // If warning bits are available, check if this category is enabled
        if (warningBits != null) {
            if (WarningFlags.isEnabledInBits(warningBits, category)) {
                // Category is lexically enabled - check for FATAL
                if (WarningFlags.isFatalInBits(warningBits, category)) {
                    return die(message, where, fileName, lineNumber);
                }
                // Fall through to emit warning
            } else if (!Warnings.isWarnFlagSet()) {
                // Category not lexically enabled AND $^W not set - suppress
                return new RuntimeScalar();
            }
            // If $^W is set, fall through to emit warning even if not lexically enabled
        } else {
            // No bits from caller - fall back to $^W global flag
            if (!Warnings.isWarnFlagSet()) {
                return new RuntimeScalar();
            }
        }
        
        // Check if the category is suppressed at runtime via "no warnings" in current scope
        if (WarningFlags.isWarningSuppressedAtRuntime(category)) {
            return new RuntimeScalar();
        }
        
        // Issue as regular warning
        return warn(message, where, fileName, lineNumber);
    }

    /**
     * Gets warning bits by scanning the Java call stack for Perl frames.
     * This looks for org.perlonjava.anon* and perlmodule classes, which are
     * JVM-compiled Perl code, and returns the first found warning bits.
     * This is more reliable than using caller() which may skip frames.
     *
     * @return The warning bits string, or null if not available
     */
    private static String getWarningBitsFromCurrentContext() {
        Throwable t = new Throwable();
        for (StackTraceElement element : t.getStackTrace()) {
            String className = element.getClassName();
            // Only look at compiled Perl frames for warning bits.
            // Skip perlmodule frames (Java-implemented builtins) — they don't
            // have lexical warning scopes; we want the Perl caller's scope.
            if (className.contains("org.perlonjava.anon")) {
                // Found a Perl frame - look up its warning bits
                String bits = org.perlonjava.runtime.WarningBitsRegistry.get(className);
                if (bits != null) {
                    return bits;
                }
            }
        }
        return null;
    }

    /**
     * Gets the Perl source location string (" at FILE line N") from the current
     * execution context. First checks interpreter frames (which don't create
     * org.perlonjava.anon* JVM stack entries), then falls back to scanning the
     * JVM call stack for compiled Perl frames.
     *
     * @return A location string like " at script.pl line 42", or empty string if not found
     */
    static String getPerlLocationFromStack() {
        // Check interpreter state first - interpreter frames don't create
        // org.perlonjava.anon* JVM stack entries, so JVM stack scanning
        // would skip them and find the wrong (calling Java code) location.
        var frame = InterpreterState.current();
        if (frame != null && frame.code() != null) {
            var pcs = InterpreterState.getPcStack();
            if (!pcs.isEmpty()) {
                int currentPc = pcs.getLast();
                if (frame.code().pcToTokenIndex != null && !frame.code().pcToTokenIndex.isEmpty()) {
                    var pcEntry = frame.code().pcToTokenIndex.floorEntry(currentPc);
                    if (pcEntry != null) {
                        int tokenIndex = pcEntry.getValue();
                        if (frame.code().errorUtil != null) {
                            var loc = frame.code().errorUtil.getSourceLocationAccurate(tokenIndex);
                            if (loc.fileName() != null && !loc.fileName().isEmpty()) {
                                return " at " + loc.fileName() + " line " + loc.lineNumber();
                            }
                        }
                    }
                }
            }
        }

        // Fall back to JVM stack scanning for compiled Perl frames
        // Note: we skip org.perlonjava.runtime.perlmodule frames because those are
        // Java-implemented Perl builtins — we want the Perl caller's location.
        Throwable t = new Throwable();
        HashMap<ByteCodeSourceMapper.SourceLocation, String> locationToClassName = new HashMap<>();
        for (StackTraceElement element : t.getStackTrace()) {
            String className = element.getClassName();
            if (className.contains("org.perlonjava.anon")) {
                var loc = ByteCodeSourceMapper.parseStackTraceElement(element, locationToClassName);
                if (loc != null && loc.sourceFileName() != null && !loc.sourceFileName().isEmpty()) {
                    return " at " + loc.sourceFileName() + " line " + loc.lineNumber();
                }
            }
        }
        return "";
    }

    /**
     * Terminates execution with an error message. If a custom die handler is defined
     * in the global %SIG hash under the "__DIE__" key, it will be invoked with the
     * error message. Otherwise, a PerlCompilerException is thrown.
     *
     * @param message The error message to be issued.
     * @param where   Additional context or location information to append to the message.
     * @return A RuntimeBase representing the result of the die operation.
     * @throws PerlCompilerException if no custom die handler is defined.
     */
    public static RuntimeBase die(RuntimeBase message, RuntimeScalar where) {
        return die(message, where, null, 0);
    }

    public static RuntimeBase die(RuntimeBase message, RuntimeScalar where, String fileName, int lineNumber) {
        var errVariable = getGlobalVariable("main::@");
        var oldErr = new RuntimeScalar(errVariable);

        if (message.toString().isEmpty()) {
            // Empty message
            message = dieEmptyMessage(oldErr, fileName, lineNumber);
        }
        if (!RuntimeScalarType.isReference(message.getFirst())
                || message.getFirst().type == RuntimeScalarType.REGEX) {
            // Error message
            String out = message.toString();
            if (!out.endsWith("\n")) {
                // Add " at FILE line N" location
                out += where.toString();
                // Add filehandle context if available (e.g., ", <DATA> chunk 1")
                String filehandleContext = getFilehandleContext();
                if (filehandleContext != null && !filehandleContext.isEmpty()) {
                    out += filehandleContext;
                }
                // Perl adds a period and newline to die messages
                if (!out.endsWith("\n")) {
                    out += ".\n";
                }
            }
            errVariable.set(out);
        } else {
            // Error object
            errVariable.set(message.getFirst());
        }

        // System.out.println("die :" + errVariable);

        RuntimeScalar sig = getGlobalHash("main::SIG").get("__DIE__");
        if (sig.getDefinedBoolean()) {
            RuntimeScalar sigHandler = new RuntimeScalar(sig);

            // Undefine $SIG{__DIE__} before calling the handler to avoid infinite recursion
            int level = DynamicVariableManager.getLocalLevel();
            DynamicVariableManager.pushLocalVariable(sig);

            RuntimeList res = RuntimeCode.apply(sigHandler, message.getArrayOfAlias(), RuntimeContextType.SCALAR);

            // Handle TAILCALL with trampoline loop (for goto &sub in __DIE__ handlers)
            while (res.isNonLocalGoto()) {
                RuntimeControlFlowList flow = (RuntimeControlFlowList) res;
                if (flow.getControlFlowType() == ControlFlowType.TAILCALL) {
                    RuntimeScalar codeRef = flow.getTailCallCodeRef();
                    RuntimeArray callArgs = flow.getTailCallArgs();
                    res = RuntimeCode.apply(codeRef, "tailcall", callArgs, RuntimeContextType.SCALAR);
                } else {
                    break;
                }
            }

            // Restore $SIG{__DIE__}
            DynamicVariableManager.popToLocalLevel(level);

            throw new PerlDieException(errVariable);
        }

        throw new PerlDieException(errVariable);
    }

    private static RuntimeBase dieEmptyMessage(RuntimeScalar oldErr, String fileName, int lineNumber) {
        if (oldErr.getDefinedBoolean() && !oldErr.toString().isEmpty()) {
            // Check if $@ contains an object reference with a PROPAGATE method
            if (RuntimeScalarType.isReference(oldErr)) {

                // Use Universal.can to check if the object has a PROPAGATE method
                RuntimeArray canArgs = new RuntimeArray();
                RuntimeArray.push(canArgs, oldErr);
                RuntimeArray.push(canArgs, new RuntimeScalar("PROPAGATE"));
                RuntimeList canResult = Universal.can(canArgs, RuntimeContextType.SCALAR);

                if (canResult.size() == 1 && canResult.getFirst().getBoolean()) {
                    // The object has a PROPAGATE method, call it with file and line info
                    RuntimeScalar propagateMethod = canResult.getFirst();
                    RuntimeArray propagateArgs = new RuntimeArray();
                    RuntimeArray.push(propagateArgs, oldErr); // self
                    RuntimeArray.push(propagateArgs, new RuntimeScalar(fileName)); // __FILE__
                    RuntimeArray.push(propagateArgs, new RuntimeScalar(lineNumber)); // __LINE__

                    try {
                        return RuntimeCode.apply(propagateMethod, propagateArgs, RuntimeContextType.SCALAR).scalar();
                    } catch (Exception e) {
                        return oldErr;
                    }
                } else {
                    return oldErr;
                }
            } else {
                // $@ is not an object reference, append ...propagated
                return new RuntimeScalar(oldErr + "\t...propagated");
            }
        } else {
            // $@ is empty, use "Died"
            return new RuntimeScalar("Died");
        }
    }

    /**
     * Terminates the program by throwing PerlExitException.
     * <p>
     * This allows embedded/library use where the calling Java application
     * can catch the exception and continue execution. The CLI (Main.main())
     * catches this and converts it to a real System.exit() call.
     *
     * @param runtimeScalar with exit status
     * @return nothing (always throws)
     * @throws PerlExitException always thrown with the exit code
     */
    public static RuntimeScalar exit(RuntimeScalar runtimeScalar) {
        int exitCode = runtimeScalar.getInt();
        // Set $? to the exit code before running END blocks (Perl 5 semantics).
        // From perlvar: "Inside an END subroutine $? contains the value that
        // is going to be given to exit(). You can modify $? in an END
        // subroutine to change the exit status of your program."
        getGlobalVariable("main::?").set(exitCode);
        try {
            runEndBlocks(false);  // Don't reset $? - we just set it to the exit code
        } catch (Throwable t) {
            RuntimeIO.closeAllHandles();
            String errorMessage = ErrorMessageUtil.stringifyException(t);
            System.err.println(errorMessage);
            throw new PerlExitException(1);
        }
        RuntimeIO.closeAllHandles();
        // Use $? as the final exit code - END blocks may have modified it
        int finalExitCode = getGlobalVariable("main::?").getInt();
        throw new PerlExitException(finalExitCode);
    }

    /**
     * Gets the current filehandle context for error messages.
     * Returns a string like ", <DATA> line 1" or ", <DATA> chunk 1" if a
     * filehandle is currently active. Uses "chunk" when $/ is set to ""
     * (paragraph mode), "line" otherwise. Matches Perl 5's behavior.
     *
     * @return String with filehandle context (including leading ", "), or null if no context
     */
    public static String getFilehandleContext() {
        if (RuntimeIO.getLastAccessedHandle() != null && RuntimeIO.getLastAccessedHandle().currentLineNumber > 0) {
            String handleName = findFilehandleName(RuntimeIO.getLastAccessedHandle());
            if (handleName != null) {
                // Perl 5 uses "line" only when $/ is exactly "\n".
                // Everything else (undef, "", custom separator, ref) uses "chunk".
                String unit = "chunk";
                try {
                    RuntimeScalar rs = GlobalVariable.getGlobalVariable("main::/");
                    if (rs.type != RuntimeScalarType.UNDEF && "\n".equals(rs.toString())) {
                        unit = "line";
                    }
                } catch (Exception ignored) {
                    // Default to "chunk" if we can't read $/
                }
                return ", <" + handleName + "> " + unit + " " + RuntimeIO.getLastAccessedHandle().currentLineNumber;
            }
        }
        return null;
    }

    /**
     * Attempts to find the variable name for a given filehandle.
     * Uses the glob name stored on the RuntimeIO handle.
     *
     * @param handle The RuntimeIO handle to find the name for
     * @return String with the bare handle name (e.g., "DATA", "STDIN"), or null if not found
     */
    private static String findFilehandleName(RuntimeIO handle) {
        if (handle.globName != null && !handle.globName.isEmpty()) {
            // Strip package prefix (e.g., "main::DATA" -> "DATA")
            String name = handle.globName;
            int colonIdx = name.lastIndexOf("::");
            if (colonIdx >= 0 && colonIdx + 2 < name.length()) {
                name = name.substring(colonIdx + 2);
            }
            return name;
        }
        // Fall back to the variable name set during the last readline (e.g., "$f")
        return RuntimeIO.getLastReadlineHandleName();
    }
}