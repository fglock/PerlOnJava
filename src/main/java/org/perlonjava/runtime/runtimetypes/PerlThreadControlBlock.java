package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.runtime.regex.RuntimeRegex;
import org.perlonjava.runtime.operators.WarnDie;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.ArrayList;
import java.util.List;

/** Internal ownership and completion record for one Perl ithread. */
public final class PerlThreadControlBlock {
    public enum State { NEW, RUNNING, JOINING, COMPLETED, FAILED, JOINED, DETACHED }

    @FunctionalInterface
    public interface EntryPoint {
        RuntimeBase run(PerlRuntime childRuntime) throws Throwable;
    }

    public record Completion(RuntimeBase value, Throwable error) {}

    private final long id;
    private final long parentId;
    private final PerlThreadRegistry registry;
    private volatile PerlRuntime childRuntime;
    private volatile EntryPoint entryPoint;
    private volatile Runnable entryCleanup;
    private final int context;
    private final long stackSize;
    private final RuntimeIO parentErrorOutput;
    private final CountDownLatch finished = new CountDownLatch(1);
    private final AtomicBoolean joinClaimed = new AtomicBoolean();
    private final AtomicBoolean abnormalTerminationReported = new AtomicBoolean();
    private volatile State state = State.NEW;
    private volatile RuntimeBase result;
    private volatile Throwable error;
    private volatile boolean detached;
    private Thread platformThread;

    private PerlThreadControlBlock(PerlRuntime parent, EntryPoint entryPoint) {
        Objects.requireNonNull(parent, "parent");
        this.registry = parent.threadRegistry();
        this.id = registry.allocateId();
        this.parentId = parent.perlThreadId();
        this.entryPoint = Objects.requireNonNull(entryPoint, "entryPoint");
        this.childRuntime = parent.snapshotCloneForThread(
                registry, id, java.util.List.of()).runtime();
        this.context = RuntimeContextType.SCALAR;
        this.stackSize = parent.defaultPerlThreadStackSize();
        this.parentErrorOutput = RuntimeIO.getStderr();
        childRuntime.setPerlThreadContext(context);
        childRuntime.setPerlThreadStackSize(stackSize);
        childRuntime.setPerlThreadExitOnly(parent.defaultPerlThreadExitOnly());
        registry.register(this);
    }

    private PerlThreadControlBlock(PerlRuntime parent, RuntimeScalar code, RuntimeArray args,
                                   int context, long stackSize, boolean exitOnly) {
        Objects.requireNonNull(parent, "parent");
        this.registry = parent.threadRegistry();
        this.id = registry.allocateId();
        this.parentId = parent.perlThreadId();
        List<RuntimeBase> roots = new ArrayList<>(args.size() + 1);
        roots.add(Objects.requireNonNull(code, "code"));
        for (int i = 0; i < args.size(); i++) roots.add(args.get(i));
        PerlRuntime.RootSnapshot snapshot = parent.snapshotCloneForThread(registry, id, roots);
        this.childRuntime = snapshot.runtime();
        this.context = context;
        this.stackSize = stackSize;
        this.parentErrorOutput = RuntimeIO.getStderr();
        childRuntime.setPerlThreadContext(context);
        childRuntime.setPerlThreadStackSize(stackSize);
        childRuntime.setPerlThreadExitOnly(exitOnly);

        List<RuntimeBase> cloned = snapshot.roots();
        RuntimeScalar childCode = (RuntimeScalar) cloned.getFirst();
        boolean releaseEntryCode = code.globalCodeRefFqn == null;
        RuntimeArray childArgs = new RuntimeArray();
        for (int i = 1; i < cloned.size(); i++) childArgs.push(cloned.get(i).scalar());
        this.entryPoint = runtime -> {
            RuntimeList values = RuntimeCode.apply(childCode, childArgs, context);
            RuntimeArray retained = new RuntimeArray();
            for (RuntimeBase value : values.elements) retained.push(value.scalar());
            return retained;
        };
        this.entryCleanup = () -> {
            // The thread invocation owns an anonymous entry CV independently
            // of its parent. Releasing it at thread end drops captured child
            // lexicals before END, while named package CVs remain stash-owned.
            if (releaseEntryCode) {
                if (childCode.value instanceof RuntimeCode entryCode) {
                    RuntimeScalar[] capturedScalars = entryCode.capturedScalars;
                    if (capturedScalars != null) {
                        for (RuntimeScalar captured : capturedScalars) {
                            // These pads were cloned from the parent's active
                            // scope, but the child has no enclosing invocation
                            // that can later retire them. Thread termination is
                            // their scope boundary in this runtime.
                            // A shared scalar is the parent's canonical storage,
                            // not a child-owned cloned pad. Releasing the child
                            // closure must drop only its capture; marking that
                            // canonical slot scope-exited lets child teardown
                            // destroy/clear a value the parent still owns.
                            if (!captured.threadShared) {
                                captured.scopeExited = true;
                            }
                        }
                    }
                    entryCode.releaseCaptures();
                }
                RuntimeScalar.scopeExitCleanup(childCode);
            }
            MortalList.flush();
        };
        registry.register(this);
    }

