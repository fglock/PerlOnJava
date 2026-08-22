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
import org.perlonjava.runtime.mro.InheritanceResolver;
import org.perlonjava.runtime.operators.Time;
import org.perlonjava.runtime.operators.Random;
import org.perlonjava.runtime.operators.ScalarFlipFlopOperator;
import org.perlonjava.runtime.operators.ScalarGlobOperator;
import org.perlonjava.runtime.operators.FileTestOperator;
import org.perlonjava.runtime.perlmodule.FilterRuntimeState;
import org.perlonjava.runtime.perlmodule.NetSSLeay;
import org.perlonjava.runtime.nativ.ExtendedNativeUtils;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.ArrayDeque;
import java.util.Deque;
import java.lang.ref.WeakReference;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

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
    private final Object lifecycleMonitor = new Object();
    private final AtomicInteger activeBindings = new AtomicInteger();
    private final AtomicInteger activeSharedLocks = new AtomicInteger();
    private final AtomicInteger activeSharedWaiters = new AtomicInteger();
    private volatile boolean initialized;
    private volatile boolean closed;
    private volatile boolean resetting;
    private volatile Thread resetOwner;
    private final PerlThreadRegistry threadRegistry;
    private final long perlThreadId;
    private volatile int perlThreadContext = RuntimeContextType.SCALAR;
    private volatile long perlThreadStackSize;
    private volatile boolean perlThreadExitOnly;

    public ExecutionRuntimeState executionState = new ExecutionRuntimeState();
    public RuntimeRegexState regexState = new RuntimeRegexState();
    public MroRuntimeState mroState = new MroRuntimeState();
    public GlobalRuntimeState globalState = new GlobalRuntimeState();
    public RuntimeCodeRuntimeState runtimeCodeState = new RuntimeCodeRuntimeState();
    public CompilationRuntimeState compilationState = new CompilationRuntimeState();
    public ByteCodeSourceMapper.State sourceMapperState = new ByteCodeSourceMapper.State();
    public FilterRuntimeState filterState = new FilterRuntimeState();
    public Time.State timeState = new Time.State();
    public PerlSignalQueue.State signalState = new PerlSignalQueue.State();
    public Random.State randomState = new Random.State();
    public DataSection.State dataSectionState = new DataSection.State();
    public final Map<Integer, ScalarFlipFlopOperator> flipFlopState = new HashMap<>();
    public final Map<Integer, ScalarGlobOperator> scalarGlobState = new HashMap<>();
    public final Map<Integer, String> pointerPackState = new HashMap<>();
    final Map<Integer, WeakReference<RuntimeBase>> bObjectState = new HashMap<>();
    public IORuntimeRegistryState ioRegistryState = new IORuntimeRegistryState();
    public FileTestOperator.State fileTestState = new FileTestOperator.State();
    public DebugRuntimeState debugState = new DebugRuntimeState();
    public DiamondIO.State diamondIOState = new DiamondIO.State();
    public ExtendedNativeUtils.State nativeState = new ExtendedNativeUtils.State();
    public final Deque<Long> netSslErrorQueue = new ArrayDeque<>();
    public NetSSLeay.State netSslState = new NetSSLeay.State();
    public ForkOpenState.PendingForkOpen pendingForkOpen;
    public int forkOpenOccurrence;
    public RuntimeArray libOriginalInc;
    public boolean storableLastOpInNetorder;
    public final Set<String> xsShimLoadingInProgress = new HashSet<>();
    private final Map<Long, WeakReference<RuntimeBase>> referenceAddresses = new ConcurrentHashMap<>();

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
    LifecycleRuntimeState lifecycleState = new LifecycleRuntimeState();
    NameNormalizer.State nameNormalizerState = new NameNormalizer.State();

    public PerlRuntime() {
        this(new PerlThreadRegistry(), 0);
    }

    private PerlRuntime(PerlThreadRegistry threadRegistry, long perlThreadId) {
        this.threadRegistry = Objects.requireNonNull(threadRegistry, "threadRegistry");
        this.perlThreadId = perlThreadId;
        ioStdout = new RuntimeIO(new StandardIO(
                ForkOpenState.isReplayProcess()
                        ? java.io.OutputStream.nullOutputStream()
                        : System.out,
                true));
        ioStderr = new RuntimeIO(new StandardIO(System.err, false));
        ioStderr.autoFlush = true;
        // A no-command fork-open child replays the program up to the selected
        // open.  Its OS stdin is already the new parent-to-child pipe, but real
        // fork semantics do not expose that pipe until the fork point.  Mute it
        // during replay so pre-fork input probes cannot consume the data the
        // parent will send to the output filter.
        ioStdin = new RuntimeIO(new StandardIO(
                ForkOpenState.isReplayProcess()
                        ? java.io.InputStream.nullInputStream()
                        : System.in));
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

    /** Make replay-child output visible once execution reaches its fork point. */
    public void activateForkOpenChildStdout() {
        RuntimeIO muted = ioStdout;
        RuntimeIO stdout = new RuntimeIO(new StandardIO(System.out, true));
        RuntimeIO stdin = new RuntimeIO(new StandardIO(System.in));
        replaceStandardHandle("main::STDOUT", stdout);
        replaceStandardHandle("main::stdout", stdout);
        replaceStandardHandle("main::STDIN", stdin);
        replaceStandardHandle("main::stdin", stdin);
        if (ioSelectedHandle == muted) ioSelectedHandle = stdout;
        if (ioLastWrittenHandle == muted) ioLastWrittenHandle = stdout;
        GlobalVariable.getGlobalVariable("main::$").set(pid);
        GlobalVariable.getGlobalHash("main::ENV").elements.remove(ForkOpenState.REPLAY_ENV);
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

    /** Record the address exposed by Perl reference stringification for B introspection. */
    public void registerReferenceAddress(RuntimeBase value) {
        registerReferenceAddress(referenceAddress(value), value);
    }

    public static long referenceAddress(RuntimeBase value) {
        // Perl exposes the same pointer token through both reference
        // stringification and numeric reference coercion. Runtime types may
        // override hashCode() to implement that established token (notably
        // typeglobs), so the B/refaddr registry must use it as well.
        return Integer.toUnsignedLong(value.hashCode());
    }

    /** Record an inherited address token for the corresponding cloned referent. */
    public void registerReferenceAddress(long address, RuntimeBase value) {
        referenceAddresses.put(address, new WeakReference<>(value));
    }

    /** Resolve an address previously exposed in this interpreter instance. */
    public RuntimeBase resolveReferenceAddress(long address) {
        WeakReference<RuntimeBase> reference = referenceAddresses.get(address);
        RuntimeBase value = reference == null ? null : reference.get();
        if (reference != null && value == null) {
            referenceAddresses.remove(address, reference);
        }
        return value;
    }

    Map<Long, RuntimeBase> snapshotReferenceAddresses() {
        Map<Long, RuntimeBase> snapshot = new HashMap<>();
        referenceAddresses.forEach((address, reference) -> {
            RuntimeBase value = reference.get();
            if (value != null) snapshot.put(address, value);
        });
        return snapshot;
    }

    /** Bind this runtime until the returned scope is closed. */
    public Binding bind() {
        synchronized (lifecycleMonitor) {
            if (closed) {
                throw new IllegalStateException("PerlRuntime is closed");
            }
            if (resetting && resetOwner != Thread.currentThread()) {
                throw new IllegalStateException("PerlRuntime is resetting");
            }
            activeBindings.incrementAndGet();
        }
        BindingFrame frame = new BindingFrame(this, CURRENT.get());
        CURRENT.set(frame);
        return new Binding(frame, Thread.currentThread(), this);
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

    public int perlThreadContext() { return perlThreadContext; }
    public void setPerlThreadContext(int context) { perlThreadContext = context; }
    public long perlThreadStackSize() { return perlThreadStackSize; }
    public void setPerlThreadStackSize(long size) { perlThreadStackSize = size; }
    public long defaultPerlThreadStackSize() { return threadRegistry.defaultStackSize(); }
    public void setDefaultPerlThreadStackSize(long size) { threadRegistry.setDefaultStackSize(size); }
    public boolean perlThreadExitOnly() { return perlThreadExitOnly; }
    public void setPerlThreadExitOnly(boolean value) { perlThreadExitOnly = value; }
    public boolean defaultPerlThreadExitOnly() { return threadRegistry.defaultExitOnly(); }
    public void setDefaultPerlThreadExitOnly(boolean value) { threadRegistry.setDefaultExitOnly(value); }

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
     * Return this runtime to fresh-initialized state without changing its Java identity.
     * Reset is rejected rather than delayed whenever observable work is still active.
     * A failure after the transition starts permanently poisons the runtime.
     */
    public PerlRuntime reset() {
        if (!executionLock.tryLock()) {
            throw new IllegalStateException("PerlRuntime reset requires exclusive execution ownership");
        }
        try {
            synchronized (lifecycleMonitor) {
                if (closed) throw new IllegalStateException("PerlRuntime is closed");
                if (resetting) throw new IllegalStateException("PerlRuntime is already resetting");
                if (activeBindings.get() != 0) {
                    throw new IllegalStateException("PerlRuntime reset requires all bindings to be closed");
                }
                if (threadRegistry.size() != 0) {
                    throw new IllegalStateException("PerlRuntime reset requires all child threads to finish");
                }
                if (activeSharedLocks.get() != 0 || activeSharedWaiters.get() != 0) {
                    throw new IllegalStateException(
                            "PerlRuntime reset requires shared locks and waiters to be quiescent");
                }
                if (org.perlonjava.app.scriptengine.PerlLanguageProvider.COMPILE_LOCK.isLocked()) {
                    throw new IllegalStateException("PerlRuntime reset requires compilation to be quiescent");
                }
                resetting = true;
                resetOwner = Thread.currentThread();
            }

            try {
                try (Binding ignored = bind()) {
                    releaseResettableResources();
                }
                replaceRuntimeState();
                initialized = false;
                initialize();
                threadRegistry.clearTerminalStateForReset();
                return this;
            } catch (Throwable failure) {
                closed = true;
                if (failure instanceof RuntimeException runtime) throw runtime;
                if (failure instanceof Error error) throw error;
                throw new IllegalStateException("PerlRuntime reset failed", failure);
            } finally {
                synchronized (lifecycleMonitor) {
                    resetting = false;
                    resetOwner = null;
                }
            }
        } finally {
            executionLock.unlock();
        }
    }

    /**
     * Clone this interpreter's package graph for a new ithread. Execution,
     * lifecycle, alarm, signal, native and I/O state starts fresh in the child.
     */
    public PerlRuntime snapshotClone() {
        return snapshotCloneInternal(new PerlThreadRegistry(), 0, java.util.List.of()).runtime();
    }

    /** Snapshot this runtime and clone additional non-global roots through the same graph map. */
    public RootSnapshot snapshotCloneWithRoots(java.util.List<? extends RuntimeBase> roots) {
        Objects.requireNonNull(roots, "roots");
        return snapshotCloneInternal(new PerlThreadRegistry(), 0, roots);
    }

    public record RootSnapshot(PerlRuntime runtime, java.util.List<RuntimeBase> roots) {}

    RootSnapshot snapshotCloneForThread(
            PerlThreadRegistry registry, long threadId,
            java.util.List<? extends RuntimeBase> roots) {
        return snapshotCloneInternal(registry, threadId, roots);
    }

    private RootSnapshot snapshotCloneInternal(
            PerlThreadRegistry registry, long threadId,
            java.util.List<? extends RuntimeBase> roots) {
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
            nameNormalizerState.snapshotInto(child.nameNormalizerState);
            RuntimeGraphCloner cloner = new RuntimeGraphCloner(this, child, skipped);
            try (Binding ignored = bind()) {
                globalState.snapshotInto(child.globalState, cloner);
                cloner.cloneCompilationHints(compilationState, child.compilationState);
                cloner.cloneCompilationWarnings(compilationState, child.compilationState);
                runtimeCodeState.snapshotCompiledMetadataInto(child.runtimeCodeState);
                sourceMapperState.snapshotInto(child.sourceMapperState);
                regexState.snapshotInto(child.regexState);
            }
            java.util.List<RuntimeBase> clonedRoots = cloner.cloneSnapshotRoots(roots);
            cloner.finishSnapshot();
            child.currentDirectory = currentDirectory;
            child.initialized = true;
            try (Binding ignored = child.bind()) {
                child.runCloneHooks();
            }
            return new RootSnapshot(child, clonedRoots);
        } finally {
            executionLock.unlock();
        }
    }

    private Set<String> preflightCloneSkip() {
        Set<String> skipped = new HashSet<>();
        // Perl calls the effective CLONE_SKIP method once for each class that
        // has live blessed values in the snapshot. Walking every defined hook
        // invoked callbacks long before any object of that class existed; only
        // checking direct package hooks missed inherited CLONE_SKIP on A2/B2.
        Set<String> liveClasses = new TreeSet<>(nameNormalizerState.blessStrCache.values());
        liveClasses.remove("");
        liveClasses.remove("__ANON__");
        for (String packageName : liveClasses) {
            RuntimeScalar hook = InheritanceResolver.findMethodInHierarchy(
                    "CLONE_SKIP", packageName, null, 0, false);
            if (hook == null || !(hook.value instanceof RuntimeCode code) || !code.defined()) continue;
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
            RuntimeScalar hook = entry.getValue();
            if (hook == null || !(hook.value instanceof RuntimeCode code)) continue;
            // A lazy named sub that closes over lexicals must be materialized
            // while the parent capture cells are authoritative. Non-capturing
            // subs stay lazy, avoiding the large test.pl eager-compilation
            // regression that originally motivated this narrow preflight.
            boolean capturesLexicals = code.closedOverVariables != null
                    && !code.closedOverVariables.isEmpty();
            if (!cloneHook && !fqn.startsWith("threads::") && !capturesLexicals) continue;
            if (code.compilerSupplier != null) {
                try {
                    code.compilerSupplier.get();
                } catch (PerlCompilerException expectedAtUseSite) {
                    // Snapshot preflight must not turn an expected-invalid lazy
                    // helper into a require-time failure. The definition stays
                    // lazy and reports its compile error when user code invokes
                    // it. CLONE hooks are part of snapshot itself and must fail.
                    if (cloneHook) throw expectedAtUseSite;
                }
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
                referenceAddresses.clear();
            }
            closed = true;
        } finally {
            executionLock.unlock();
        }
    }

    private void releaseResettableResources() {
        MortalList.flush();
        MortalList.flushDeferredCapturesBeforeEnd();
        try {
            SpecialBlock.runEndBlocks(false);
        } finally {
            MortalList.flushDeferredCaptures();
            org.perlonjava.runtime.regex.RuntimeRegex.emitCurrentRuntimeDebugFreeTraces();
        }
        GlobalDestruction.runGlobalDestruction();
        Time.cancelCurrentAlarm();
        PerlSignalQueue.clearSignals();
        RuntimeIO.closeAllHandles();
        NetSSLeay.resetState();
        MortalList.clearCurrentRuntimeState();
    }

    private void replaceRuntimeState() {
        executionState = new ExecutionRuntimeState();
        regexState = new RuntimeRegexState();
        mroState = new MroRuntimeState();
        globalState = new GlobalRuntimeState();
        runtimeCodeState = new RuntimeCodeRuntimeState();
        compilationState = new CompilationRuntimeState();
        sourceMapperState = new ByteCodeSourceMapper.State();
        filterState = new FilterRuntimeState();
        timeState = new Time.State();
        signalState = new PerlSignalQueue.State();
        randomState = new Random.State();
        dataSectionState = new DataSection.State();
        ioRegistryState = new IORuntimeRegistryState();
        fileTestState = new FileTestOperator.State();
        debugState = new DebugRuntimeState();
        diamondIOState = new DiamondIO.State();
        nativeState = new ExtendedNativeUtils.State();
        lifecycleState = new LifecycleRuntimeState();
        nameNormalizerState = new NameNormalizer.State();

        flipFlopState.clear();
        scalarGlobState.clear();
        pointerPackState.clear();
        bObjectState.clear();
        netSslErrorQueue.clear();
        netSslState = new NetSSLeay.State();
        xsShimLoadingInProgress.clear();
        referenceAddresses.clear();
        stateVariableInitialized.clear();
        pendingForkOpen = null;
        libOriginalInc = null;
        storableLastOpInNetorder = false;
        currentDirectory = System.getProperty("user.dir");
        perlThreadContext = RuntimeContextType.SCALAR;
        perlThreadStackSize = 0;
        perlThreadExitOnly = false;
        resetStandardIOState();
    }

    private void resetStandardIOState() {
        ioOpenHandles.clear();
        standardIOGlobs.clear();
        hiddenStandardIOGlobs.clear();
        ioStdout = new RuntimeIO(new StandardIO(System.out, true));
        ioStderr = new RuntimeIO(new StandardIO(System.err, false));
        ioStderr.autoFlush = true;
        ioStdin = new RuntimeIO(new StandardIO(System.in));
        ioSelectedHandle = ioStdout;
        ioLastWrittenHandle = ioStdout;
        ioLastAccessedHandle = null;
        ioLastReadlineHandleName = null;
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

    private void releaseBinding() {
        activeBindings.decrementAndGet();
    }

    void sharedLockAcquired() { activeSharedLocks.incrementAndGet(); }
    void sharedLockReleased() { activeSharedLocks.decrementAndGet(); }
    boolean hasSharedLock() { return activeSharedLocks.get() > 0; }
    void sharedWaiterEntered() { activeSharedWaiters.incrementAndGet(); }
    void sharedWaiterExited() { activeSharedWaiters.decrementAndGet(); }

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
        private final PerlRuntime runtime;
        private boolean closed;

        private Binding(BindingFrame frame, Thread owner, PerlRuntime runtime) {
            this.frame = frame;
            this.owner = owner;
            this.runtime = runtime;
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
            runtime.releaseBinding();
        }
    }
}
