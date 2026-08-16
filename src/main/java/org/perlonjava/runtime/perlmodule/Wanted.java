package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.*;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarFalse;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarTrue;

/** Native caller-context and double-return support for Wanted. */
public class Wanted extends PerlModuleBase {
    public Wanted() {
        super("Wanted", false);
    }

    public static void initialize() {
        Wanted module = new Wanted();
        GlobalVariable.getGlobalVariable("Wanted::VERSION").set(new RuntimeScalar("0.1.2"));
        try {
            module.registerMethod("want", null);
            module.registerMethod("context", null);
            module.registerMethod("wantref", null);
            module.registerMethod("howmany", null);
            module.registerMethod("rreturn", null);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Missing Wanted implementation", e);
        }
    }

    private static Integer callerContext() {
        return RuntimeCode.getCallContextAtCallerFrame(1);
    }

    public static RuntimeList context(RuntimeArray args, int ctx) {
        Integer caller = callerContext();
        if (caller == null || caller == RuntimeContextType.VOID) {
            return new RuntimeScalar("VOID").getList();
        }
        if (caller == RuntimeContextType.OBJECT) {
            return new RuntimeScalar("OBJECT").getList();
        }
        return new RuntimeScalar(RuntimeContextType.isListLike(caller) ? "LIST" : "SCALAR").getList();
    }

    public static RuntimeList wantref(RuntimeArray args, int ctx) {
        return new RuntimeScalar("").getList();
    }

    public static RuntimeList howmany(RuntimeArray args, int ctx) {
        Integer caller = callerContext();
        if (caller == null || RuntimeContextType.isListLike(caller)) {
            return new RuntimeScalar().getList();
        }
        return new RuntimeScalar(caller == RuntimeContextType.VOID ? 0 : 1).getList();
    }

    public static RuntimeList want(RuntimeArray args, int ctx) {
        Integer caller = callerContext();
        for (int i = 0; i < args.size(); i++) {
            for (String requestedText : args.get(i).toString().split("\\s+")) {
                boolean negative = requestedText.startsWith("!");
                String requested = negative ? requestedText.substring(1) : requestedText;
                if (requested.equalsIgnoreCase("COUNT")) {
                    return howmany(new RuntimeArray(), ctx);
                }
                boolean matches = switch (requested.toUpperCase()) {
                    case "VOID" -> caller != null && caller == RuntimeContextType.VOID;
                    case "LIST" -> caller != null && RuntimeContextType.isListLike(caller);
                    case "SCALAR" -> caller != null && (caller == RuntimeContextType.SCALAR
                            || caller == RuntimeContextType.LVALUE);
                    case "LVALUE", "ASSIGN" -> caller != null && (caller == RuntimeContextType.LVALUE
                            || caller == RuntimeContextType.LVALUE_LIST);
                    case "RVALUE" -> caller == null || (caller != RuntimeContextType.LVALUE
                            && caller != RuntimeContextType.LVALUE_LIST);
                    case "INFINITY" -> caller != null && RuntimeContextType.isListLike(caller);
                    case "OBJECT" -> caller != null && caller == RuntimeContextType.OBJECT;
                    case "BOOL", "BOOLEAN", "ARRAY", "HASH", "CODE", "GLOB",
                            "REF", "REFSCALAR" -> false;
                    default -> {
                        if (requested.matches("[0-9]+")) {
                            if (caller != null && RuntimeContextType.isListLike(caller)) yield true;
                            int count = caller != null && caller == RuntimeContextType.VOID ? 0 : 1;
                            yield count >= Integer.parseInt(requested);
                        }
                        yield false;
                    }
                };
                if (negative ? matches : !matches) return scalarFalse.getList();
            }
        }
        return scalarTrue.getList();
    }

    public static RuntimeList rreturn(RuntimeArray args, int ctx) {
        RuntimeList value = new RuntimeList();
        if (RuntimeContextType.isListLike(ctx)) {
            value.elements.addAll(args.elements);
        } else if (!args.isEmpty()) {
            value.elements.add(args.get(args.size() - 1));
        }

        RuntimeCode caller = RuntimeCode.getOutermostActiveCodeAtCallerFrame(1);
        if (caller == null) return value;
        throw new PerlNonLocalReturnException(value, caller);
    }
}