    public static PerlThreadControlBlock create(PerlRuntime parent, EntryPoint entryPoint) {
        return new PerlThreadControlBlock(parent, entryPoint);
    }

    /** Create a Perl thread, cloning its CODE and arguments with the runtime snapshot graph. */
    public static PerlThreadControlBlock create(
            PerlRuntime parent, RuntimeScalar code, RuntimeArray args, int context) {
        return new PerlThreadControlBlock(parent, code, args, context,
                parent.defaultPerlThreadStackSize(), parent.defaultPerlThreadExitOnly());
    }

    public static PerlThreadControlBlock create(
            PerlRuntime parent, RuntimeScalar code, RuntimeArray args, int context,
            long stackSize, boolean exitOnly) {
        return new PerlThreadControlBlock(parent, code, args, context, stackSize, exitOnly);
    }

    public synchronized PerlThreadControlBlock start() {
        if (state != State.NEW) throw new IllegalStateException("Thread already started");
        state = State.RUNNING;
        platformThread = PerlThreadExecutionPolicy.configured()
                .effectiveForStackSize(stackSize)
                .unstarted(id, stackSize, this::run);
        platformThread.start();
        return this;
    }

    private void run() {
        try {
            Outcome outcome = childRuntime.execute(() -> {
                RuntimeBase value = null;
                Throwable failure = null;
                try {
                    EntryPoint work = entryPoint;
                    if (work == null) throw new IllegalStateException("Thread entry point was released early");
                    value = work.run(childRuntime);
                } catch (Throwable thrown) {
                    PerlThreadExitException exit = findThreadExit(thrown);
                    if (exit != null) value = exit.values();
                    else {
                        PerlExitException processExit = findProcessExit(thrown);
                        if (processExit != null) {
                            registry.requestProcessExit(processExit.getExitCode());
                        }
                        failure = thrown;
                    }
                }
                if (failure != null) {
                    RuntimeScalar warningSlot = GlobalVariable.getGlobalHash("main::SIG")
                            .get("__WARN__");
                    RuntimeScalar retainedWarningHandler = retainedWarningHandler(failure);
                    if (retainedWarningHandler == null) {
                        retainedWarningHandler = childRuntime.executionState()
                                .pendingThreadWarningHandler;
                    }
                    childRuntime.executionState().pendingThreadWarningHandler = null;
                    RuntimeScalar warningHandler = warningSlot;
                    RuntimeScalar savedWarningSlot = null;
                    if ((warningHandler == null || !warningHandler.getDefinedBoolean())
                            && retainedWarningHandler != null
                            && retainedWarningHandler.getDefinedBoolean()) {
                        savedWarningSlot = warningSlot == null
                                ? new RuntimeScalar() : new RuntimeScalar(warningSlot);
                        if (warningSlot == null) {
                            GlobalVariable.getGlobalHash("main::SIG")
                                    .put("__WARN__", new RuntimeScalar(retainedWarningHandler));
                            warningSlot = GlobalVariable.getGlobalHash("main::SIG").get("__WARN__");
                        } else {
                            warningSlot.set(retainedWarningHandler);
                        }
                        warningHandler = warningSlot;
                    }
                    if (warningHandler != null && warningHandler.getDefinedBoolean()) {
                        try {
                            WarnDie.warn(new RuntimeScalar(
                                            "Thread " + id + " terminated abnormally: "
                                                    + ErrorMessageUtil.stringifyException(failure)),
                                    new RuntimeScalar());
                        } catch (PerlThreadExitException threadExit) {
                            value = threadExit.values();
                            failure = null;
                        } catch (PerlExitException processExit) {
                            registry.requestProcessExit(processExit.getExitCode());
                            failure = processExit;
                        } finally {
                            if (savedWarningSlot != null && warningSlot != null) {
                                warningSlot.set(savedWarningSlot);
                            }
                        }
                    }
                    if (retainedWarningHandler != null) {
                        RuntimeScalar.scopeExitCleanup(retainedWarningHandler);
                    }
                }
                Runnable cleanup = entryCleanup;
                if (cleanup != null) cleanup.run();
                MortalList.flushDeferredCapturesBeforeEnd();
                try {
                    SpecialBlock.runEndBlocks(false);
                } catch (Throwable endFailure) {
                    if (failure == null) failure = endFailure;
                } finally {
                    MortalList.flushDeferredCaptures();
                    RuntimeRegex.emitCurrentRuntimeDebugFreeTraces();
                }
                GlobalDestruction.runGlobalDestruction();
                return new Outcome(value, failure);
            });
            result = outcome.value();
            finish(outcome.error() == null ? State.COMPLETED : State.FAILED, outcome.error());
        } catch (Throwable failure) {
            finish(State.FAILED, failure);
        } finally {
            finished.countDown();
            if (detached) {
                reportAbnormalTermination();
                registry.remove(this);
                releaseTerminalResources();
            }
        }
    }

