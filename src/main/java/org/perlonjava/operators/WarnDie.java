package org.perlonjava.operators;

import org.perlonjava.perlmodule.Universal;
import org.perlonjava.runtime.*;

import static org.perlonjava.runtime.GlobalVariable.*;
import static org.perlonjava.runtime.SpecialBlock.runEndBlocks;

/**
 * The WarnDie class provides implementations for the warn, die, and exit operations,
 * which are used to issue warnings and terminate execution with an error message,
 * respectively. These operations can trigger custom signal handlers if defined.
 */
public class WarnDie {

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
        String out = message.toString();
        if (out.isEmpty()) {
            RuntimeScalar err = getGlobalVariable("main::@");
            if (err.getDefinedBoolean()) {
                out = err + "\t...caught";
            } else {
                out = "Warning: something's wrong";
            }
        }
        if (!out.endsWith("\n")) {
            out += where.toString();
        }

        RuntimeScalar sig = getGlobalHash("main::SIG").get("__WARN__");
        if (sig.getDefinedBoolean()) {
            RuntimeArray args = new RuntimeScalar(out).getArrayOfAlias();

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
        stderrIO.write(out);

        // System.err.print(out);
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

        if (!message.getFirst().isReference()) {
            // Error message
            String out = message.toString();
            if (out.isEmpty()) {
                out = dieEmptyMessage(oldErr, fileName, lineNumber);
            }
            if (!out.endsWith("\n")) {
                out += where.toString();
            }
            errVariable.set(out);
        } else {
            // Error object
            errVariable.set(message.getFirst());
        }

        RuntimeScalar sig = getGlobalHash("main::SIG").get("__DIE__");
        if (sig.getDefinedBoolean()) {
            RuntimeArray args = new RuntimeScalar(errVariable).getArrayOfAlias();
            return RuntimeCode.apply(sig, args, RuntimeContextType.SCALAR);
        }

        throw new PerlCompilerException(errVariable.toString());
    }

    private static String dieEmptyMessage(RuntimeScalar oldErr, String fileName, int lineNumber) {
        String out;
        if (oldErr.getDefinedBoolean() && !oldErr.toString().isEmpty()) {
            // Check if $@ contains an object reference with a PROPAGATE method
            if (oldErr.isReference()) {

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
                        RuntimeScalar newErr = RuntimeCode.apply(propagateMethod, propagateArgs, RuntimeContextType.SCALAR).scalar();
                        // Replace $@ with the return value
                        getGlobalVariable("main::@").set(newErr);
                        out = newErr.toString();
                    } catch (Exception e) {
                        // If PROPAGATE fails, fall back to appending ...propagated
                        out = oldErr + "\t...propagated";
                    }
                } else {
                    // No PROPAGATE method, append ...propagated
                    out = oldErr + "\t...propagated";
                }
            } else {
                // $@ is not an object reference, append ...propagated
                out = oldErr + "\t...propagated";
            }
        } else {
            // $@ is empty, use "Died"
            out = "Died";
        }
        return out;
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
            System.out.println(errorMessage);
            System.exit(1);
        }
        System.exit(runtimeScalar.getInt());
        return new RuntimeScalar(); // This line will never be reached
    }
}
