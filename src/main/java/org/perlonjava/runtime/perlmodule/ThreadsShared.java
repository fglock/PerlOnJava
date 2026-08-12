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
            module.registerMethod("_cond_wait", null);
            module.registerMethod("_cond_timedwait", null);
            module.registerMethod("_cond_signal", null);
            module.registerMethod("_cond_broadcast", null);
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

    public static RuntimeList _cond_wait(RuntimeArray args, int ctx) {
        RuntimeScalar condition = required(args, "cond_wait");
        RuntimeScalar lock = args.size() > 1 ? args.get(1) : condition;
        SharedPerlStorage.conditionWait(condition, lock);
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList _cond_timedwait(RuntimeArray args, int ctx) {
        RuntimeScalar condition = required(args, "cond_timedwait");
        if (args.size() < 2) throw new IllegalArgumentException("cond_timedwait requires a timeout");
        RuntimeScalar lock = args.size() > 2 ? args.get(2) : condition;
        boolean signalled = SharedPerlStorage.conditionTimedWait(condition, lock, args.get(1).getDouble());
        return (signalled ? new RuntimeScalar(1) : RuntimeScalarCache.scalarUndef).getList();
    }

    public static RuntimeList _cond_signal(RuntimeArray args, int ctx) {
        if (!SharedPerlStorage.conditionSignal(required(args, "cond_signal"), false)) {
            org.perlonjava.runtime.operators.WarnDie.warn(
                    new RuntimeScalar("cond_signal() called on unlocked variable"), new RuntimeScalar());
        }
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList _cond_broadcast(RuntimeArray args, int ctx) {
        if (!SharedPerlStorage.conditionSignal(required(args, "cond_broadcast"), true)) {
            org.perlonjava.runtime.operators.WarnDie.warn(
                    new RuntimeScalar("cond_broadcast() called on unlocked variable"), new RuntimeScalar());
        }
        return new RuntimeScalar(1).getList();
    }

    private static RuntimeScalar required(RuntimeArray args, String name) {
        if (args.isEmpty()) throw new IllegalArgumentException(name + " requires a value");
        return args.get(0);
    }
}
