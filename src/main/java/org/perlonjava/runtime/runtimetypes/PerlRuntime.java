package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.backend.jvm.ByteCodeSourceMapper;
import org.perlonjava.frontend.parser.DataSection;
import org.perlonjava.runtime.CompilationRuntimeState;
import org.perlonjava.runtime.ForkOpenState;
import org.perlonjava.runtime.debugger.DebugRuntimeState;
import org.perlonjava.runtime.io.IOHandle;
import org.perlonjava.runtime.io.IORuntimeRegistryState;
import org.perlonjava.runtime.io.StandardIO;
import org.perlonjava.runtime.mro.MroRuntimeState;
import org.perlonjava.runtime.operators.Time;
import org.perlonjava.runtime.operators.Random;
import org.perlonjava.runtime.operators.ScalarFlipFlopOperator;
import org.perlonjava.runtime.operators.ScalarGlobOperator;
import org.perlonjava.runtime.operators.FileTestOperator;
import org.perlonjava.runtime.perlmodule.FilterRuntimeState;
import org.perlonjava.runtime.perlmodule.NetSSLeay;
import org.perlonjava.runtime.nativ.ExtendedNativeUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;
import java.util.TreeSet;

/**
 * Identity and scoped thread binding for one Perl interpreter instance.
 *
 * <p>Runtime subsystems migrate into this object in independently tested phases.
 * Bindings are explicit and scoped:
 * they do not inherit into child or executor threads, whose tasks must capture
 * and bind the intended runtime themselves.</p>
 */
public final class PerlRuntime implements AutoCloseable {
    private static final ThreadLocal<BindingFrame> CURRENT = new ThreadLocal<>();
    public final long pid = ProcessHandle.current().pid();
    String currentDirectory = System.getProperty("user.dir");
    private final ReentrantLock executionLock = new ReentrantLock();
    private volatile boolean initialized;
    private volatile boolean closed;
    private final PerlThreadRegistry threadRegistry;
    private final long perlThreadId;

    public final ExecutionRuntimeState executionState = new ExecutionRuntimeState();
    public final RuntimeRegexState regexState = new RuntimeRegexState();
    public final MroRuntimeState mroState = new MroRuntimeState();
    public final GlobalRuntimeState globalState = new GlobalRuntimeState();
    public final RuntimeCodeRuntimeState runtimeCodeState = new RuntimeCodeRuntimeState();
    public final CompilationRuntimeState compilationState = new CompilationRuntimeState();
    public final ByteCodeSourceMapper.State sourceMapperState = new ByteCodeSourceMapper.State();
    public final FilterRuntimeState filterState = new FilterRuntimeState();
    public final Time.State timeState = new Time.State();
    public final PerlSignalQueue.State signalState = new PerlSignalQueue.State();
    public final Random.State randomState = new Random.State();
    public final DataSection.State dataSectionState = new DataSection.State();
    public final Map<Integer, ScalarFlipFlopOperator> flipFlopState = new HashMap<>();
    public final Map<Integer, ScalarGlobOperator> scalarGlobState = new HashMap<>();
    public final Map<Integer, String> pointerPackState = new HashMap<>();
    public final IORuntimeRegistryState ioRegistryState = new IORuntimeRegistryState();
    public final FileTestOperator.State fileTestState = new FileTestOperator.State();
    public final DebugRuntimeState debugState = new DebugRuntimeState();
    public final DiamondIO.State diamondIOState = new DiamondIO.State();
    public final ExtendedNativeUtils.State nativeState = new ExtendedNativeUtils.State();
    public final Deque<Long> netSslErrorQueue = new ArrayDeque<>();
    public final NetSSLeay.State netSslState = new NetSSLeay.State();
    public ForkOpenState.PendingForkOpen pendingForkOpen;
    public RuntimeArray libOriginalInc;
    public boolean storableLastOpInNetorder;
    public final Set<String> xsShimLoadingInProgress = new HashSet<>();

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
    final Map<String, Boolean> stateVariableInitialized = new HashMap<>();
    final LifecycleRuntimeState lifecycleState = new LifecycleRuntimeState();
    final NameNormalizer.State nameNormalizerState = new NameNormalizer.State();

    public PerlRuntime() {
        this(new PerlThreadRegistry(), 0);
    }

