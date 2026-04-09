package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.*;
import org.perlonjava.runtime.io.FileDescriptorTable;
import org.perlonjava.runtime.io.SocketIO;
import org.perlonjava.runtime.runtimetypes.*;

import java.io.IOException;
import java.nio.channels.*;
import java.util.*;

/**
 * Java XS backend for IO::Poll.
 * Implements the _poll() function that replaces the XS code in IO.xs (lines 254-286).
 * Constants are defined in IO/Poll.pm using 'use constant'.
 *
 * <p>The _poll() function wraps Java NIO Selector for sockets and
 * FileDescriptorTable readiness checks for non-socket handles.
 */
public class IOPoll extends PerlModuleBase {

    // Poll constants (matching POSIX poll.h values, same as IO/Poll.pm)
    private static final int POLLIN   = 0x0001;
    private static final int POLLPRI  = 0x0002;
    private static final int POLLOUT  = 0x0004;
    private static final int POLLERR  = 0x0008;
    private static final int POLLHUP  = 0x0010;
    private static final int POLLNVAL = 0x0020;

    public IOPoll() {
        super("IO::Poll", false);
    }

    public static void initialize() {
        IOPoll module = new IOPoll();
        try {
            module.registerMethod("_poll", "poll", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing IO::Poll method: " + e.getMessage());
        }
    }

    /**
     * _poll($timeout_ms, $fd1, $events1, $fd2, $events2, ...)
     *
     * <p>Polls file descriptors for I/O readiness.
     * Modifies the event_mask arguments in-place with returned events (revents).
     * Returns the count of ready file descriptors, or -1 on error.
     *
     * <p>This matches the XS _poll() semantics from IO.xs:
     * - On success (ret >= 0), ALL event mask args are overwritten with revents
     * - fd args are re-written with same value (matching sv_setiv behavior)
     */
    public static RuntimeList poll(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return new RuntimeScalar(-1).getList();
        }

        int timeoutMs = args.get(0).getInt();
        int nfd = (args.size() - 1) / 2;

        if (nfd == 0) {
            // No fds to poll — just sleep if timeout > 0
            if (timeoutMs > 0) {
                try { Thread.sleep(timeoutMs); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return new RuntimeScalar(0).getList();
        }

        // revents array: stores returned events for each fd pair
        int[] revents = new int[nfd];
        int[] fds = new int[nfd];
        int[] requestedEvents = new int[nfd];

        // Parse fd/events pairs
        for (int i = 0; i < nfd; i++) {
            int argIdx = 1 + i * 2;
            fds[i] = args.get(argIdx).getInt();
            requestedEvents[i] = args.get(argIdx + 1).getInt();
        }

        Selector selector = null;
        List<SelectableChannel> madeNonBlocking = new ArrayList<>();

        try {
            selector = Selector.open();
            Map<SelectableChannel, Integer> channelToIndex = new HashMap<>();
            List<Integer> nonSocketIndices = new ArrayList<>();
            int readyCount = 0;

            // Classify each fd
            for (int i = 0; i < nfd; i++) {
                int fd = fds[i];
                int events = requestedEvents[i];

                RuntimeIO rio = RuntimeIO.getByFileno(fd);
                if (rio == null) {
                    // Invalid fd — POLLNVAL
                    revents[i] = POLLNVAL;
                    readyCount++;
                    continue;
                }

                // Check if it's a socket handle (unwrap LayeredIOHandle if needed)
                SocketIO socketIO = getSocketIO(rio);

                if (socketIO != null) {
                    SelectableChannel ch = socketIO.getSelectableChannel();
                    if (ch == null) {
                        // Socket without selectable channel — treat as ready
                        if ((events & (POLLIN | POLLPRI)) != 0) revents[i] |= POLLIN;
                        if ((events & POLLOUT) != 0) revents[i] |= POLLOUT;
                        if (revents[i] != 0) readyCount++;
                        continue;
                    }

                    // Configure non-blocking for NIO selection
                    if (ch.isBlocking()) {
                        ch.configureBlocking(false);
                        madeNonBlocking.add(ch);
                    }

                    int ops = 0;
                    if ((events & (POLLIN | POLLPRI)) != 0) {
                        ops |= (ch instanceof ServerSocketChannel)
                                ? SelectionKey.OP_ACCEPT
                                : SelectionKey.OP_READ;
                    }
                    if ((events & POLLOUT) != 0) {
                        if (ch instanceof SocketChannel sc && sc.isConnectionPending()) {
                            ops |= SelectionKey.OP_CONNECT;
                        } else {
                            ops |= SelectionKey.OP_WRITE;
                        }
                    }

                    if (ops != 0) {
                        ch.register(selector, ops);
                        channelToIndex.put(ch, i);
                    }
                } else {
                    // Non-socket handle — check immediate readiness
                    if ((events & (POLLIN | POLLPRI)) != 0
                            && FileDescriptorTable.isReadReady(rio.ioHandle)) {
                        revents[i] |= POLLIN;
                    }
                    if ((events & POLLOUT) != 0
                            && FileDescriptorTable.isWriteReady(rio.ioHandle)) {
                        revents[i] |= POLLOUT;
                    }
                    if (revents[i] != 0) {
                        readyCount++;
                    } else {
                        nonSocketIndices.add(i);
                    }
                }
            }

            // If some handles already ready and no NIO channels need polling, done
            if (readyCount > 0 && channelToIndex.isEmpty() && nonSocketIndices.isEmpty()) {
                writeResults(args, nfd, fds, revents);
                return new RuntimeScalar(readyCount).getList();
            }

            // Poll loop: check NIO selector and pollable fds
            long deadlineMs = (timeoutMs < 0) ? Long.MAX_VALUE
                    : System.currentTimeMillis() + timeoutMs;
            long pollIntervalMs = 10;

            while (readyCount == 0) {
                // NIO selector
                if (!channelToIndex.isEmpty()) {
                    long remainMs = Math.min(pollIntervalMs,
                            Math.max(0, deadlineMs - System.currentTimeMillis()));
                    if (timeoutMs < 0 && nonSocketIndices.isEmpty()) {
                        selector.select(pollIntervalMs);
                    } else {
                        selector.select(Math.max(1, remainMs));
                    }

                    for (SelectionKey key : selector.selectedKeys()) {
                        Integer idx = channelToIndex.get(key.channel());
                        if (idx == null) continue;
                        int readyOps = key.readyOps();

                        if ((readyOps & (SelectionKey.OP_READ | SelectionKey.OP_ACCEPT)) != 0) {
                            revents[idx] |= POLLIN;
                        }
                        if ((readyOps & (SelectionKey.OP_WRITE | SelectionKey.OP_CONNECT)) != 0) {
                            revents[idx] |= POLLOUT;
                        }
                        if (revents[idx] != 0) readyCount++;
                    }
                    selector.selectedKeys().clear();
                    if (readyCount > 0) break;
                }

                // Pollable non-socket fds
                for (int idx : nonSocketIndices) {
                    int fd = fds[idx];
                    int events = requestedEvents[idx];
                    RuntimeIO rio = RuntimeIO.getByFileno(fd);
                    if (rio == null) continue;

                    if ((events & (POLLIN | POLLPRI)) != 0
                            && FileDescriptorTable.isReadReady(rio.ioHandle)) {
                        revents[idx] |= POLLIN;
                    }
                    if ((events & POLLOUT) != 0
                            && FileDescriptorTable.isWriteReady(rio.ioHandle)) {
                        revents[idx] |= POLLOUT;
                    }
                    if (revents[idx] != 0) readyCount++;
                }
                if (readyCount > 0) break;

                // Check timeout
                if (timeoutMs >= 0 && System.currentTimeMillis() >= deadlineMs) break;

                // Sleep before next poll if no NIO channels
                if (channelToIndex.isEmpty()) {
                    try {
                        long sleepMs = Math.min(pollIntervalMs,
                                Math.max(1, deadlineMs - System.currentTimeMillis()));
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            // Write results back to args (in-place modification)
            writeResults(args, nfd, fds, revents);
            return new RuntimeScalar(readyCount).getList();

        } catch (IOException e) {
            return new RuntimeScalar(-1).getList();
        } finally {
            if (selector != null) {
                try { selector.close(); } catch (IOException ignored) {}
            }
            for (SelectableChannel ch : madeNonBlocking) {
                try { ch.configureBlocking(true); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Write poll results back into the args array in-place.
     * XS _poll() overwrites all fd/events pairs: fd stays same, events becomes revents.
     */
    private static void writeResults(RuntimeArray args, int nfd, int[] fds, int[] revents) {
        for (int i = 0; i < nfd; i++) {
            int argIdx = 1 + i * 2;
            args.get(argIdx).set(new RuntimeScalar(fds[i]));        // re-write fd
            args.get(argIdx + 1).set(new RuntimeScalar(revents[i])); // overwrite with revents
        }
    }

    /**
     * Extract SocketIO from a RuntimeIO, unwrapping LayeredIOHandle if needed.
     */
    private static SocketIO getSocketIO(RuntimeIO rio) {
        if (rio.ioHandle instanceof SocketIO socketIO) {
            return socketIO;
        }
        if (rio.ioHandle instanceof org.perlonjava.runtime.io.LayeredIOHandle layered) {
            if (layered.getDelegate() instanceof SocketIO socketIO) {
                return socketIO;
            }
        }
        return null;
    }
}
