package org.perlonjava.runtime.runtimetypes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Runtime-family registry for platform threads created by Perl ithreads. */
public final class PerlThreadRegistry {
    private final AtomicLong nextId = new AtomicLong(1);
    private final AtomicLong defaultStackSize = new AtomicLong();
    private final AtomicBoolean defaultExitOnly = new AtomicBoolean();
    private final AtomicInteger requestedProcessExit = new AtomicInteger(Integer.MIN_VALUE);
    private final ConcurrentHashMap<Long, PerlThreadControlBlock> threads = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<String>> userUnicodeProperties =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, PerlThreadControlBlock> terminalThreads = new ConcurrentHashMap<>();

    public long allocateId() {
        return nextId.getAndIncrement();
    }

    void register(PerlThreadControlBlock thread) {
        PerlThreadControlBlock previous = threads.putIfAbsent(thread.id(), thread);
        if (previous != null) throw new IllegalStateException("Duplicate Perl thread id " + thread.id());
    }

    void remove(PerlThreadControlBlock thread) {
        if (threads.get(thread.id()) != thread) return;
        // Publish the retained terminal record before withdrawing the active
        // record. Readers of getKnown() must never observe a gap between the
        // two maps while a child completes, joins, or detaches.
        terminalThreads.put(thread.id(), thread);
        if (!threads.remove(thread.id(), thread)) terminalThreads.remove(thread.id(), thread);
    }

    public PerlThreadControlBlock get(long id) {
        return threads.get(id);
    }

    /** Lookup including joined/detached records retained for existing object aliases. */
    public PerlThreadControlBlock getKnown(long id) {
        PerlThreadControlBlock active = threads.get(id);
        return active != null ? active : terminalThreads.get(id);
    }

    public List<PerlThreadControlBlock> snapshot() {
        List<PerlThreadControlBlock> result = new ArrayList<>(threads.values());
        result.sort(Comparator.comparingLong(PerlThreadControlBlock::id));
        return result;
    }

    public int size() {
        return threads.size();
    }

    public long defaultStackSize() { return defaultStackSize.get(); }
    public void setDefaultStackSize(long value) { defaultStackSize.set(value); }
    public boolean defaultExitOnly() { return defaultExitOnly.get(); }
    public void setDefaultExitOnly(boolean value) { defaultExitOnly.set(value); }

    /** Publish the first process-wide exit requested by a non-thread-only child. */
    public void requestProcessExit(int status) {
        requestedProcessExit.compareAndSet(Integer.MIN_VALUE, status);
    }

    public int requestedProcessExitOr(int fallback) {
        int requested = requestedProcessExit.get();
        return requested == Integer.MIN_VALUE ? fallback : requested;
    }

    void clearTerminalStateForReset() {
        if (!threads.isEmpty() || !userUnicodeProperties.isEmpty()) {
            throw new IllegalStateException("Thread registry is not quiescent");
        }
        terminalThreads.clear();
        nextId.set(1);
        defaultStackSize.set(0);
        defaultExitOnly.set(false);
        requestedProcessExit.set(Integer.MIN_VALUE);
    }

    /** Format Perl's process-exit diagnostic for attached, unjoined children. */
    public String activeThreadExitWarning() {
        int running = 0;
        int finished = 0;
        for (PerlThreadControlBlock thread : threads.values()) {
            if (thread.isDetached()) continue;
            if (thread.isRunning()) running++;
            else finished++;
        }
        if (running == 0 && finished == 0) return "";
        return "Perl exited with active threads:\n"
                + "\t" + running + " running and unjoined\n"
                + "\t" + finished + " finished and unjoined\n"
                + "\t0 running and detached\n";
    }

    /**
     * Serialize only simultaneous definitions of the same user Unicode property.
     * Different property names remain independent, matching Perl's regex lock
     * granularity without sharing a child's mutable regex cache.
     */
    public String resolveUserUnicodeProperty(String name, Supplier<String> resolver) {
        CompletableFuture<String> mine = new CompletableFuture<>();
        CompletableFuture<String> active = userUnicodeProperties.putIfAbsent(name, mine);
        if (active == null) {
            try {
                String result = resolver.get();
                mine.complete(result);
                return result;
            } catch (RuntimeException | Error failure) {
                mine.completeExceptionally(failure);
                throw failure;
            } finally {
                userUnicodeProperties.remove(name, mine);
            }
        }

        try {
            return active.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            throw new IllegalArgumentException(
                    "Timeout waiting for another thread to define \""
                            + shortPropertyName(name) + "\" in regex", timeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted waiting for user-defined Unicode property " + name,
                    interrupted);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("User-defined Unicode property failed: " + name,
                    cause);
        }
    }

    private static String shortPropertyName(String name) {
        int modeSeparator = name.indexOf('\0');
        if (modeSeparator >= 0) name = name.substring(0, modeSeparator);
        int separator = name.lastIndexOf("::");
        return separator >= 0 ? name.substring(separator + 2) : name;
    }
}
