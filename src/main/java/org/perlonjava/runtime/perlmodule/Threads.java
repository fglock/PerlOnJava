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
            stub.put("tid", new RuntimeScalar(-1));
            stub.put("state", new RuntimeScalar("detached"));
            return ReferenceOperators.bless(stub.createReference(), new RuntimeScalar(CLASS)).getList();
        }
        RuntimeArray threadArgs = new RuntimeArray();
        for (int i = codeIndex + 1; i < args.size(); i++) threadArgs.push(args.get(i));
        PerlRuntime parent = PerlRuntime.current();
        int threadContext = creationContext(options, ctx);
        long stackSize = optionLong(options, "stack_size", parent.defaultPerlThreadStackSize());
        boolean exitOnly = optionExitOnly(options, parent.defaultPerlThreadExitOnly());
        PerlThreadControlBlock thread = PerlThreadControlBlock.create(
                parent, code, threadArgs, threadContext, stackSize, exitOnly).start();
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
            String error = errorText(completion.error());
            object.put("error", error.isEmpty() ? RuntimeScalarCache.scalarUndef : new RuntimeScalar(error));
            if (completion.error() instanceof PerlExitException processExit) throw processExit;
            if (!error.isEmpty()) {
                RuntimeIO.getStderr().write(
                        "Thread " + thread.id() + " terminated abnormally: " + error + "\n");
            }
            if (!(completion.value() instanceof RuntimeArray values)) return new RuntimeList();
            List<RuntimeBase> cloned = new RuntimeGraphCloner(
                    thread.childRuntime(), PerlRuntime.current()).cloneRoots(values.elements);
            if (thread.context() == RuntimeContextType.VOID) {
                return RuntimeScalarCache.scalarUndef.getList();
            }
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
            return thread.error() == null ? RuntimeScalarCache.scalarUndef.getList()
                    : new RuntimeScalar(errorText(thread.error())).getList();
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
        PerlThreadControlBlock thread = findThread(hash);
        if (thread == null || !thread.isRunning() || thread.isDetached()) {
            return RuntimeScalarCache.scalarUndef.getList();
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
            if (thread != null) thread.childRuntime().setPerlThreadExitOnly(value);
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
            "KILL", "USR1", "SEGV", "USR2", "PIPE", "ALRM", "TERM", "CHLD");

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
}
