package org.perlonjava.operators;

import org.perlonjava.runtime.*;

import static org.perlonjava.runtime.GlobalVariable.*;

/**
 * The WarnDie class provides implementations for the warn and die operations,
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
     * @return A RuntimeDataProvider representing the result of the warning operation.
     */
    public static RuntimeDataProvider warn(RuntimeDataProvider message, RuntimeScalar where) {
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
            RuntimeArray args = new RuntimeArray();
            new RuntimeScalar(out).setArrayOfAlias(args);

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
     * @param value   The error message to be issued.
     * @param message Additional context or location information to append to the message.
     * @return A RuntimeDataProvider representing the result of the die operation.
     * @throws PerlCompilerException if no custom die handler is defined.
     */
    public static RuntimeDataProvider die(RuntimeDataProvider value, RuntimeScalar message) {
        String out = value.toString();
        if (out.isEmpty()) {
            RuntimeScalar err = getGlobalVariable("main::@");
            if (!err.toString().isEmpty()) {
                out = err + "\t...propagated";
            } else {
                out = "Died";
            }
        }
        if (!out.endsWith("\n")) {
            out += message.toString();
        }

        RuntimeScalar sig = getGlobalHash("main::SIG").get("__DIE__");
        if (sig.getDefinedBoolean()) {
            RuntimeArray args = new RuntimeArray();
            new RuntimeScalar(out).setArrayOfAlias(args);
            return RuntimeCode.apply(sig, args, RuntimeContextType.SCALAR);
        }

        throw new PerlCompilerException(out);
    }
}
