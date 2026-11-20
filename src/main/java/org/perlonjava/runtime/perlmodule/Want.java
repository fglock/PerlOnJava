package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.PerlNonLocalReturnException;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarCache;

/** Native call-context predicates for the bundled Want compatibility layer. */
public class Want extends PerlModuleBase {

    public Want() {
        super("Want");
    }

    public static void initialize() {
        Want want = new Want();
        GlobalVariable.getGlobalVariable("Want::VERSION").set(new RuntimeScalar("0.29"));
        try {
            want.registerMethod("want", "@");
            want.registerMethod("rreturn", null);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Missing Want implementation", e);
        }
    }

    public static RuntimeList want(RuntimeArray args, int ctx) {
        Integer callerContext = RuntimeCode.getRawCallContextAtCallerFrame(1);
        for (RuntimeScalar arg : args) {
            for (String requested : arg.toString().trim().split("\\s+")) {
                if (requested.isEmpty()) continue;
                boolean negative = requested.startsWith("!");
                String kind = (negative ? requested.substring(1) : requested).toUpperCase();
                boolean matches = switch (kind) {
                    case "VOID" -> callerContext != null && callerContext == RuntimeContextType.VOID;
                    case "LIST" -> callerContext != null && RuntimeContextType.isListLike(callerContext);
                    case "SCALAR" -> callerContext != null
                            && (callerContext == RuntimeContextType.SCALAR
                            || callerContext == RuntimeContextType.LVALUE
                            || callerContext == RuntimeContextType.OBJECT);
                    case "LVALUE", "ASSIGN" -> callerContext != null
                            && (callerContext == RuntimeContextType.LVALUE
                            || callerContext == RuntimeContextType.LVALUE_LIST);
                    case "OBJECT" -> callerContext != null
                            && callerContext == RuntimeContextType.OBJECT;
                    case "RVALUE" -> callerContext == null
                            || (callerContext != RuntimeContextType.LVALUE
                            && callerContext != RuntimeContextType.LVALUE_LIST);
                    case "INFINITY" -> callerContext != null
                            && RuntimeContextType.isListLike(callerContext);
                    case "BOOL", "BOOLEAN" -> callerContext != null
                            && callerContext == RuntimeContextType.BOOLEAN;
                    case "ARRAY", "HASH", "CODE", "GLOB",
                            "REF", "REFSCALAR" -> false;
                    default -> {
                        if (kind.matches("[0-9]+")) {
                            if (callerContext != null && RuntimeContextType.isListLike(callerContext)) {
                                yield true;
                            }
                            int count = callerContext != null
                                    && callerContext == RuntimeContextType.VOID ? 0 : 1;
                            yield count >= Integer.parseInt(kind);
                        }
                        yield false;
                    }
                };
                if (negative ? matches : !matches) {
                    return RuntimeScalarCache.scalarFalse.getList();
                }
            }
        }
        return RuntimeScalarCache.scalarTrue.getList();
    }

    /** Return from Want's Perl caller, preserving the context of that caller's caller. */
    public static RuntimeList rreturn(RuntimeArray args, int ctx) {
        Integer callerContext = RuntimeCode.getRawCallContextAtCallerFrame(1);
        RuntimeList value = new RuntimeList();
        if (callerContext != null && RuntimeContextType.isListLike(callerContext)) {
            value.elements.addAll(args.elements);
        } else if (callerContext != null && callerContext != RuntimeContextType.VOID
                && !args.isEmpty()) {
            value.elements.add(args.get(args.size() - 1));
        }

        RuntimeCode caller = RuntimeCode.getOutermostActiveCodeAtCallerFrame(1);
        if (caller == null) return value;
        throw new PerlNonLocalReturnException(value, caller);
    }
}
