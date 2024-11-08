package org.perlonjava.operators;

import org.perlonjava.runtime.*;

import static org.perlonjava.runtime.GlobalContext.getGlobalHash;
import static org.perlonjava.runtime.GlobalContext.getGlobalVariable;

public class WarnDie {
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

            // undefine $SIG{__WARN__} before calling the handler to avoid infinite recursion
            int level = DynamicVariableManager.getLocalLevel();
            DynamicVariableManager.pushLocalVariable(sig);

            RuntimeScalar res = sigHandler.apply(args, RuntimeContextType.SCALAR).scalar();

            // restore $SIG{__WARN__}
            DynamicVariableManager.popToLocalLevel(level);

            return res;
        }

        System.err.print(out);
        return new RuntimeScalar();
    }

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
            return sig.apply(args, RuntimeContextType.SCALAR);
        }

        throw new PerlCompilerException(out);
    }
}
