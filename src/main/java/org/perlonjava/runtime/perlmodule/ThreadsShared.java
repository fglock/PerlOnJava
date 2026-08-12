package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.*;

/** Java backend for the supported threads::shared core. */
public final class ThreadsShared extends PerlModuleBase {
    private ThreadsShared() { super("threads::shared", false); }

    public static void initialize() {
        ThreadsShared module = new ThreadsShared();
        try {
            module.registerMethod("_share", null);
            module.registerMethod("_is_shared", null);
            module.registerMethod("_shared_clone", null);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Missing threads::shared backend method", e);
        }
    }

    public static RuntimeList _share(RuntimeArray args, int ctx) {
        RuntimeScalar value = required(args, "share");
        SharedPerlStorage.share(value);
        return value.getList();
    }

    public static RuntimeList _is_shared(RuntimeArray args, int ctx) {
        return new RuntimeScalar(SharedPerlStorage.isShared(required(args, "is_shared")) ? 1 : 0).getList();
    }

    public static RuntimeList _shared_clone(RuntimeArray args, int ctx) {
        return SharedPerlStorage.sharedClone(required(args, "shared_clone")).getList();
    }

    private static RuntimeScalar required(RuntimeArray args, String name) {
        if (args.isEmpty()) throw new IllegalArgumentException(name + " requires a value");
        return args.get(0);
    }
}
