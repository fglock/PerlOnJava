package org.perlonjava.operators;

import org.perlonjava.perlmodule.Universal;
import org.perlonjava.runtime.*;

import static org.perlonjava.runtime.GlobalVariable.*;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;
import static org.perlonjava.runtime.SpecialBlock.runEndBlocks;

/**
 * The WarnDie class provides implementations for the warn, die, and exit operations,
 * which are used to issue warnings and terminate execution with an error message,
 * respectively. These operations can trigger custom signal handlers if defined.
 */
public class WarnDie {

    private static Exception unwrapException(Exception throwable) {
        Throwable current = throwable;

        // Unwrap RuntimeExceptions that just wrap other exceptions
        while (current instanceof RuntimeException && current.getCause() != null) {
            Throwable cause = current.getCause();

            // Stop unwrapping if we find a meaningful exception
            if (cause instanceof PerlCompilerException pc) {
                return pc;
            }
            current = cause;
        }
        return throwable;
    }

    /**
     *  Catches the exception in an eval-block
     */
    public static RuntimeScalar catchEval(Exception e) {
        e = unwrapException(e);
        if (e instanceof PerlCompilerException && getGlobalVariable("main::@").getBoolean()) {
            // $@ is already set

            // System.out.println("catchEval :" + e);
            // System.out.println("          :" + getGlobalVariable("main::@"));
            return scalarUndef;
        }

        // System.out.println("catchEval : not PerlCompilerException: " + e);
        // e.printStackTrace();

        getGlobalVariable("main::@").set(new RuntimeScalar(ErrorMessageUtil.stringifyException(e)));
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
                    out += where.toString();
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

            RuntimeScalar res = RuntimeCode.apply(sigHandler, args, RuntimeContextType.SCALAR).scalar();

            // Restore $SIG{__WARN__}
            DynamicVariableManager.popToLocalLevel(level);

            return res;
        }

        // Get the RuntimeIO for STDERR and write the message
        RuntimeIO stderrIO = getGlobalIO("main::STDERR").getRuntimeIO();
        stderrIO.write(finalMessage.toString());

        return new RuntimeScalar();
    }

    /**
     * Terminates execution with an error message. If a custom die handler is defined
     * in the global %SIG hash under the "__DIE__" key, it will be invoked with the
     * error message. Otherwise, a PerlCompilerException is thrown.
     *
     * @param message   The error message to be issued.
     * @param where     Additional context or location information to append to the message.
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
        if (!RuntimeScalarType.isReference(message.getFirst())) {
            // Error message
            String out = message.toString();
            if (!out.endsWith("\n")) {
                out += where.toString();
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

            // Restore $SIG{__DIE__}
            DynamicVariableManager.popToLocalLevel(level);

            return res;
        }

        throw new PerlCompilerException(errVariable.toString());
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
     * Terminates the program
     *
     * @param runtimeScalar with exit status
     * @return nothing
     */
    public static RuntimeScalar exit(RuntimeScalar runtimeScalar) {
        try {
            runEndBlocks();
            RuntimeIO.closeAllHandles();
        } catch (Throwable t) {
            RuntimeIO.closeAllHandles();
            String errorMessage = ErrorMessageUtil.stringifyException(t);
            System.err.println(errorMessage);
            System.exit(1);
        }
        System.exit(runtimeScalar.getInt());
        return new RuntimeScalar(); // This line will never be reached
    }
}