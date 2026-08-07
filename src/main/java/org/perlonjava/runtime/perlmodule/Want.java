package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
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
            want.registerMethod("want", "$");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Missing Want::want implementation", e);
        }
    }

    public static RuntimeList want(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("want() expects exactly one argument");
        }

        String kind = args.get(0).toString().toUpperCase();
        Integer callerContext = RuntimeCode.getCallContextAt(1);
        boolean matches = switch (kind) {
            case "VOID" -> callerContext != null && callerContext == RuntimeContextType.VOID;
            case "LIST" -> callerContext != null && RuntimeContextType.isListLike(callerContext);
            case "SCALAR" -> callerContext != null
                    && (callerContext == RuntimeContextType.SCALAR
                    || callerContext == RuntimeContextType.LVALUE);
            case "LVALUE", "ASSIGN" -> callerContext != null
                    && (callerContext == RuntimeContextType.LVALUE
                    || callerContext == RuntimeContextType.LVALUE_LIST);
            case "OBJECT", "RVALUE" -> true;
            case "BOOL" -> false;
            default -> false;
        };
        return (matches ? RuntimeScalarCache.scalarTrue : RuntimeScalarCache.scalarFalse).getList();
    }
}