    private PerlRuntime(PerlThreadRegistry threadRegistry, long perlThreadId) {
        this.threadRegistry = Objects.requireNonNull(threadRegistry, "threadRegistry");
        this.perlThreadId = perlThreadId;
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
        if (closed) {
            throw new IllegalStateException("PerlRuntime is closed");
        }
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

    public GlobalRuntimeState globalState() {
        return globalState;
    }

    public RuntimeCodeRuntimeState runtimeCodeState() {
        return runtimeCodeState;
    }

    public PerlThreadRegistry threadRegistry() {
        return threadRegistry;
    }

    public long perlThreadId() {
        return perlThreadId;
    }

    /** Initialize this independent interpreter's globals and runtime services. */
    public PerlRuntime initialize() {
        executionLock.lock();
        try {
            if (closed) throw new IllegalStateException("PerlRuntime is closed");
            if (initialized) return this;
            try (Binding ignored = bind()) {
                org.perlonjava.app.scriptengine.PerlLanguageProvider.resetAll();
            }
            initialized = true;
            return this;
        } finally {
            executionLock.unlock();
        }
    }

    /** Execute one operation while enforcing exclusive runtime ownership. */
    public <T> T execute(Callable<T> operation) throws Exception {
        Objects.requireNonNull(operation, "operation");
        executionLock.lock();
        try {
            if (!initialized) {
                // Public provider/JSR entry points may have initialized this
                // runtime before it was adopted by the managed lifecycle API.
                // Never reset that live package graph just to mark ownership.
                if (globalState.coreGlobalsInitialized()) {
                    initialized = true;
                } else {
                    initialize();
                }
            }
            try (Binding ignored = bind()) {
                return operation.call();
            }
        } finally {
            executionLock.unlock();
        }
    }

    /** Execute a void operation while enforcing exclusive runtime ownership. */
    public void execute(Runnable operation) {
        Objects.requireNonNull(operation, "operation");
        try {
            execute(() -> {
                operation.run();
                return null;
            });
        } catch (RuntimeException | Error error) {
            throw error;
        } catch (Exception impossible) {
            throw new IllegalStateException("Runnable execution failed", impossible);
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    public boolean isClosed() {
        return closed;
    }

    /**
     * Clone this interpreter's package graph for a new ithread. Execution,
     * lifecycle, alarm, signal, native and I/O state starts fresh in the child.
     */
    public PerlRuntime snapshotClone() {
        return snapshotCloneInternal(new PerlThreadRegistry(), 0).runtime();
    }

    record ThreadSnapshot(PerlRuntime runtime, RuntimeGraphCloner cloner) {}

    ThreadSnapshot snapshotCloneForThread(PerlThreadRegistry registry, long threadId) {
        return snapshotCloneInternal(registry, threadId);
    }

    private ThreadSnapshot snapshotCloneInternal(PerlThreadRegistry registry, long threadId) {
        executionLock.lock();
        try {
            if (closed) throw new IllegalStateException("PerlRuntime is closed");
            if (!initialized) {
                if (globalState.coreGlobalsInitialized()) {
                    initialized = true;
                } else {
                    initialize();
                }
            }

            Set<String> skipped;
            try (Binding ignored = bind()) {
                materializeLazyCodeDefinitions();
                skipped = preflightCloneSkip();
            }

            PerlRuntime child = new PerlRuntime(registry, threadId);
            RuntimeGraphCloner cloner = new RuntimeGraphCloner(this, child, skipped);
            try (Binding ignored = bind()) {
                globalState.snapshotInto(child.globalState, cloner);
            }
            child.currentDirectory = currentDirectory;
            child.initialized = true;
            try (Binding ignored = child.bind()) {
                child.runCloneHooks();
            }
            return new ThreadSnapshot(child, cloner);
        } finally {
            executionLock.unlock();
        }
    }

    private Set<String> preflightCloneSkip() {
        Set<String> skipped = new HashSet<>();
        for (String fqn : new TreeSet<>(globalState.codeRefs().keySet())) {
            if (!fqn.endsWith("::CLONE_SKIP")) continue;
            RuntimeScalar hook = globalState.codeRefs().get(fqn);
            if (hook == null || !(hook.value instanceof RuntimeCode code) || !code.defined()) continue;
            String packageName = fqn.substring(0, fqn.length() - "::CLONE_SKIP".length());
            RuntimeArray args = new RuntimeArray(new RuntimeScalar(packageName));
            if (RuntimeCode.apply(hook, args, RuntimeContextType.SCALAR).scalar().getBoolean()) {
                skipped.add(packageName);
            }
        }
        return skipped;
    }

    private void materializeLazyCodeDefinitions() {
        for (Map.Entry<String, RuntimeScalar> entry
                : new java.util.ArrayList<>(globalState.codeRefs().entrySet())) {
            String fqn = entry.getKey();
            boolean cloneHook = fqn.endsWith("::CLONE") || fqn.endsWith("::CLONE_SKIP");
            if (!cloneHook && !fqn.startsWith("threads::")) continue;
            RuntimeScalar hook = entry.getValue();
            if (hook != null && hook.value instanceof RuntimeCode code
                    && code.compilerSupplier != null) {
                code.compilerSupplier.get();
            }
        }
    }

    private void runCloneHooks() {
        for (String fqn : new TreeSet<>(globalState.codeRefs().keySet())) {
            if (!fqn.endsWith("::CLONE") || fqn.endsWith("::CLONE_SKIP")) continue;
            RuntimeScalar hook = globalState.codeRefs().get(fqn);
            if (hook == null || !(hook.value instanceof RuntimeCode code) || !code.defined()) continue;
            String packageName = fqn.substring(0, fqn.length() - "::CLONE".length());
            RuntimeCode.apply(hook, new RuntimeArray(new RuntimeScalar(packageName)),
                    RuntimeContextType.VOID);
        }
    }

    /** Release runtime-owned I/O, alarms, signals, and lifecycle registries. */
    @Override
    public void close() {
        executionLock.lock();
        try {
            if (closed) return;
            try (Binding ignored = bind()) {
                Time.cancelCurrentAlarm();
                PerlSignalQueue.clearSignals();
                RuntimeIO.closeAllHandles();
                NetSSLeay.resetState();
                MortalList.clearCurrentRuntimeState();
                stateVariableInitialized.clear();
                ioRegistryState.clear();
                nativeState.clear();
                flipFlopState.clear();
                scalarGlobState.clear();
                pointerPackState.clear();
            }
            closed = true;
        } finally {
            executionLock.unlock();
        }
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
