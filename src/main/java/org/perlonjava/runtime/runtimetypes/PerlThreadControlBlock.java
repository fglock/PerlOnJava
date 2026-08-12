package org.perlonjava.runtime.runtimetypes;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

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

    public static PerlThreadControlBlock create(PerlRuntime parent, EntryPoint entryPoint) {
        return new PerlThreadControlBlock(parent, entryPoint);
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
            result = childRuntime.execute(() -> {
                try {
                    return entryPoint.run(childRuntime);
                } catch (RuntimeException | Error failure) {
                    throw failure;
                } catch (Throwable failure) {
                    throw new ThreadEntryException(failure);
                }
            });
            finish(State.COMPLETED, null);
        } catch (Throwable failure) {
            if (failure instanceof ThreadEntryException wrapped) failure = wrapped.getCause();
            finish(State.FAILED, failure);
        } finally {
            finished.countDown();
            if (detached) registry.remove(this);
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

    private static final class ThreadEntryException extends RuntimeException {
        ThreadEntryException(Throwable cause) { super(cause); }
    }
}
