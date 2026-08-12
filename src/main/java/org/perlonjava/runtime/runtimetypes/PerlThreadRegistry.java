package org.perlonjava.runtime.runtimetypes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Runtime-family registry for platform threads created by Perl ithreads. */
public final class PerlThreadRegistry {
    private final AtomicLong nextId = new AtomicLong(1);
    private final ConcurrentHashMap<Long, PerlThreadControlBlock> threads = new ConcurrentHashMap<>();

    long allocateId() {
        return nextId.getAndIncrement();
    }

    void register(PerlThreadControlBlock thread) {
        PerlThreadControlBlock previous = threads.putIfAbsent(thread.id(), thread);
        if (previous != null) throw new IllegalStateException("Duplicate Perl thread id " + thread.id());
    }

    void remove(PerlThreadControlBlock thread) {
        threads.remove(thread.id(), thread);
    }

    public PerlThreadControlBlock get(long id) {
        return threads.get(id);
    }

    public List<PerlThreadControlBlock> snapshot() {
        List<PerlThreadControlBlock> result = new ArrayList<>(threads.values());
        result.sort(Comparator.comparingLong(PerlThreadControlBlock::id));
        return result;
    }

    public int size() {
        return threads.size();
    }
}
