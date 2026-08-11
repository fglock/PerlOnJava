package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.runtime.io.IOHandle;
import org.perlonjava.runtime.io.StandardIO;
import org.perlonjava.runtime.mro.MroRuntimeState;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Identity and scoped thread binding for one Perl interpreter instance.
 *
 * <p>Runtime subsystems migrate into this object in independently tested phases.
 * Bindings are explicit and scoped:
 * they do not inherit into child or executor threads, whose tasks must capture
 * and bind the intended runtime themselves.</p>
 */
public final class PerlRuntime {
    private static final ThreadLocal<BindingFrame> CURRENT = new ThreadLocal<>();

    public final ExecutionRuntimeState executionState = new ExecutionRuntimeState();
    public final RuntimeRegexState regexState = new RuntimeRegexState();
    public final MroRuntimeState mroState = new MroRuntimeState();

    RuntimeIO ioStdout;
    RuntimeIO ioStderr;
    RuntimeIO ioStdin;
    RuntimeIO ioSelectedHandle;
    RuntimeIO ioLastWrittenHandle;
    RuntimeIO ioLastAccessedHandle;
    String ioLastReadlineHandleName;
    final Map<IOHandle, Boolean> ioOpenHandles = new LinkedHashMap<>(RuntimeIO.MAX_OPEN_HANDLES, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<IOHandle, Boolean> eldest) {
            if (size() <= RuntimeIO.MAX_OPEN_HANDLES) {
                return false;
            }
            try {
                eldest.getKey().flush();
            } catch (Exception ignored) {
                // Eviction is best-effort; the handle remains externally owned.
            }
            return true;
        }
    };
    private final Map<String, RuntimeGlob> standardIOGlobs = new HashMap<>();
    private final Set<String> hiddenStandardIOGlobs = new HashSet<>();

    public PerlRuntime() {
        ioStdout = new RuntimeIO(new StandardIO(System.out, true));
        ioStderr = new RuntimeIO(new StandardIO(System.err, false));
        ioStderr.autoFlush = true;
        ioStdin = new RuntimeIO(new StandardIO(System.in));
        ioSelectedHandle = ioStdout;
        ioLastWrittenHandle = ioStdout;

        installInitialStandardGlob("main::STDOUT", ioStdout);
        installInitialStandardGlob("main::stdout", ioStdout);
        installInitialStandardGlob("main::STDERR", ioStderr);
        installInitialStandardGlob("main::stderr", ioStderr);
        installInitialStandardGlob("main::STDIN", ioStdin);
        installInitialStandardGlob("main::stdin", ioStdin);
        ioStdout.globName = "main::STDOUT";
        ioStderr.globName = "main::STDERR";
        ioStdin.globName = "main::STDIN";
    }

    /** Return the runtime bound to this thread, failing clearly when absent. */
    public static PerlRuntime current() {
        PerlRuntime runtime = currentOrNull();
        if (runtime == null) {
            throw new IllegalStateException(
                    "No PerlRuntime is bound to the current thread; use PerlRuntime.bind() in a scoped block");
        }
        return runtime;
    }

    /** Return the runtime bound to this thread, or {@code null}. */
    public static PerlRuntime currentOrNull() {
        BindingFrame frame = CURRENT.get();
        return frame != null ? frame.runtime : null;
    }

    /** Bind this runtime until the returned scope is closed. */
    public Binding bind() {
        BindingFrame frame = new BindingFrame(this, CURRENT.get());
        CURRENT.set(frame);
        return new Binding(frame, Thread.currentThread());
    }

    /** Convenience form for {@code runtime.bind()}. */
    public static Binding bind(PerlRuntime runtime) {
        return Objects.requireNonNull(runtime, "runtime").bind();
    }

    /**
     * Bind the existing runtime again, or create a temporary runtime when
     * called at a public Java entry point. Closing always restores the exact
     * prior state, including absence.
     */
    public static Binding bindCurrentOrNew() {
        PerlRuntime runtime = currentOrNull();
        return (runtime != null ? runtime : new PerlRuntime()).bind();
    }

    public ExecutionRuntimeState executionState() {
        return executionState;
    }

    public RuntimeRegexState regexState() {
        return regexState;
    }

    public MroRuntimeState mroState() {
        return mroState;
    }

    RuntimeGlob standardIOGlob(String name) {
        return standardIOGlobs.get(name);
    }

    Set<String> standardIOGlobNames() {
        return standardIOGlobs.keySet();
    }

    boolean isStandardIOGlobVisible(String name) {
        return standardIOGlobs.containsKey(name) && !hiddenStandardIOGlobs.contains(name);
    }

    void hideStandardIOGlob(String name) {
        if (standardIOGlobs.containsKey(name)) {
            hiddenStandardIOGlobs.add(name);
        }
    }

    void showStandardIOGlob(String name) {
        hiddenStandardIOGlobs.remove(name);
    }

    void hideStandardIOGlobsWithPrefix(String prefix) {
        for (String name : standardIOGlobs.keySet()) {
            if (name.startsWith(prefix)) {
                hiddenStandardIOGlobs.add(name);
            }
        }
    }

    void resetStandardIOGlobVisibility() {
        hiddenStandardIOGlobs.clear();
    }

    void replaceStandardIOGlob(String name, RuntimeGlob glob) {
        standardIOGlobs.put(name, glob);
        if (glob != null && glob.IO != null && glob.IO.value instanceof RuntimeIO io) {
            replaceStandardHandle(name, io);
        }
    }

    void replaceStandardHandle(String name, RuntimeIO io) {
        switch (name) {
        case "main::STDOUT" -> {
            ioStdout = io;
            updateStandardGlobHandle(name, io);
        }
        case "main::STDERR" -> {
            ioStderr = io;
            updateStandardGlobHandle(name, io);
        }
        case "main::STDIN" -> {
            ioStdin = io;
            updateStandardGlobHandle(name, io);
        }
        case "main::stdout", "main::stderr", "main::stdin" ->
            updateStandardGlobHandle(name, io);
        default -> throw new IllegalArgumentException("Not a standard I/O glob: " + name);
        }
        io.globName = name;
    }

    private void installInitialStandardGlob(String name, RuntimeIO io) {
        RuntimeGlob glob = new RuntimeGlob(name);
        glob.IO = new RuntimeScalar(io);
        standardIOGlobs.put(name, glob);
    }

    private void updateStandardGlobHandle(String name, RuntimeIO io) {
        RuntimeGlob glob = standardIOGlobs.get(name);
        if (glob != null) {
            glob.IO = new RuntimeScalar(io);
        }
    }

    private record BindingFrame(PerlRuntime runtime, BindingFrame previous) {
    }

    /** Owns exactly one binding frame and restores the prior frame on close. */
    public static final class Binding implements AutoCloseable {
        private final BindingFrame frame;
        private final Thread owner;
        private boolean closed;

        private Binding(BindingFrame frame, Thread owner) {
            this.frame = frame;
            this.owner = owner;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("PerlRuntime binding must be closed by its owning thread");
            }
            if (CURRENT.get() != frame) {
                throw new IllegalStateException("PerlRuntime bindings must be closed in LIFO order");
            }
            closed = true;
            if (frame.previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(frame.previous);
            }
        }
    }
}
