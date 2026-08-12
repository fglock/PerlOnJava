package org.perlonjava.runtime.perlmodule;

import org.perlonjava.app.scriptengine.PerlLanguageProvider;
import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.runtimetypes.*;

import java.util.List;

/** Java backend for the first, deliberately unadvertised Perl threads API tranche. */
public final class Threads extends PerlModuleBase {
    private static final String CLASS = "threads";

    private Threads() {
        super(CLASS, false);
    }

    public static void initialize() {
        Threads module = new Threads();
        try {
            module.registerMethod("_create", null);
            module.registerMethod("_self", null);
            module.registerMethod("_list", null);
            module.registerMethod("_join", null);
            module.registerMethod("_detach", null);
            module.registerMethod("_is_running", null);
            module.registerMethod("_is_joinable", null);
            module.registerMethod("_is_detached", null);
            module.registerMethod("_error", null);
            module.registerMethod("_exit", null);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Missing threads backend method", e);
        }
    }

    public static RuntimeList _create(RuntimeArray args, int ctx) {
        if (args.isEmpty() || args.get(0).type != RuntimeScalarType.CODE) {
            throw new IllegalArgumentException("threads->create requires a CODE reference");
        }
        // BEGIN-time code holds the global compilation lock. A new ithread may
        // need that lock for require/eval before it can signal readiness, so
        // core's test.pl watchdog would deadlock the compiler while polling it.
        // Config still deliberately does not advertise ithreads; preserve the
        // old detached watchdog-stub behavior at this interim boundary.
        if (PerlLanguageProvider.COMPILE_LOCK.isHeldByCurrentThread()) {
            RuntimeHash stub = new RuntimeHash();
            stub.put("tid", new RuntimeScalar(-1));
            stub.put("state", new RuntimeScalar("detached"));
            return ReferenceOperators.bless(stub.createReference(), new RuntimeScalar(CLASS)).getList();
        }
        RuntimeArray threadArgs = new RuntimeArray();
        for (int i = 1; i < args.size(); i++) threadArgs.push(args.get(i));
        PerlThreadControlBlock thread = PerlThreadControlBlock.create(
                PerlRuntime.current(), args.get(0), threadArgs, ctx).start();
        return threadObject(thread.id(), null).getList();
    }

    public static RuntimeList _self(RuntimeArray args, int ctx) {
        return threadObject(PerlRuntime.current().perlThreadId(), null).getList();
    }

    public static RuntimeList _list(RuntimeArray args, int ctx) {
        int filter = args.isEmpty() ? 0 : args.get(0).getInt();
        RuntimeList result = new RuntimeList();
        for (PerlThreadControlBlock thread : PerlRuntime.current().threadRegistry().snapshot()) {
            if (filter == 1 && !thread.isRunning()) continue;
            if (filter == 2 && !thread.isJoinable()) continue;
            result.add(threadObject(thread.id(), thread));
        }
        return result;
    }

    public static RuntimeList _join(RuntimeArray args, int ctx) {
        RuntimeHash object = threadHash(args);
        PerlThreadControlBlock thread = findThread(object);
        if (thread == null) throw new IllegalStateException("Thread is no longer joinable");
        try {
            PerlThreadControlBlock.Completion completion = thread.join();
            object.put("state", new RuntimeScalar("joined"));
            object.put("error", new RuntimeScalar(errorText(completion.error())));
            if (!(completion.value() instanceof RuntimeArray values)) return new RuntimeList();
            List<RuntimeBase> cloned = new RuntimeGraphCloner(
                    thread.childRuntime(), PerlRuntime.current()).cloneRoots(values.elements);
            if (ctx == RuntimeContextType.VOID) return new RuntimeList();
            if (ctx == RuntimeContextType.SCALAR) {
                return cloned.isEmpty() ? RuntimeScalarCache.scalarUndef.getList()
                        : cloned.getLast().scalar().getList();
            }
            return new RuntimeList(cloned.toArray(RuntimeBase[]::new));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Thread join interrupted", e);
        }
    }

    public static RuntimeList _detach(RuntimeArray args, int ctx) {
        RuntimeHash object = threadHash(args);
        PerlThreadControlBlock thread = findThread(object);
        if (thread == null) throw new IllegalStateException("Thread is no longer detachable");
        thread.detach();
        object.put("state", new RuntimeScalar("detached"));
        return threadObjectScalar(args).getList();
    }

    public static RuntimeList _is_running(RuntimeArray args, int ctx) {
        PerlThreadControlBlock thread = findThread(threadHash(args));
        return new RuntimeScalar(thread != null && thread.isRunning() ? 1 : 0).getList();
    }

    public static RuntimeList _is_joinable(RuntimeArray args, int ctx) {
        PerlThreadControlBlock thread = findThread(threadHash(args));
        return new RuntimeScalar(thread != null && thread.isJoinable() ? 1 : 0).getList();
    }

    public static RuntimeList _is_detached(RuntimeArray args, int ctx) {
        RuntimeHash object = threadHash(args);
        PerlThreadControlBlock thread = findThread(object);
        boolean detached = thread != null ? thread.isDetached()
                : "detached".equals(object.get("state").toString());
        return new RuntimeScalar(detached ? 1 : 0).getList();
    }

    public static RuntimeList _error(RuntimeArray args, int ctx) {
        RuntimeHash object = threadHash(args);
        RuntimeScalar saved = object.get("error");
        PerlThreadControlBlock thread = findThread(object);
        if (thread != null && thread.state() == PerlThreadControlBlock.State.FAILED) {
            return new RuntimeScalar(errorText(thread.error())).getList();
        }
        return saved == null ? RuntimeScalarCache.scalarUndef.getList() : saved.getList();
    }

    public static RuntimeList _exit(RuntimeArray args, int ctx) {
        if (PerlRuntime.current().perlThreadId() == 0) {
            throw new IllegalStateException("threads->exit may only be called from a child thread");
        }
        RuntimeArray values = new RuntimeArray(RuntimeScalarCache.scalarUndef);
        throw new PerlThreadExitException(values);
    }

    private static PerlThreadControlBlock findThread(RuntimeHash object) {
        RuntimeScalar tid = object.get("tid");
        return tid == null ? null : PerlRuntime.current().threadRegistry().get(tid.getLong());
    }

    private static RuntimeHash threadHash(RuntimeArray args) {
        RuntimeScalar self = threadObjectScalar(args);
        if (self.type != RuntimeScalarType.HASHREFERENCE || !(self.value instanceof RuntimeHash hash)) {
            throw new IllegalArgumentException("Invalid threads object");
        }
        return hash;
    }

    private static RuntimeScalar threadObjectScalar(RuntimeArray args) {
        if (args.isEmpty()) throw new IllegalArgumentException("Missing threads object");
        return args.get(0);
    }

    private static RuntimeScalar threadObject(long id, PerlThreadControlBlock thread) {
        RuntimeHash object = new RuntimeHash();
        object.put("tid", new RuntimeScalar(id));
        object.put("state", new RuntimeScalar(thread == null ? "self" : "running"));
        return ReferenceOperators.bless(object.createReference(), new RuntimeScalar(CLASS));
    }

    private static String errorText(Throwable error) {
        if (error == null) return "";
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.toString() : message;
    }
}
