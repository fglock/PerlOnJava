package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.runtimetypes.*;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarEmptyString;

public class Carp extends PerlModuleBase {

    public Carp() {
        super("Carp");
    }

    public static void initialize() {
        Carp carp = new Carp();
        carp.initializeExporter();
        carp.defineExport("EXPORT", "carp", "croak", "confess");
        carp.defineExport("EXPORT_OK", "cluck", "longmess", "shortmess");
        try {
            carp.registerMethod("carp", null);
            carp.registerMethod("croak", null);
            carp.registerMethod("confess", null);
            carp.registerMethod("cluck", null);
            carp.registerMethod("longmess", null);
            carp.registerMethod("shortmess", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Carp method: " + e.getMessage());
        }
    }

    public static RuntimeList carp(RuntimeArray args, int ctx) {
        return warnOrDie(args, ctx, false, false);
    }

    public static RuntimeList croak(RuntimeArray args, int ctx) {
        return warnOrDie(args, ctx, true, false);
    }

    public static RuntimeList confess(RuntimeArray args, int ctx) {
        return warnOrDie(args, ctx, true, true);
    }

    public static RuntimeList cluck(RuntimeArray args, int ctx) {
        return warnOrDie(args, ctx, false, true);
    }

    public static RuntimeList longmess(RuntimeArray args, int ctx) {
        return formatMessage(args, ctx, true);
    }

    public static RuntimeList shortmess(RuntimeArray args, int ctx) {
        return formatMessage(args, ctx, false);
    }

    private static RuntimeList warnOrDie(RuntimeArray args, int ctx, boolean die, boolean backtrace) {
        RuntimeScalar message = args.get(0);
        String formattedMessage = message.toString();

        if (backtrace) {
            // Use ErrorMessageUtil to format the exception with a stack trace
            formattedMessage = ErrorMessageUtil.stringifyException(new Throwable(formattedMessage), 2);
        } else {
            // Use caller to get context information
            RuntimeList callerInfo = RuntimeCode.caller(new RuntimeScalar(1).getList(), RuntimeContextType.LIST);
            if (callerInfo.size() >= 3) {
                String fileName = callerInfo.elements.get(1).toString();
                int line = ((RuntimeScalar) callerInfo.elements.get(2)).getInt();
                formattedMessage += " at " + fileName + " line " + line + "\n";
            }
        }

        if (die) {
            throw new PerlCompilerException(formattedMessage);
        } else {
            WarnDie.warn(new RuntimeScalar(formattedMessage), scalarEmptyString);
            return new RuntimeList();
        }
    }

    private static RuntimeList formatMessage(RuntimeArray args, int ctx, boolean longFormat) {
        RuntimeScalar message = args.get(0);
        String formattedMessage = longFormat
                ? ErrorMessageUtil.stringifyException(new Throwable(message.toString()))
                : message.toString();
        RuntimeList list = new RuntimeList();
        list.elements.add(new RuntimeScalar(formattedMessage));
        return list;
    }
}