    private synchronized void finish(State terminal, Throwable failure) {
        error = failure;
        state = detached ? State.DETACHED : terminal;
    }

    public Completion join() throws InterruptedException {
        if (detached) throw new IllegalStateException("Cannot join a detached thread");
        if (!joinClaimed.compareAndSet(false, true)) {
            throw new IllegalStateException("Thread already joined at");
        }
        boolean success = false;
        try {
            finished.await();
            synchronized (this) {
                if (detached) throw new IllegalStateException("Cannot join a detached thread");
                state = State.JOINED;
            }
            registry.remove(this);
            success = true;
            return new Completion(result, error);
        } finally {
            if (!success && finished.getCount() != 0) joinClaimed.set(false);
        }
    }

    public synchronized void detach() {
        if (joinClaimed.get() || state == State.JOINED) {
            throw new IllegalStateException("Cannot detach a joined thread at");
        }
        if (detached) throw new IllegalStateException("Thread already detached");
        detached = true;
        if (finished.getCount() == 0) {
            state = State.DETACHED;
            reportAbnormalTermination();
            registry.remove(this);
            releaseTerminalResources();
        }
    }

    public long id() { return id; }
    public long parentId() { return parentId; }
    public State state() { return state; }
    public boolean isRunning() { return state == State.NEW || state == State.RUNNING; }
    public boolean isJoining() { return state == State.JOINING; }
    public boolean isJoinable() { return !detached && finished.getCount() == 0 && state != State.JOINED; }
    public boolean isDetached() { return detached; }
    public PerlRuntime childRuntime() { return childRuntime; }
    public Thread platformThread() { return platformThread; }
    public String javaThreadName() { return platformThread == null ? null : platformThread.getName(); }
    public boolean isVirtualThread() { return platformThread != null && platformThread.isVirtual(); }
    public Throwable error() { return error; }
    public int context() { return context; }
    public long stackSize() { return stackSize; }

    public synchronized void beginJoinWait() {
        if (state == State.RUNNING) state = State.JOINING;
    }

    public synchronized void endJoinWait() {
        if (state == State.JOINING) state = State.RUNNING;
    }

    /** Deliver a Perl signal in the target runtime at its next safe point. */
    public void signal(String signal) {
        if (!isRunning()) return;
        PerlRuntime runtime = childRuntime;
        if (runtime == null) return;
        PerlSignalQueue.enqueue(runtime.signalState, signal);
        Thread javaThread = platformThread;
        if (javaThread != null) javaThread.interrupt();
    }

    public boolean hasSignalHandler(String signal) {
        PerlRuntime runtime = childRuntime;
        if (runtime == null) return false;
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar handler = GlobalVariable.getGlobalHash("main::SIG").get(signal);
            return handler != null && handler.getDefinedBoolean();
        }
    }

    /**
     * Drop the completed child's package graph after its return values have
     * been cloned into the joining runtime. Terminal thread-object aliases
     * retain this control block's state and error, but must not retain an
     * entire interpreter snapshot indefinitely.
     */
    public void releaseTerminalResources() {
        PerlRuntime runtime;
        RuntimeBase terminalResult;
        synchronized (this) {
            if (state != State.JOINED && state != State.DETACHED) return;
            terminalResult = result;
            result = null;
            entryPoint = null;
            entryCleanup = null;
            runtime = childRuntime;
            childRuntime = null;
        }
        if (runtime != null) {
            runtime.execute(() -> {
                if (terminalResult instanceof RuntimeArray values) {
                    MortalList.scopeExitCleanupArray(values);
                }
                MortalList.flush();
                MortalList.flushDeferredCaptures();
                GlobalDestruction.runGlobalDestruction();
            });
            runtime.close();
        }
    }

    private void reportAbnormalTermination() {
        Throwable failure = error;
        if (failure == null || !abnormalTerminationReported.compareAndSet(false, true)) return;
        String message = failure.getMessage();
        if (message == null || message.isEmpty()) message = failure.toString();
        parentErrorOutput.write("Thread " + id + " terminated abnormally: " + message + "\n");
    }

    private record Outcome(RuntimeBase value, Throwable error) {}

    private static PerlThreadExitException findThreadExit(Throwable thrown) {
        Throwable current = thrown;
        while (current != null) {
            if (current instanceof PerlThreadExitException exit) return exit;
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return null;
    }

    private static PerlExitException findProcessExit(Throwable thrown) {
        Throwable current = thrown;
        while (current != null) {
            if (current instanceof PerlExitException exit) return exit;
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return null;
    }

    private static RuntimeScalar retainedWarningHandler(Throwable thrown) {
        Throwable current = thrown;
        while (current != null) {
            if (current instanceof PerlDieException die
                    && die.getWarningHandler() != null) {
                return die.getWarningHandler();
            }
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return null;
    }
}
