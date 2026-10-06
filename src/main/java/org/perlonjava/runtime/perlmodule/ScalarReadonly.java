package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.*;

/** Java replacement for the Scalar::Readonly XS scalar flag operations. */
public final class ScalarReadonly extends PerlModuleBase {
    public static final String XS_VERSION = "0.03";

    public ScalarReadonly() {
        super("Scalar::Readonly", false);
    }

    public static void initialize() {
        ScalarReadonly module = new ScalarReadonly();
        try {
            module.registerMethod("readonly", "$");
            module.registerMethod("readonly_on", "$");
            module.registerMethod("readonly_off", "$");
            GlobalVariable.getGlobalVariable("Scalar::Readonly::VERSION")
                    .set(new RuntimeScalar(XS_VERSION));
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Missing Scalar::Readonly method", e);
        }
    }

    public static RuntimeList readonly(RuntimeArray args, int ctx) {
        RuntimeScalar scalar = scalarArgument(args, "readonly");
        return new RuntimeScalar(isReadonly(scalar)).getList();
    }

    public static RuntimeList readonly_on(RuntimeArray args, int ctx) {
        RuntimeScalar scalar = scalarArgument(args, "readonly_on");
        if (!isReadonly(scalar)) {
            RuntimeScalar inner = new RuntimeScalar(scalar);
            scalar.type = RuntimeScalarType.READONLY_SCALAR;
            scalar.value = inner;
        }
        return new RuntimeList();
    }

    public static RuntimeList readonly_off(RuntimeArray args, int ctx) {
        RuntimeScalar scalar = scalarArgument(args, "readonly_off");
        if (scalar.type == RuntimeScalarType.READONLY_SCALAR
                && scalar.value instanceof RuntimeScalar inner) {
            scalar.type = inner.type;
            scalar.value = inner.value;
        }
        return new RuntimeList();
    }

    private static RuntimeScalar scalarArgument(RuntimeArray args, String method) {
        if (args.size() != 1 || !(args.getFirst() instanceof RuntimeScalar scalar)) {
            throw new PerlCompilerException("Usage: Scalar::Readonly::" + method + "(SCALAR)");
        }
        return scalar;
    }

    private static boolean isReadonly(RuntimeScalar scalar) {
        return scalar instanceof RuntimeScalarReadOnly
                || scalar.type == RuntimeScalarType.READONLY_SCALAR;
    }
}
