package org.perlonjava.runtime.perlmodule;

import org.perlonjava.app.scriptengine.PerlLanguageProvider;
import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.runtimetypes.*;

import java.util.List;
import java.util.Locale;
import java.util.Set;

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
            module.registerMethod("_object", null);
            module.registerMethod("_wantarray", null);
            module.registerMethod("_kill", null);
            module.registerMethod("_get_stack_size", null);
            module.registerMethod("_set_stack_size", null);
            module.registerMethod("_set_thread_exit_only", null);
            module.registerMethod("_set_default_exit_only", null);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Missing threads backend method", e);
        }
    }

    public static RuntimeList _create(RuntimeArray args, int ctx) {
        int codeIndex = 0;
        RuntimeHash options = null;
        if (!args.isEmpty() && args.get(0).type == RuntimeScalarType.HASHREFERENCE) {
            options = (RuntimeHash) args.get(0).value;
            codeIndex = 1;
        }
        if (args.size() <= codeIndex) {
            throw new IllegalArgumentException("threads->create requires a CODE reference");
        }
        RuntimeScalar code = args.get(codeIndex);
        if (code.type != RuntimeScalarType.CODE && !RuntimeScalarType.isReference(code)) {
            String name = code.toString();
            if (!name.contains("::")) {
                name = org.perlonjava.backend.bytecode.InterpreterState.currentPackage.get()
                        + "::" + name;
            }
            code = GlobalVariable.getGlobalCodeRef(name);
        }
        RuntimeArray threadArgs = new RuntimeArray();
        for (int i = codeIndex + 1; i < args.size(); i++) threadArgs.push(args.get(i));
        PerlRuntime parent = PerlRuntime.current();
        int threadContext = creationContext(options, ctx);
        // Perl accepts a reference-valued entry here and creates the ithread;
        // the child then fails with "Not a CODE reference". Keeping creation
        // asynchronous preserves join/error lifecycle and core diagnostics.
        // BEGIN-time code holds the global compilation lock. A new ithread may
        // need that lock for require/eval before it can signal readiness, so
        // core's test.pl watchdog would deadlock the compiler while polling it.
        // Preserve the detached watchdog behavior at this compile-time boundary;
        // starting a real child here could deadlock on the enclosing compile lock.
        if (PerlLanguageProvider.COMPILE_LOCK.isHeldByCurrentThread()) {
            RuntimeHash stub = new RuntimeHash();
            stub.put("tid", new RuntimeScalar(parent.threadRegistry().allocateId()));
            stub.put("state", new RuntimeScalar("compile-stub"));
            stub.put("context", new RuntimeScalar(threadContext));
            try {
                RuntimeList result = RuntimeCode.apply(code, threadArgs, threadContext);
                stub.put("result", new RuntimeArray(result).createReference());
            } catch (Throwable failure) {
                PerlThreadExitException threadExit = findThreadExit(failure);
                if (threadExit != null) {
                    stub.put("result", threadExit.values().createReference());
                } else {
                    stub.put("error", new RuntimeScalar(errorText(failure)));
                }
            }
            return ReferenceOperators.bless(stub.createReference(), new RuntimeScalar(CLASS)).getList();
        }
        // Perl compiles an anonymous thread entry in the parent. In
        // particular, a malformed qr// inside that CODE must fail in the
        // surrounding eval before an ithread exists. Deferring the entry CV
        // until child execution lost $@ and created a large direct-vs-thread
        // diagnostic delta in regexp_qr_embed_thr.t.
        if (code.type == RuntimeScalarType.CODE
                && code.value instanceof RuntimeCode runtimeCode
                && runtimeCode.compilerSupplier != null) {
            runtimeCode.compilerSupplier.get();
        }
        long stackSize = optionLong(options, "stack_size",
                optionLong(options, "stack", parent.defaultPerlThreadStackSize()));
        boolean exitOnly = optionExitOnly(options, parent.defaultPerlThreadExitOnly());
        PerlThreadControlBlock thread;
        try {
            thread = PerlThreadControlBlock.create(
                    parent, code, threadArgs, threadContext, stackSize, exitOnly).start();
        } finally {
            releaseTemporaryEntryCode(code);
        }
        return threadObject(thread.id(), null).getList();
    }

    /**
     * Drop the parent half of an inline anonymous thread entry after its graph
     * has been cloned. A CODE value stored in a lexical/package scalar has a
     * real refCount owner and must remain callable; a direct {@code async {}}
     * argument has neither and otherwise keeps its captured pads alive until
     * top-level destruction.
     */
    private static void releaseTemporaryEntryCode(RuntimeScalar code) {
        if (code == null || code.globalCodeRefFqn != null
                || MyVarCleanupStack.isRegistered(code)
                || !(code.value instanceof RuntimeCode runtimeCode)) {
            return;
        }
        runtimeCode.releaseCaptures();
    }

    public static RuntimeList _self(RuntimeArray args, int ctx) {
        return threadObject(PerlRuntime.current().perlThreadId(), null).getList();
    }

    public static RuntimeList _list(RuntimeArray args, int ctx) {
        int filter = args.isEmpty() ? 0 : args.get(0).getInt();
        RuntimeList result = new RuntimeList();
        for (PerlThreadControlBlock thread : PerlRuntime.current().threadRegistry().snapshot()) {
            // Detached threads are no longer application-owned and Perl never
            // returns them from list(), even while their Java carrier is still
            // winding down.
            if (thread.isDetached()) continue;
            if (thread.isJoining()) continue;
            if (filter == 1 && !thread.isRunning()) continue;
            if (filter == 2 && !thread.isJoinable()) continue;
            result.add(threadObject(thread.id(), thread));
        }
        if (ctx == RuntimeContextType.SCALAR) {
            return new RuntimeScalar(result.size()).getList();
        }
        return result;
    }

    public static RuntimeList _join(RuntimeArray args, int ctx) {
        RuntimeHash object = threadHash(args);
        RuntimeScalar objectTid = object.get("tid");
        if (objectTid != null && "compile-stub".equals(object.get("state").toString())) {
            object.put("state", new RuntimeScalar("joined"));
            RuntimeScalar error = object.get("error");
            if (error != null && error.getDefinedBoolean()) {
                org.perlonjava.runtime.operators.WarnDie.warnWithCategory(
                        new RuntimeScalar("Thread " + objectTid + " terminated abnormally: " + error),
                        new RuntimeScalar(), "threads");
                return RuntimeScalarCache.scalarUndef.getList();
            }
            RuntimeScalar stored = object.get("result");
            RuntimeArray values = stored != null && stored.value instanceof RuntimeArray array
                    ? array : new RuntimeArray();
            int creationContext = object.get("context").getInt();
            if (creationContext == RuntimeContextType.VOID || ctx == RuntimeContextType.VOID) {
                return RuntimeScalarCache.scalarUndef.getList();
            }
            if (ctx == RuntimeContextType.SCALAR) {
                return values.isEmpty() ? RuntimeScalarCache.scalarUndef.getList()
                        : values.get(values.size() - 1).getList();
            }
            return new RuntimeList(values.elements.toArray(RuntimeBase[]::new));
        }
        PerlThreadControlBlock thread = findKnownThread(object);
        if (thread == null) throw new IllegalStateException("Thread is no longer joinable");
        if (thread.id() == PerlRuntime.current().perlThreadId()) {
            throw new IllegalStateException("Cannot join self");
        }
        try {
            PerlThreadControlBlock caller = PerlRuntime.current().threadRegistry()
                    .get(PerlRuntime.current().perlThreadId());
            if (caller != null && caller != thread) caller.beginJoinWait();
            PerlThreadControlBlock.Completion completion;
            try {
                completion = thread.join();
            } finally {
                if (caller != null && caller != thread) caller.endJoinWait();
            }
            try {
                object.put("state", new RuntimeScalar("joined"));
                String error = errorText(completion.error());
                RuntimeScalar errorValue = threadErrorValue(thread);
                object.put("error", errorValue);
                if (completion.error() instanceof PerlExitException processExit) throw processExit;
                if (!error.isEmpty()) {
                    org.perlonjava.runtime.operators.WarnDie.warnWithCategory(
                            new RuntimeScalar("Thread " + thread.id()
                                    + " terminated abnormally: " + error),
                            new RuntimeScalar(), "threads");
                }
                if (!(completion.value() instanceof RuntimeArray values)) return new RuntimeList();
                List<RuntimeBase> cloned = new RuntimeGraphCloner(
                        thread.childRuntime(), PerlRuntime.current()).cloneRoots(values.elements);
                // Join-cloned scalar roots are Perl temporaries. The receiving
                // assignment acquires its own owner; leaving the clone's
                // reconstructed owner live suppresses DESTROY for the joined
                // value at parent scope/global destruction.
                for (RuntimeBase clonedValue : cloned) {
                    if (clonedValue instanceof RuntimeScalar scalar) {
                        MortalList.deferDecrementIfTracked(scalar);
                    }
                }
                if (thread.context() == RuntimeContextType.VOID) {
                    return RuntimeScalarCache.scalarUndef.getList();
                }
                if (ctx == RuntimeContextType.VOID) return new RuntimeList();
                if (ctx == RuntimeContextType.SCALAR) {
                    return cloned.isEmpty() ? RuntimeScalarCache.scalarUndef.getList()
                            : cloned.getLast().scalar().getList();
                }
                return new RuntimeList(cloned.toArray(RuntimeBase[]::new));
            } finally {
                thread.releaseTerminalResources();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Thread join interrupted", e);
        }
    }

    public static RuntimeList _detach(RuntimeArray args, int ctx) {
        RuntimeHash object = threadHash(args);
        RuntimeScalar objectTid = object.get("tid");
        if (objectTid != null && "compile-stub".equals(object.get("state").toString())) {
            object.put("state", new RuntimeScalar("detached"));
            return RuntimeScalarCache.scalarUndef.getList();
        }
        PerlThreadControlBlock thread = findKnownThread(object);
        if (thread == null) throw new IllegalStateException("Thread is no longer detachable");
        thread.detach();
        object.put("state", new RuntimeScalar("detached"));
        return RuntimeScalarCache.scalarUndef.getList();
    }

    public static RuntimeList _is_running(RuntimeArray args, int ctx) {
        RuntimeHash object = threadHash(args);
        RuntimeScalar tid = object.get("tid");
        if (tid != null && tid.getLong() == 0) return new RuntimeScalar(1).getList();
        PerlThreadControlBlock thread = findKnownThread(object);
        return new RuntimeScalar(thread != null && thread.isRunning() ? 1 : 0).getList();
    }

    public static RuntimeList _is_joinable(RuntimeArray args, int ctx) {
        PerlThreadControlBlock thread = findKnownThread(threadHash(args));
        return new RuntimeScalar(thread != null && thread.isJoinable() ? 1 : 0).getList();
    }

    public static RuntimeList _is_detached(RuntimeArray args, int ctx) {
        RuntimeHash object = threadHash(args);
        RuntimeScalar tid = object.get("tid");
        if (tid != null && tid.getLong() == 0) return new RuntimeScalar(1).getList();
        PerlThreadControlBlock thread = findKnownThread(object);
        boolean detached = thread != null ? thread.isDetached()
                : "detached".equals(object.get("state").toString());
        return new RuntimeScalar(detached ? 1 : 0).getList();
    }

    public static RuntimeList _error(RuntimeArray args, int ctx) {
        RuntimeHash object = threadHash(args);
        RuntimeScalar saved = object.get("error");
        PerlThreadControlBlock thread = findKnownThread(object);
        if (thread != null) {
            if (thread.error() == null) return RuntimeScalarCache.scalarUndef.getList();
            RuntimeScalar current = threadErrorValue(thread);
            if (current.getDefinedBoolean()) return current.getList();
        }
        return saved == null || !saved.getDefinedBoolean()
                ? RuntimeScalarCache.scalarUndef.getList() : saved.getList();
    }

    public static RuntimeList _exit(RuntimeArray args, int ctx) {
        if (PerlRuntime.current().perlThreadId() == 0) {
            RuntimeScalar status = args.isEmpty() ? new RuntimeScalar(0) : args.get(0);
            return WarnDie.exit(status).getList();
        }
        RuntimeArray values = new RuntimeArray(RuntimeScalarCache.scalarUndef);
        throw new PerlThreadExitException(values);
    }

    public static RuntimeList _object(RuntimeArray args, int ctx) {
        if (args.isEmpty() || !args.get(0).getDefinedBoolean()) return RuntimeScalarCache.scalarUndef.getList();
        long id = args.get(0).getLong();
        if (id == 0) return threadObject(0, null).getList();
        PerlThreadControlBlock thread = PerlRuntime.current().threadRegistry().get(id);
        return thread == null ? RuntimeScalarCache.scalarUndef.getList() : threadObject(id, thread).getList();
    }

    public static RuntimeList _wantarray(RuntimeArray args, int ctx) {
        RuntimeHash object = threadHash(args);
        RuntimeScalar tid = object.get("tid");
        int context = RuntimeContextType.SCALAR;
        if (tid != null && tid.getLong() != 0) {
            PerlThreadControlBlock thread = findKnownThread(object);
            if (thread == null) return RuntimeScalarCache.scalarUndef.getList();
            context = thread.context();
        }
        if (context == RuntimeContextType.VOID) return RuntimeScalarCache.scalarUndef.getList();
        return new RuntimeScalar(RuntimeContextType.isListLike(context) ? 1 : 0).getList();
    }

    public static RuntimeList _kill(RuntimeArray args, int ctx) {
        RuntimeScalar object = threadObjectScalar(args);
        RuntimeHash hash = threadHash(args);
        if (args.size() < 2) throw new IllegalArgumentException("Usage: $thr->kill('SIG...')");
        String signal = normalizeSignal(args.get(1));
        PerlThreadControlBlock thread = findKnownThread(hash);
        // Java cannot deliver Perl's uncatchable KILL semantics to a detached
        // carrier. Preserve the documented explicit no-op for that one signal;
        // ordinary safe signals remain deliverable to running detached threads.
        if (thread != null && thread.isDetached() && "KILL".equals(signal)) {
            return RuntimeScalarCache.scalarUndef.getList();
        }
        if (thread == null || !thread.isRunning()) {
            return thread != null && !thread.isDetached()
                    ? object.getList() : RuntimeScalarCache.scalarUndef.getList();
        }
        if (!thread.hasSignalHandler(signal)) {
            throw new IllegalStateException("Signal " + signal + " has no signal handler set");
        }
        thread.signal(signal);
        return object.getList();
    }

    public static RuntimeList _get_stack_size(RuntimeArray args, int ctx) {
        if (args.isEmpty()) return new RuntimeScalar(PerlRuntime.current().defaultPerlThreadStackSize()).getList();
        RuntimeHash object = threadHash(args);
        RuntimeScalar tid = object.get("tid");
        if (tid != null && tid.getLong() == 0) {
            return new RuntimeScalar(PerlRuntime.current().perlThreadStackSize()).getList();
        }
        PerlThreadControlBlock thread = findKnownThread(object);
        return new RuntimeScalar(thread == null ? 0 : thread.stackSize()).getList();
    }

    public static RuntimeList _set_stack_size(RuntimeArray args, int ctx) {
        if (args.isEmpty()) throw new IllegalArgumentException("Missing stack size");
        long size = args.get(args.size() - 1).getLong();
        if (size < 0) throw new IllegalArgumentException("Stack size must not be negative");
        if (args.size() > 1 && args.get(0).type == RuntimeScalarType.HASHREFERENCE) {
            throw new IllegalStateException("Cannot change stack size of an existing thread");
        }
        PerlRuntime runtime = PerlRuntime.current();
        long old = runtime.defaultPerlThreadStackSize();
        runtime.setDefaultPerlThreadStackSize(size);
        return new RuntimeScalar(old).getList();
    }

    public static RuntimeList _set_thread_exit_only(RuntimeArray args, int ctx) {
        if (args.size() < 2) throw new IllegalArgumentException("Missing thread exit policy");
        RuntimeScalar invocant = args.get(0);
        boolean value = args.get(1).getBoolean();
        if (invocant.type == RuntimeScalarType.HASHREFERENCE) {
            PerlThreadControlBlock thread = findKnownThread(threadHash(args));
            PerlRuntime child = thread == null ? null : thread.childRuntime();
            if (child != null) child.setPerlThreadExitOnly(value);
        } else {
            PerlRuntime.current().setPerlThreadExitOnly(value);
        }
        return invocant.getList();
    }

    public static RuntimeList _set_default_exit_only(RuntimeArray args, int ctx) {
        boolean value = !args.isEmpty() && args.get(0).getBoolean();
        PerlRuntime.current().setDefaultPerlThreadExitOnly(value);
        return RuntimeScalarCache.scalarUndef.getList();
    }

    private static PerlThreadControlBlock findThread(RuntimeHash object) {
        RuntimeScalar tid = object.get("tid");
        return tid == null ? null : PerlRuntime.current().threadRegistry().get(tid.getLong());
    }

    private static PerlThreadControlBlock findKnownThread(RuntimeHash object) {
        RuntimeScalar tid = object.get("tid");
        return tid == null ? null : PerlRuntime.current().threadRegistry().getKnown(tid.getLong());
    }

    private static int creationContext(RuntimeHash options, int implicit) {
        if (options == null) return implicit;
        RuntimeScalar named = options.get("context");
        if (named != null && named.getDefinedBoolean()) return namedContext(named.toString());
        if (truthy(options, "list") || truthy(options, "array")) return RuntimeContextType.LIST;
        if (truthy(options, "scalar")) return RuntimeContextType.SCALAR;
        if (truthy(options, "void")) return RuntimeContextType.VOID;
        return implicit;
    }

    private static int namedContext(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "list", "array" -> RuntimeContextType.LIST;
            case "scalar" -> RuntimeContextType.SCALAR;
            case "void" -> RuntimeContextType.VOID;
            default -> throw new IllegalArgumentException("Invalid context: " + value);
        };
    }

    private static boolean truthy(RuntimeHash options, String key) {
        RuntimeScalar value = options.get(key);
        return value != null && value.getBoolean();
    }

    private static long optionLong(RuntimeHash options, String key, long fallback) {
        if (options == null) return fallback;
        RuntimeScalar value = options.get(key);
        return value == null || !value.getDefinedBoolean() ? fallback : value.getLong();
    }

    private static boolean optionExitOnly(RuntimeHash options, boolean fallback) {
        if (options == null) return fallback;
        RuntimeScalar value = options.get("exit");
        if (value == null || !value.getDefinedBoolean()) return fallback;
        return value.toString().matches("threads?_only");
    }

    private static final Set<String> SIGNALS = Set.of(
            "HUP", "INT", "QUIT", "ILL", "TRAP", "ABRT", "BUS", "FPE",
            "KILL", "USR1", "SEGV", "USR2", "PIPE", "ALRM", "TERM", "CHLD",
            "STOP", "CONT");

    private static String normalizeSignal(RuntimeScalar value) {
        String signal = value.toString().toUpperCase(Locale.ROOT);
        if (signal.startsWith("SIG")) signal = signal.substring(3);
        if (!SIGNALS.contains(signal)) throw new IllegalArgumentException("Unrecognized signal name: " + value);
        return signal;
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

    private static PerlThreadExitException findThreadExit(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof PerlThreadExitException exit) return exit;
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return null;
    }

    private static RuntimeScalar threadErrorValue(PerlThreadControlBlock thread) {
        Throwable failure = thread.error();
        PerlRuntime child = thread.childRuntime();
        if (failure == null || child == null) return RuntimeScalarCache.scalarUndef;
        RuntimeScalar source = ErrorMessageUtil.exceptionValue(failure);
        return new RuntimeGraphCloner(child, PerlRuntime.current())
                .cloneRoots(List.of(source)).getFirst().scalar();
    }
}
