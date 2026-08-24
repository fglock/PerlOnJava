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
            module.registerMethod("_shared_id", null);
            module.registerMethod("_shared_refcnt", null);
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
        return new RuntimeScalar(SharedPerlStorage.sharedId(required(args, "is_shared"))).getList();
    }

    public static RuntimeList _shared_id(RuntimeArray args, int ctx) {
        RuntimeScalar value = requiredReference(args, "_id");
        return new RuntimeScalar(SharedPerlStorage.sharedId(value)).getList();
    }

    public static RuntimeList _shared_refcnt(RuntimeArray args, int ctx) {
        RuntimeScalar value = requiredReference(args, "_refcnt");
        if (!SharedPerlStorage.isShared(value)) {
            org.perlonjava.runtime.operators.WarnDie.warn(
                    new RuntimeScalar(value + " is not shared"), new RuntimeScalar());
            return RuntimeScalarCache.scalarUndef.getList();
        }
        return new RuntimeScalar(SharedPerlStorage.sharedReferenceCount(value)).getList();
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
            warnUnlockedCondition("cond_signal() called on unlocked variable");
        }
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList _cond_broadcast(RuntimeArray args, int ctx) {
        if (!SharedPerlStorage.conditionSignal(required(args, "cond_broadcast"), true)) {
            warnUnlockedCondition("cond_broadcast() called on unlocked variable");
        }
        return new RuntimeScalar(1).getList();
    }

    /** Emit an XS-style warning using the Perl caller's lexical warning bits. */
    private static void warnUnlockedCondition(String message) {
        if (!Warnings.isCategoryEnabledAtPerlXsCaller("threads")) {
            return;
        }
        RuntimeScalar warning = new RuntimeScalar(message);
        if (Warnings.isCategoryFatalAtPerlXsCaller("threads")) {
            org.perlonjava.runtime.operators.WarnDie.die(
                    warning, new RuntimeScalar("\n"));
        } else {
            org.perlonjava.runtime.operators.WarnDie.warn(
                    warning, new RuntimeScalar(""));
        }
    }

    private static RuntimeScalar required(RuntimeArray args, String name) {
        if (args.isEmpty()) throw new IllegalArgumentException(name + " requires a value");
        return args.get(0);
    }

    private static RuntimeScalar requiredReference(RuntimeArray args, String name) {
        RuntimeScalar value = required(args, name);
        if (!RuntimeScalarType.isReference(value)) {
            throw new IllegalArgumentException("Argument to " + name + " needs to be passed as ref");
        }
        return value;
    }
}
