package org.perlonjava.runtime.io;

import org.perlonjava.runtime.runtimetypes.RuntimeGlob;
import org.perlonjava.runtime.runtimetypes.RuntimeIO;

import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/** Runtime-owned descriptor, process, and abandoned-handle registries. */
public final class IORuntimeRegistryState {
    public final Map<Long, Process> childProcesses = new ConcurrentHashMap<>();
    public final Map<Long, Process> windowsChildProcesses = new ConcurrentHashMap<>();
    public final AtomicInteger nextFileno = new AtomicInteger(3);
    public final ConcurrentHashMap<Integer, RuntimeIO> filenoToIO = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<RuntimeIO, Integer> ioToFileno = new ConcurrentHashMap<>();
    public final ConcurrentLinkedQueue<Integer> recycledFds = new ConcurrentLinkedQueue<>();
    public final ReferenceQueue<RuntimeGlob> globGCQueue = new ReferenceQueue<>();
    public final ConcurrentHashMap<PhantomReference<RuntimeGlob>, RuntimeIO> phantomToIO =
            new ConcurrentHashMap<>();
    public final AtomicInteger nextFd = new AtomicInteger(3);
    public final ConcurrentHashMap<Integer, IOHandle> fdToHandle = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<Integer, Integer> handleToFd = new ConcurrentHashMap<>();
    public final Map<Integer, RuntimeIO> operatorFileDescriptors = new ConcurrentHashMap<>();

    public void clear() {
        childProcesses.clear();
        windowsChildProcesses.clear();
        nextFileno.set(3);
        filenoToIO.clear();
        ioToFileno.clear();
        recycledFds.clear();
        phantomToIO.clear();
        nextFd.set(3);
        fdToHandle.clear();
        handleToFd.clear();
        operatorFileDescriptors.clear();
        while (globGCQueue.poll() != null) {
            // Drain references owned by this closed runtime.
        }
    }
}
