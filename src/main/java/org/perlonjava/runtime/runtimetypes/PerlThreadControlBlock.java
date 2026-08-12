package org.perlonjava.runtime.runtimetypes;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.ArrayList;
import java.util.List;

/** Internal ownership and completion record for one Perl platform thread. */
public final class PerlThreadControlBlock {
    public enum State { NEW, RUNNING, COMPLETED, FAILED, JOINED, DETACHED }

    @FunctionalInterface
    public interface EntryPoint {
        RuntimeBase run(PerlRuntime childRuntime) throws Throwable;
    }

    public record Completion(RuntimeBase value, Throwable error) {}

    private final long id;
    private final long parentId;
    private final PerlThreadRegistry registry;
    private final PerlRuntime childRuntime;
    private final EntryPoint entryPoint;
    private final CountDownLatch finished = new CountDownLatch(1);
    private final AtomicBoolean joinClaimed = new AtomicBoolean();
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
        this.childRuntime = parent.snapshotCloneForThread(registry, id).runtime();
        registry.register(this);
    }

    private PerlThreadControlBlock(PerlRuntime parent, RuntimeScalar code, RuntimeArray args, int context) {
        Objects.requireNonNull(parent, "parent");
        this.registry = parent.threadRegistry();
        this.id = registry.allocateId();
        this.parentId = parent.perlThreadId();
        PerlRuntime.ThreadSnapshot snapshot = parent.snapshotCloneForThread(registry, id);
        this.childRuntime = snapshot.runtime();

        List<RuntimeBase> roots = new ArrayList<>(args.size() + 1);
        roots.add(Objects.requireNonNull(code, "code"));
        for (int i = 0; i < args.size(); i++) roots.add(args.get(i));
        List<RuntimeBase> cloned = snapshot.cloner().cloneRoots(roots);
        RuntimeScalar childCode = (RuntimeScalar) cloned.getFirst();
        RuntimeArray childArgs = new RuntimeArray();
        for (int i = 1; i < cloned.size(); i++) childArgs.push(cloned.get(i).scalar());
        this.entryPoint = runtime -> {
            RuntimeList values = RuntimeCode.apply(childCode, childArgs, context);
            RuntimeArray retained = new RuntimeArray();
            for (RuntimeBase value : values.elements) retained.push(value.scalar());
            return retained;
        };
        registry.register(this);
    }

    public static PerlThreadControlBlock create(PerlRuntime parent, EntryPoint entryPoint) {
        return new PerlThreadControlBlock(parent, entryPoint);
    }

    /** Create a Perl thread, cloning its CODE and arguments with the runtime snapshot graph. */
    public static PerlThreadControlBlock create(
            PerlRuntime parent, RuntimeScalar code, RuntimeArray args, int context) {
        return new PerlThreadControlBlock(parent, code, args, context);
    }

    public synchronized PerlThreadControlBlock start() {
        if (state != State.NEW) throw new IllegalStateException("Thread already started");
        state = State.RUNNING;
        platformThread = Thread.ofPlatform().name("perl-ithread-" + id).unstarted(this::run);
        platformThread.start();
        return this;
    }

    private void run() {
        try {
            Outcome outcome = childRuntime.execute(() -> {
                RuntimeBase value = null;
                Throwable failure = null;
                try {
                    value = entryPoint.run(childRuntime);
                } catch (Throwable thrown) {
                    PerlThreadExitException exit = findThreadExit(thrown);
                    if (exit != null) value = exit.values();
                    else failure = thrown;
                }
                try {
                    SpecialBlock.runEndBlocks(false);
                } catch (Throwable endFailure) {
                    if (failure == null) failure = endFailure;
                }
                return new Outcome(value, failure);
            });
            result = outcome.value();
            finish(outcome.error() == null ? State.COMPLETED : State.FAILED, outcome.error());
        } catch (Throwable failure) {
            finish(State.FAILED, failure);
        } finally {
            finished.countDown();
            if (detached) {
                registry.remove(this);
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
            throw new IllegalStateException("Thread has already been joined");
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
            throw new IllegalStateException("Cannot detach a joined thread");
        }
        if (detached) throw new IllegalStateException("Thread is already detached");
        detached = true;
        if (finished.getCount() == 0) {
            state = State.DETACHED;
            registry.remove(this);
        }
    }

    public long id() { return id; }
    public long parentId() { return parentId; }
    public State state() { return state; }
    public boolean isRunning() { return state == State.NEW || state == State.RUNNING; }
    public boolean isJoinable() { return !detached && finished.getCount() == 0 && state != State.JOINED; }
    public boolean isDetached() { return detached; }
    public PerlRuntime childRuntime() { return childRuntime; }
    public Thread platformThread() { return platformThread; }
    public Throwable error() { return error; }

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
}
