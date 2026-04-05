package org.perlonjava.runtime.io;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.perlonjava.runtime.runtimetypes.RuntimeIO;

/**
 * Maps simulated file descriptor numbers to IOHandle objects.
 *
 * <p>Java doesn't expose real POSIX file descriptors. This table assigns
 * sequential integers starting from 3 (0, 1, 2 are reserved for
 * stdin, stdout, stderr) and allows lookup by FD number.
 *
 * <p>Used by:
 * <ul>
 *   <li>{@code fileno()} — to return a consistent FD for each handle</li>
 *   <li>4-arg {@code select()} — to map bit-vector bits back to handles</li>
 * </ul>
 *
 * <p>Thread-safe: uses ConcurrentHashMap and AtomicInteger.
 */
public class FileDescriptorTable {

    // Next FD number to assign.  0–2 are stdin/stdout/stderr.
    private static final AtomicInteger nextFd = new AtomicInteger(3);

    // FD number → IOHandle (for select() lookup)
    private static final ConcurrentHashMap<Integer, IOHandle> fdToHandle = new ConcurrentHashMap<>();

    // IOHandle identity → FD number (to avoid assigning multiple FDs to the same handle)
    private static final ConcurrentHashMap<Integer, Integer> handleToFd = new ConcurrentHashMap<>();

    /**
     * Register an IOHandle and return its FD number.
     * If the handle was already registered, returns the existing FD.
     *
     * @param handle the IOHandle to register
     * @return the file descriptor number
     */
    public static int register(IOHandle handle) {
        int identity = System.identityHashCode(handle);
        Integer existing = handleToFd.get(identity);
        if (existing != null) {
            return existing;
        }
        int fd = nextFd.getAndIncrement();
        fdToHandle.put(fd, handle);
        handleToFd.put(identity, fd);
        // Keep RuntimeIO in sync to prevent fd collisions with socket()/socketpair()
        RuntimeIO.advanceFilenoCounterPast(fd);
        return fd;
    }

    /**
     * Register an IOHandle at a specific FD number.
     * Used when wrapping an existing handle (e.g., for dup) to preserve its fd number.
     * Replaces any existing handle at that fd.
     *
     * @param fd     the file descriptor number to register at
     * @param handle the IOHandle to register
     */
    public static void registerAt(int fd, IOHandle handle) {
        // Remove any previous handle at this fd
        IOHandle oldHandle = fdToHandle.put(fd, handle);
        if (oldHandle != null) {
            handleToFd.remove(System.identityHashCode(oldHandle));
        }
        handleToFd.put(System.identityHashCode(handle), fd);
        // Ensure nextFd is past this fd
        nextFd.updateAndGet(current -> Math.max(current, fd + 1));
        RuntimeIO.advanceFilenoCounterPast(fd);
    }

    /**
     * Advances the nextFd counter past the given fd value.
     * Called by RuntimeIO.assignFileno() to keep the two fd allocation
     * systems in sync and prevent fd collisions.
     *
     * @param fd the fd value to advance past
     */
    public static void advancePast(int fd) {
        nextFd.updateAndGet(current -> Math.max(current, fd + 1));
    }

    /**
     * Returns the next fd number that would be allocated, without actually allocating it.
     * Useful as a fallback when the original fd is unknown.
     */
    public static int nextFdValue() {
        return nextFd.get();
    }

    /**
     * Look up an IOHandle by its FD number.
     *
     * @param fd the file descriptor number
     * @return the IOHandle, or null if not found
     */
    public static IOHandle getHandle(int fd) {
        return fdToHandle.get(fd);
    }

    /**
     * Remove a handle from the table (e.g., on close).
     *
     * @param fd the file descriptor number to remove
     */
    public static void unregister(int fd) {
        IOHandle handle = fdToHandle.remove(fd);
        if (handle != null) {
            handleToFd.remove(System.identityHashCode(handle));
        }
    }

    /**
     * Check if a read-end handle has data available without blocking.
     * Returns true if the handle is "ready for reading".
     *
     * @param handle the IOHandle to check
     * @return true if data is available or handle is at EOF/closed
     */
    public static boolean isReadReady(IOHandle handle) {
        if (handle instanceof InternalPipeHandle pipeHandle) {
            return pipeHandle.hasDataAvailable();
        }
        if (handle instanceof StandardIO) {
            // stdin: check System.in.available()
            try {
                return System.in.available() > 0;
            } catch (Exception e) {
                return false;
            }
        }
        // For unknown handle types, report as ready to avoid blocking
        return true;
    }

    /**
     * Check if a write-end handle can accept writes without blocking.
     *
     * @param handle the IOHandle to check
     * @return true if the handle can accept writes
     */
    public static boolean isWriteReady(IOHandle handle) {
        // Pipes and most handles can always accept writes (they buffer internally)
        return true;
    }
}
