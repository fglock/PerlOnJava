package org.perlonjava.runtime.operators;

import org.perlonjava.frontend.astnode.FormatLine;
import org.perlonjava.frontend.astnode.PictureLine;
import org.perlonjava.frontend.parser.StringParser;
import org.perlonjava.runtime.ForkOpenState;
import org.perlonjava.runtime.io.*;
import org.perlonjava.runtime.nativ.NativeUtils;
import org.perlonjava.runtime.nativ.ffm.FFMPosix;
import org.perlonjava.runtime.perlmodule.Socket;
import org.perlonjava.runtime.runtimetypes.*;

import java.io.File;
import java.io.IOException;
import java.net.*;
import java.net.StandardProtocolFamily;
import java.nio.channels.DatagramChannel;
import java.nio.channels.FileChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.perlonjava.runtime.runtimetypes.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.*;

public class IOOperator {
    // Simple socket option storage: key is "socketHashCode:level:optname", value is the option value
    private static final Map<String, Integer> globalSocketOptions = new ConcurrentHashMap<>();

    // File descriptor to RuntimeIO mapping for duplication support
    private static final Map<Integer, RuntimeIO> fileDescriptorMap = new ConcurrentHashMap<>();

    public static RuntimeScalar select(RuntimeList runtimeList, int ctx) {
        if (runtimeList.isEmpty()) {
            // select() with no args returns the currently selected filehandle.
            // In Perl 5 this returns a string name like "main::STDOUT".
            // We return the RuntimeIO wrapped as a GLOB scalar, which stringifies
            // to the glob name. This preserves the round-trip: select(select())
            // correctly restores the previous handle for tied handles too.
            return new RuntimeScalar(RuntimeIO.selectedHandle);
        }
        if (runtimeList.size() == 4) {
            // select RBITS,WBITS,EBITS,TIMEOUT (syscall)
            // Get the original scalars so we can modify bit vectors in-place
            // (Perl's select() modifies its first 3 args to reflect which fds are ready)
            RuntimeScalar rbits = runtimeList.elements.get(0).scalar();
            RuntimeScalar wbits = runtimeList.elements.get(1).scalar();
            RuntimeScalar ebits = runtimeList.elements.get(2).scalar();
            RuntimeScalar timeout = runtimeList.elements.get(3).scalar();

            // Special case: if all bit vectors are undef, just sleep
            if (!rbits.getDefinedBoolean() && !wbits.getDefinedBoolean() && !ebits.getDefinedBoolean()) {
                double sleepTime = timeout.getDouble();
                if (sleepTime > 0) {
                    Thread.interrupted();
                    try {
                        long millis = (long) (sleepTime * 1000);
                        int nanos = (int) ((sleepTime * 1000 - millis) * 1_000_000);
                        Thread.sleep(millis, nanos);
                    } catch (InterruptedException e) {
                        PerlSignalQueue.checkPendingSignals();
                        Thread.interrupted();
                        return new RuntimeScalar(0);
                    }
                }
                // Return 0 to indicate the sleep completed
                return new RuntimeScalar(0);
            }

            // Implement 4-arg select() using NIO Selector
            try {
                return selectWithNIO(rbits, wbits, ebits, timeout);
            } catch (Exception e) {
                getGlobalVariable("main::!").set(e.getMessage());
                return new RuntimeScalar(-1);
            }
        }
        // select FILEHANDLE (returns/sets current filehandle)
        RuntimeScalar fh = new RuntimeScalar(RuntimeIO.selectedHandle);
        RuntimeIO.selectedHandle = runtimeList.getFirst().getRuntimeIO();
        RuntimeIO.lastAccesseddHandle = RuntimeIO.selectedHandle;
        return fh;
    }

    /**
     * Implements 4-arg select() using Java NIO Selector.
     * Monitors file descriptors in the bit vectors for readiness.
     * Modifies the bit vectors in place to reflect which descriptors are ready.
     *
     * @param rbits   read bit vector (modified in place)
     * @param wbits   write bit vector (modified in place)
     * @param ebits   error bit vector (modified in place)
     * @param timeout timeout in seconds (undef = block forever, 0 = poll)
     * @return number of ready descriptors, or -1 on error
     */
    private static RuntimeScalar selectWithNIO(RuntimeScalar rbits, RuntimeScalar wbits,
                                                RuntimeScalar ebits, RuntimeScalar timeout) throws IOException {
        byte[] rdata = rbits.getDefinedBoolean() ? getVecBytes(rbits) : new byte[0];
        byte[] wdata = wbits.getDefinedBoolean() ? getVecBytes(wbits) : new byte[0];
        byte[] edata = ebits.getDefinedBoolean() ? getVecBytes(ebits) : new byte[0];
        int maxFd = Math.max(rdata.length, Math.max(wdata.length, edata.length)) * 8;

        Selector selector = Selector.open();
        List<SelectableChannel> madeNonBlocking = new ArrayList<>();

        try {
            Map<SelectableChannel, Integer> channelToFd = new HashMap<>();
            List<Integer> pollableFds = new ArrayList<>();
            int nonSocketReady = 0;

            for (int fd = 0; fd < maxFd; fd++) {
                boolean wantRead = isBitSet(rdata, fd);
                boolean wantWrite = isBitSet(wdata, fd);
                if (!wantRead && !wantWrite) continue;

                RuntimeIO rio = RuntimeIO.getByFileno(fd);
                if (rio == null) continue;

                if (rio.ioHandle instanceof SocketIO socketIO) {
                    SelectableChannel ch = socketIO.getSelectableChannel();
                    if (ch == null) {
                        // SSL-wrapped sockets have no NIO channel.
                        // Check readiness via InputStream.available() for reads,
                        // and assume always writable for writes.
                        boolean ready = false;
                        if (wantRead) {
                            try {
                                java.io.InputStream is = socketIO.getSocket() != null
                                        ? socketIO.getSocket().getInputStream() : null;
                                if (is != null && is.available() > 0) {
                                    ready = true;
                                } else {
                                    // For SSL sockets, available() may return 0 even when
                                    // data is waiting in the SSL layer. Assume readable
                                    // to avoid blocking in select() — worst case, the
                                    // subsequent read will block briefly.
                                    if (socketIO.getSocket() instanceof javax.net.ssl.SSLSocket) {
                                        ready = true;
                                    }
                                }
                            } catch (Exception e) {
                                ready = true; // Err on the side of reporting ready
                            }
                        }
                        if (wantWrite) {
                            ready = true; // SSL sockets are always writable
                        }
                        if (ready) {
                            nonSocketReady++;
                        }
                        continue;
                    }

                    if (ch.isBlocking()) {
                        ch.configureBlocking(false);
                        madeNonBlocking.add(ch);
                    }

                    int ops = 0;
                    if (wantRead) {
                        ops |= (ch instanceof ServerSocketChannel)
                                ? SelectionKey.OP_ACCEPT
                                : SelectionKey.OP_READ;
                    }
                    if (wantWrite) {
                        if (ch instanceof SocketChannel sc) {
                            // For non-blocking connects in progress, use OP_CONNECT.
                            // Perl's select() treats write-readiness as "connect complete",
                            // but Java NIO requires OP_CONNECT for pending connections.
                            if (sc.isConnectionPending()) {
                                ops |= SelectionKey.OP_CONNECT;
                            } else {
                                ops |= SelectionKey.OP_WRITE;
                            }
                        } else if (ch instanceof java.nio.channels.DatagramChannel) {
                            ops |= SelectionKey.OP_WRITE;
                        }
                    }

                    if (ops != 0) {
                        ch.register(selector, ops);
                        channelToFd.put(ch, fd);
                    }
                } else {
                    // Non-socket handles (files, pipes): check actual readiness
                    // using FileDescriptorTable.isReadReady/isWriteReady instead of
                    // assuming always-ready (which causes POE's event loop to busy-loop).
                    boolean ready = false;
                    if (wantRead && FileDescriptorTable.isReadReady(rio.ioHandle)) {
                        ready = true;
                    }
                    if (wantWrite && FileDescriptorTable.isWriteReady(rio.ioHandle)) {
                        ready = true;
                    }
                    if (ready) {
                        nonSocketReady++;
                    } else {
                        // Track pollable fds that aren't ready yet
                        pollableFds.add(fd);
                    }
                }
            }

            // Perform the select with polling for non-NIO handles
            double timeoutSec = timeout.getDefinedBoolean() ? timeout.getDouble() : -1;

            if (nonSocketReady > 0 && channelToFd.isEmpty()) {
                // Some non-socket handles already ready, no NIO channels — return immediately
            } else if (!channelToFd.isEmpty() || !pollableFds.isEmpty()) {
                // Poll loop: check both NIO selector and pollable fds
                long deadlineMs = (timeoutSec < 0) ? Long.MAX_VALUE
                        : System.currentTimeMillis() + (long) (timeoutSec * 1000);
                long pollIntervalMs = 10; // 10ms poll interval for pipes

                while (true) {
                    // Check NIO selector
                    if (!channelToFd.isEmpty()) {
                        long remainMs = Math.min(pollIntervalMs,
                                Math.max(0, deadlineMs - System.currentTimeMillis()));
                        if (timeoutSec < 0 && pollableFds.isEmpty()) {
                            selector.select(pollIntervalMs); // block with poll interval
                        } else {
                            selector.select(Math.max(1, remainMs));
                        }
                        if (!selector.selectedKeys().isEmpty()) break;
                    }

                    // Check pollable (non-NIO) fds
                    for (int fd : pollableFds) {
                        RuntimeIO rio = RuntimeIO.getByFileno(fd);
                        if (rio == null) continue;
                        boolean wantRead = isBitSet(rdata, fd);
                        boolean wantWrite = isBitSet(wdata, fd);
                        if (wantRead && FileDescriptorTable.isReadReady(rio.ioHandle)) {
                            nonSocketReady++;
                        }
                        if (wantWrite && FileDescriptorTable.isWriteReady(rio.ioHandle)) {
                            nonSocketReady++;
                        }
                    }
                    if (nonSocketReady > 0) break;

                    // Check timeout
                    if (timeoutSec >= 0 && System.currentTimeMillis() >= deadlineMs) break;

                    // Sleep before next poll if no NIO channels to select on
                    if (channelToFd.isEmpty()) {
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
            } else if (timeoutSec > 0) {
                // Nothing to monitor — just sleep for timeout
                try {
                    Thread.sleep((long) (timeoutSec * 1000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            // Build result bit vectors (same size as input)
            byte[] rresult = new byte[rdata.length];
            byte[] wresult = new byte[wdata.length];
            byte[] eresult = new byte[edata.length];
            int totalReady = 0;

            // Non-socket handles and SSL sockets (no NIO channel): set result bits
            for (int fd = 0; fd < maxFd; fd++) {
                RuntimeIO rio = RuntimeIO.getByFileno(fd);
                if (rio == null) continue;

                if (rio.ioHandle instanceof SocketIO socketIO) {
                    // Only handle SocketIO with null channel (SSL sockets)
                    if (socketIO.getSelectableChannel() != null) continue;

                    // SSL socket: set bits based on readiness
                    if (isBitSet(rdata, fd)) {
                        setBit(rresult, fd); totalReady++;
                    }
                    if (isBitSet(wdata, fd)) {
                        setBit(wresult, fd); totalReady++;
                    }
                    continue;
                }

                // Non-socket handles
                if (isBitSet(rdata, fd) && FileDescriptorTable.isReadReady(rio.ioHandle)) {
                    setBit(rresult, fd); totalReady++;
                }
                if (isBitSet(wdata, fd) && FileDescriptorTable.isWriteReady(rio.ioHandle)) {
                    setBit(wresult, fd); totalReady++;
                }
            }

            // Process selected keys
            for (SelectionKey key : selector.selectedKeys()) {
                Integer fd = channelToFd.get(key.channel());
                if (fd == null) continue;
                int readyOps = key.readyOps();

                if ((readyOps & (SelectionKey.OP_READ | SelectionKey.OP_ACCEPT)) != 0
                        && isBitSet(rdata, fd)) {
                    setBit(rresult, fd);
                    totalReady++;
                }
                // OP_CONNECT means the non-blocking connect completed — treat as write-ready.
                // Do NOT call finishConnect() here — leave the connection pending so that
                // a subsequent connect() call (as IO::Socket does) can detect the result
                // via finishConnect() and set $! appropriately (EISCONN or ECONNREFUSED).
                // This matches POSIX behavior where select() just reports readiness.
                if ((readyOps & (SelectionKey.OP_WRITE | SelectionKey.OP_CONNECT)) != 0 && isBitSet(wdata, fd)) {
                    setBit(wresult, fd);
                    totalReady++;
                }
            }

            // Modify the original scalars in place.
            // Only set back if the input had actual content (length > 0).
            // Empty string '' means "don't monitor" — no modification needed,
            // and attempting to set a read-only string literal would throw.
            if (rdata.length > 0) {
                rbits.set(new String(rresult, StandardCharsets.ISO_8859_1));
            }
            if (wdata.length > 0) {
                wbits.set(new String(wresult, StandardCharsets.ISO_8859_1));
            }
            if (edata.length > 0) {
                ebits.set(new String(eresult, StandardCharsets.ISO_8859_1));
            }

            return new RuntimeScalar(totalReady);

        } finally {
            // Close the selector first — this deregisters all keys,
            // allowing us to restore blocking mode
            selector.close();

            // Restore blocking mode for channels we modified
            for (SelectableChannel ch : madeNonBlocking) {
                try {
                    ch.configureBlocking(true);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * Extracts the raw bytes from a bit-vector scalar (as used by vec/select).
     */
    private static byte[] getVecBytes(RuntimeScalar scalar) {
        String s = scalar.toString();
        byte[] data = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) {
            data[i] = (byte) s.charAt(i);
        }
        return data;
    }

    /**
     * Tests whether bit 'fd' is set in a byte array (vec-style, little-endian bits within each byte).
     */
    private static boolean isBitSet(byte[] data, int fd) {
        int byteIndex = fd / 8;
        int bitIndex = fd % 8;
        return byteIndex < data.length && (data[byteIndex] & (1 << bitIndex)) != 0;
    }

    /**
     * Sets bit 'fd' in a byte array (vec-style, little-endian bits within each byte).
     */
    private static void setBit(byte[] data, int fd) {
        int byteIndex = fd / 8;
        int bitIndex = fd % 8;
        if (byteIndex < data.length) {
            data[byteIndex] |= (byte) (1 << bitIndex);
        }
    }

    public static RuntimeScalar seek(RuntimeScalar fileHandle, RuntimeList runtimeList) {
        RuntimeIO runtimeIO = fileHandle.getRuntimeIO();
        if (runtimeIO != null) {
            if (runtimeIO.ioHandle != null) {
                if (runtimeIO instanceof TieHandle tieHandle) {
                    return TieHandle.tiedSeek(tieHandle, runtimeList);
                }

                long position = runtimeList.getFirst().getLong();
                int whence = IOHandle.SEEK_SET; // Default to SEEK_SET

                // Check if whence parameter is provided
                if (runtimeList.size() > 1) {
                    whence = runtimeList.elements.get(1).scalar().getInt();
                }

                RuntimeIO.lastAccesseddHandle = runtimeIO;
                return runtimeIO.ioHandle.seek(position, whence);
            } else {
                return RuntimeIO.handleIOError("No file handle available for seek");
            }
        } else {
            return RuntimeIO.handleIOError("Unsupported scalar type for seek");
        }
    }

    /**
     * sysseek returns the new file position, or undef on failure.
     * A position of zero is returned as the string "0 but true".
     */
    public static RuntimeScalar sysseek(RuntimeScalar fileHandle, RuntimeList runtimeList) {
        RuntimeScalar seekResult = seek(fileHandle, runtimeList);
        if (!seekResult.getBoolean()) {
            return seekResult;
        }
        RuntimeScalar pos = tell(fileHandle);
        long p = pos.getLong();
        if (p < 0) {
            return RuntimeIO.handleIOError("sysseek: could not determine position");
        }
        if (p == 0) {
            return new RuntimeScalar("0 but true");
        }
        return pos;
    }

    public static RuntimeScalar getc(int ctx, RuntimeBase... args) {
        RuntimeScalar fileHandle;
        if (args.length < 1) {
            fileHandle = new RuntimeScalar("main::STDIN");
        } else {
            fileHandle = args[0].scalar();
        }

        RuntimeIO fh = fileHandle.getRuntimeIO();

        if (fh instanceof TieHandle tieHandle) {
            return TieHandle.tiedGetc(tieHandle);
        }

        if (fh.ioHandle != null) {
            return fh.ioHandle.read(1);
        }
        throw new PerlCompilerException("No input source available");
    }

    public static RuntimeScalar tell(RuntimeScalar fileHandle) {
        boolean argless = !fileHandle.getDefinedBoolean();
        RuntimeIO fh = fileHandle.getRuntimeIO();

        // If no explicit filehandle was provided (tell with no args),
        // fall back to the last accessed handle like Perl does.
        if (fh == null) {
            if (argless) {
                RuntimeIO last = RuntimeIO.lastAccesseddHandle;
                if (last != null) {
                    return last.tell();
                }
                GlobalVariable.getGlobalVariable("main::!").set(9);
                return new RuntimeScalar(-1);
            }
            GlobalVariable.getGlobalVariable("main::!").set(9);
            return new RuntimeScalar(-1);
        }

        // Update the last accessed filehandle
        RuntimeIO.lastAccesseddHandle = fh;

        if (fh instanceof TieHandle tieHandle) {
            return TieHandle.tiedTell(tieHandle);
        }

        if (fh.ioHandle == null || fh.ioHandle instanceof ClosedIOHandle) {
            GlobalVariable.getGlobalVariable("main::!").set(9);
            return new RuntimeScalar(-1);
        }
        return fh.tell();
    }

    public static RuntimeScalar binmode(RuntimeScalar fileHandle, RuntimeList runtimeList) {
        RuntimeIO fh = fileHandle.getRuntimeIO();
        // Handle undefined or invalid filehandle
        if (fh == null) {
            // Set $! to EBADF (Bad file descriptor) - errno 9
            GlobalVariable.getGlobalVariable("main::!")
                    .set(new RuntimeScalar(9));
            return scalarUndef;
        }

        if (fh instanceof TieHandle tieHandle) {
            return TieHandle.tiedBinmode(tieHandle, runtimeList);
        }

        String ioLayer = runtimeList.getFirst().toString();
        if (ioLayer.isEmpty()) {
            ioLayer = ":raw";
        }
        fh.binmode(ioLayer);
        return fileHandle;
    }

    public static RuntimeScalar fileno(int ctx, RuntimeBase... args) {
        RuntimeScalar fileHandle;
        if (args.length < 1) {
            throw new PerlCompilerException("Not enough arguments for fileno");
        } else {
            fileHandle = args[0].scalar();
        }

        RuntimeIO fh = fileHandle.getRuntimeIO();

        if (fh instanceof TieHandle tieHandle) {
            return TieHandle.tiedFileno(tieHandle);
        }

        if (fh == null || fh.ioHandle == null || fh.ioHandle instanceof ClosedIOHandle) {
            return RuntimeScalarCache.scalarUndef;
        }

        return fh.fileno();
    }


    /**
     * Opens a file and initialize a file handle.
     *
     * @return A RuntimeScalar indicating the result of the open operation.
     * @args file handle, file mode, arg list.
     */
    public static RuntimeScalar open(int ctx, RuntimeBase... args) {
        //public static RuntimeScalar open(RuntimeList runtimeList, RuntimeScalar fileHandle) {
//        open FILEHANDLE,MODE,EXPR
//        open FILEHANDLE,MODE,EXPR,LIST
//        open FILEHANDLE,MODE,REFERENCE
//        open FILEHANDLE,EXPR
//        open FILEHANDLE

        boolean ioDebug = System.getenv("JPERL_IO_DEBUG") != null;

        // Get the filehandle - this should be an lvalue RuntimeScalar
        // For array/hash elements like $fh0[0], this is the actual lvalue that can be modified
        // We assert it's a RuntimeScalar rather than calling .scalar() which would create a copy
        RuntimeScalar fileHandle = (RuntimeScalar) args[0];
        if (args.length < 2) {
            // 1-argument open: open FILEHANDLE
            // Uses $_ as the filename (with embedded mode prefix parsed from it)
            String fileName = getGlobalVariable("main::_").toString();
            RuntimeIO oneFh = RuntimeIO.open(fileName);
            if (oneFh == null) {
                return scalarUndef;
            }
            // Assign the IO handle to the filehandle glob (reuse the existing assignment logic below)
            RuntimeGlob targetGlob = null;
            if ((fileHandle.type == RuntimeScalarType.GLOB || fileHandle.type == RuntimeScalarType.GLOBREFERENCE) && fileHandle.value instanceof RuntimeGlob glob) {
                targetGlob = glob;
            } else if ((fileHandle.type == RuntimeScalarType.STRING || fileHandle.type == RuntimeScalarType.BYTE_STRING) && fileHandle.value instanceof String name) {
                if (!name.isEmpty() && name.matches("^[A-Za-z_][A-Za-z0-9_]*(::[A-Za-z_][A-Za-z0-9_]*)*$")) {
                    String fullName = name.contains("::") ? name : ("main::" + name);
                    targetGlob = GlobalVariable.getGlobalIO(fullName);
                    RuntimeScalar newGlob = new RuntimeScalar();
                    newGlob.type = RuntimeScalarType.GLOBREFERENCE;
                    newGlob.value = targetGlob;
                    fileHandle.set(newGlob);
                }
            }
            if (targetGlob != null) {
                targetGlob.setIO(oneFh);
            } else {
                RuntimeScalar newGlob = new RuntimeScalar();
                newGlob.type = RuntimeScalarType.GLOBREFERENCE;
                RuntimeGlob anonGlob = new RuntimeGlob(null).setIO(oneFh);
                newGlob.value = anonGlob;
                RuntimeIO.registerGlobForFdRecycling(anonGlob, oneFh);
                fileHandle.set(newGlob);
                fileHandle.ioOwner = true;
            }
            long pid = oneFh.getPid();
            if (pid > 0) return new RuntimeScalar(pid);
            return scalarTrue;
        }
        String mode = args[1].toString();
        RuntimeList runtimeList = new RuntimeList(Arrays.copyOfRange(args, 1, args.length));

        // Clear any stale pending fork-open state before new open operation
        ForkOpenState.clear();

        RuntimeIO fh;

        if (mode.contains("|")) {
            // Check for fork-open pattern: open FH, "-|" or open FH, "|-" with no command
            // This is the 2-arg piped open that normally forks in Perl
            if (args.length == 2 && (mode.equals("-|") || mode.equals("|-"))) {
                // Fork-open emulation: set pending state and return 0 (child PID)
                // The actual pipe will be created when exec() is called
                ForkOpenState.setPending(fileHandle, 0, "");
                if (ioDebug) {
                    System.err.println("[JPERL_IO_DEBUG] Fork-open emulation: pending state set for " + mode);
                    System.err.flush();
                }
                return new RuntimeScalar(0);  // Return 0 = "child" branch
            }
            // Pipe open with command (3+ arg form)
            fh = RuntimeIO.openPipe(runtimeList);
        } else if (args.length > 2) {
            // 3-argument open
            RuntimeScalar secondArg = args[2].scalar();

            // Check for filehandle duplication modes (<&, >&, >>&, +<&, +>&, +>>& and &= variants)
            if (mode.equals("<&") || mode.equals(">&") || mode.equals(">>&") ||
                    mode.equals("+<&") || mode.equals("+>&") || mode.equals("+>>&") ||
                    mode.equals("<&=") || mode.equals(">&=") || mode.equals(">>&=") ||
                    mode.equals("+<&=") || mode.equals("+>&=") || mode.equals("+>>&=")) {
                // Handle filehandle duplication
                String argStr = secondArg.toString();
                boolean isParsimonious = mode.endsWith("="); // &= modes reuse file descriptor

                if (ioDebug) {
                    System.err.println("[JPERL_IO_DEBUG] open dup-mode: mode=" + mode + " argStr=" + argStr +
                            " argType=" + secondArg.type);
                    System.err.flush();
                }

                // Check if it's a numeric file descriptor
                if (argStr.matches("^-?\\d+$")) {
                    int fd = Integer.parseInt(argStr);
                    // Handle numeric file descriptor duplication
                    RuntimeIO sourceHandle = fd >= 0 ? findFileHandleByDescriptor(fd) : null;
                    if (sourceHandle != null && sourceHandle.ioHandle != null) {
                        if (isParsimonious) {
                            // &= mode: non-owning wrapper sharing the same fd
                            fh = createBorrowedHandle(sourceHandle);
                        } else {
                            // & mode: create a new handle that duplicates the original
                            fh = duplicateFileHandle(sourceHandle);
                        }
                    } else {
                        // Match real Perl: negative fd -> return undef with empty $!,
                        // unknown non-negative fd -> return undef with $! = EBADF
                        if (fd >= 0) {
                            GlobalVariable.getGlobalVariable("main::!").set(9);
                        } else {
                            GlobalVariable.getGlobalVariable("main::!").set("");
                        }
                        fh = null;
                    }
                }
                // Check if it's a GLOB or GLOBREFERENCE
                else if (secondArg.type == RuntimeScalarType.GLOB || secondArg.type == RuntimeScalarType.GLOBREFERENCE) {
                    try {
                        RuntimeIO sourceHandle = secondArg.getRuntimeIO();
                        if (sourceHandle != null && sourceHandle.ioHandle != null) {
                            if (ioDebug) {
                                String srcFileno;
                                try {
                                    srcFileno = sourceHandle.ioHandle.fileno().toString();
                                } catch (Throwable t) {
                                    srcFileno = "<err>";
                                }
                                System.err.println("[JPERL_IO_DEBUG] open dup-mode sourceHandle ioHandle=" +
                                        sourceHandle.ioHandle.getClass().getName() + " fileno=" + srcFileno +
                                        " ioHandleId=" + System.identityHashCode(sourceHandle.ioHandle));
                                System.err.flush();
                            }
                            if (isParsimonious) {
                                // &= mode: non-owning wrapper sharing the same fd
                                fh = createBorrowedHandle(sourceHandle);
                            } else {
                                // & mode: create a new handle that duplicates the original
                                fh = duplicateFileHandle(sourceHandle);
                            }
                        } else {
                            throw new PerlCompilerException("Bad filehandle: " + extractFilehandleName(argStr));
                        }
                    } catch (Exception ex) {
                        throw new PerlCompilerException("Bad filehandle: " + extractFilehandleName(argStr));
                    }
                } else {
                    // Try getRuntimeIO() which handles blessed objects with *{} overloading
                    // (e.g., File::Temp objects passed to open with dup mode)
                    RuntimeIO sourceHandle = null;
                    try {
                        sourceHandle = secondArg.getRuntimeIO();
                    } catch (Exception ignored) {
                    }

                    if (sourceHandle != null && sourceHandle.ioHandle != null) {
                        if (isParsimonious) {
                            fh = createBorrowedHandle(sourceHandle);
                        } else {
                            fh = duplicateFileHandle(sourceHandle);
                        }
                    } else {
                        // Handle string filehandle names (like "STDOUT", "STDERR", "STDIN")
                        String handleName = secondArg.toString();
                        if (handleName.equals("STDOUT") || handleName.equals("STDERR") || handleName.equals("STDIN")) {
                            // Convert string to proper filehandle reference
                            RuntimeScalar handleRef = GlobalVariable.getGlobalIO("main::" + handleName);
                            if (handleRef != null && handleRef.value instanceof RuntimeGlob) {
                                sourceHandle = ((RuntimeGlob) handleRef.value).getIO().getRuntimeIO();
                                if (sourceHandle != null && sourceHandle.ioHandle != null) {
                                    if (isParsimonious) {
                                        fh = createBorrowedHandle(sourceHandle);
                                    } else {
                                        // & mode: create a new handle that duplicates the original
                                        fh = duplicateFileHandle(sourceHandle);
                                    }
                                } else {
                                    throw new PerlCompilerException("Bad filehandle: " + extractFilehandleName(argStr));
                                }
                            } else {
                                throw new PerlCompilerException("Bad filehandle: " + extractFilehandleName(argStr));
                            }
                        } else {
                            // For other non-GLOB types, provide proper "Bad filehandle" error messages
                            throw new PerlCompilerException("Bad filehandle: " + extractFilehandleName(argStr));
                        }
                    }
                }
            } else if (secondArg.type == RuntimeScalarType.REFERENCE) {
                // Open to in-memory scalar
                fh = RuntimeIO.open(secondArg, mode);
            } else {
                // Regular file open
                String fileName = secondArg.toString();
                fh = RuntimeIO.open(fileName, mode);
            }
        } else {
            // 2-argument open
            fh = RuntimeIO.open(mode);
        }
        if (fh == null) {
            return scalarUndef;
        }

        // Check if the filehandle already contains a GLOB
        RuntimeGlob targetGlob = null;
        if ((fileHandle.type == RuntimeScalarType.GLOB || fileHandle.type == RuntimeScalarType.GLOBREFERENCE) && fileHandle.value instanceof RuntimeGlob glob) {
            targetGlob = glob;
        } else if ((fileHandle.type == RuntimeScalarType.STRING || fileHandle.type == RuntimeScalarType.BYTE_STRING) && fileHandle.value instanceof String name) {
            // Symbolic filehandle: open($fh, ...) where $fh contains "TST" should open main::TST
            // so later bareword usage like <TST> resolves to the correct global handle.
            if (!name.isEmpty() && name.matches("^[A-Za-z_][A-Za-z0-9_]*(::[A-Za-z_][A-Za-z0-9_]*)*$")) {
                String fullName = name.contains("::") ? name : ("main::" + name);
                targetGlob = GlobalVariable.getGlobalIO(fullName);

                // Store a reference to the named glob in the scalar lvalue
                RuntimeScalar newGlob = new RuntimeScalar();
                newGlob.type = RuntimeScalarType.GLOBREFERENCE;
                newGlob.value = targetGlob;
                fileHandle.set(newGlob);
            }
        }

        if (targetGlob != null) {
            if (ioDebug && targetGlob.globName != null && (targetGlob.globName.equals("main::STDOUT") || targetGlob.globName.equals("main::STDERR") || targetGlob.globName.equals("main::STDIN"))) {
                String ioHandleClass = fh != null && fh.ioHandle != null ? fh.ioHandle.getClass().getName() : "null";
                String filenoStr;
                try {
                    filenoStr = fh != null && fh.ioHandle != null ? fh.ioHandle.fileno().toString() : "undef";
                } catch (Throwable t) {
                    filenoStr = "<err>";
                }
                System.err.println("[JPERL_IO_DEBUG] open assign " + targetGlob.globName + " mode=" + mode +
                        " ioHandle=" + ioHandleClass + " fileno=" + filenoStr +
                        " ioHandleId=" + (fh != null && fh.ioHandle != null ? System.identityHashCode(fh.ioHandle) : 0));
                System.err.flush();
            }
            targetGlob.setIO(fh);
        } else {
            // Create a new anonymous GLOB and assign it to the lvalue
            RuntimeScalar newGlob = new RuntimeScalar();
            newGlob.type = RuntimeScalarType.GLOBREFERENCE;
            RuntimeGlob anonGlob = new RuntimeGlob(null).setIO(fh);
            newGlob.value = anonGlob;
            // Register for GC-based fd recycling (mimics Perl's DESTROY on scope exit)
            RuntimeIO.registerGlobForFdRecycling(anonGlob, fh);
            // Use set() to modify the lvalue in place
            fileHandle.set(newGlob);
            // Mark this scalar as the IO owner so scopeExitCleanup will close
            // the handle when the variable goes out of scope. Copies of this
            // reference (via set()) won't have ioOwner=true, preventing
            // premature close of shared handles (e.g., Test2's dup'd STDOUT).
            fileHandle.ioOwner = true;
        }
        long pid = fh.getPid();
        if (pid > 0) return new RuntimeScalar(pid);
        return scalarTrue;
    }

    /**
     * Close a file handle.
     *
     * @param args The file handle.
     * @return A RuntimeScalar with the result of the close operation.
     */
    public static RuntimeScalar close(int ctx, RuntimeBase... args) {
        // Clear any pending fork-open state
        ForkOpenState.clear();
        
        RuntimeScalar handle = args.length == 1 ? ((RuntimeScalar) args[0]) : select(new RuntimeList(), RuntimeContextType.SCALAR);
        RuntimeIO fh = handle.getRuntimeIO();

        // Handle case where the filehandle is invalid/corrupted
        if (fh == null) {
            // Return false (undef in boolean context) for invalid filehandle
            return new RuntimeScalar();
        }

        if (fh instanceof TieHandle tieHandle) {
            return TieHandle.tiedClose(tieHandle);
        }

        return fh.close();
    }

    /**
     * Prints the elements to the specified file handle according to the format string.
     *
     * @param runtimeList
     * @param fileHandle  The file handle to write to.
     * @return A RuntimeScalar indicating the result of the write operation.
     */
    public static RuntimeScalar printf(RuntimeList runtimeList, RuntimeScalar fileHandle) {
        RuntimeIO fh = fileHandle.getRuntimeIO();

        if (fh instanceof TieHandle tieHandle) {
            return TieHandle.tiedPrintf(tieHandle, runtimeList);
        }

        // Flatten any arrays in the list (handles "printf @a" where @a contains format + args)
        RuntimeList flatList = new RuntimeList();
        for (RuntimeBase elem : runtimeList.elements) {
            if (elem instanceof RuntimeArray array) {
                for (int j = 0; j < array.size(); j++) {
                    flatList.add(array.get(j));
                }
            } else {
                flatList.add(elem);
            }
        }

        // Handle empty argument list (printf +())
        if (flatList.elements.isEmpty()) {
            return scalarTrue;
        }

        RuntimeScalar format = (RuntimeScalar) flatList.elements.removeFirst(); // Extract the format string from elements

        String formattedString;

        // Use sprintf to get the formatted string
        try {
            formattedString = SprintfOperator.sprintf(format, flatList).toString();
        } catch (PerlCompilerException e) {
            // Change sprintf error messages to printf
            String message = e.getMessage();
            if (message != null && message.contains("Integer overflow in format string for sprintf ")) {
                throw new PerlCompilerException("Integer overflow in format string for printf ");
            }
            // Re-throw other exceptions unchanged
            throw e;
        }

        // Write the formatted content to the file handle
        return fh.write(formattedString);
    }

    /**
     * Prints the elements to the specified file handle with a separator and newline.
     *
     * @param runtimeList
     * @param fileHandle  The file handle to write to.
     * @return A RuntimeScalar indicating the result of the write operation.
     */
    public static RuntimeScalar print(RuntimeList runtimeList, RuntimeScalar fileHandle) {
        RuntimeIO fh = fileHandle.getRuntimeIO();

        if (fh instanceof TieHandle tieHandle) {
            return TieHandle.tiedPrint(tieHandle, runtimeList);
        }

        if (fh == null || ((fh.ioHandle == null || fh.ioHandle instanceof ClosedIOHandle) && fh.directoryIO == null)) {
            getGlobalVariable("main::!").set(9);
            return scalarFalse;
        }

        StringBuilder sb = new StringBuilder();
        String separator = OutputFieldSeparator.getInternalOFS(); // fetch $, (internal copy, not affected by aliasing)
        String newline = OutputRecordSeparator.getInternalORS();  // fetch $\ (internal copy, not affected by aliasing)
        boolean first = true;

        // Iterate through elements and append them with the separator
        for (RuntimeBase element : runtimeList.elements) {
            if (!first) {
                sb.append(separator);
            }
            sb.append(element.toString());
            first = false;
        }

        // Append the newline character
        sb.append(newline);

        try {
            // Write the content to the file handle
            return fh.write(sb.toString());
        } catch (java.nio.channels.NonWritableChannelException e) {
            // Writing to a read-only filehandle (opened with "<")
            getGlobalVariable("main::!").set("Bad file descriptor");
            WarnDie.warnWithCategory(
                    new RuntimeScalar("Filehandle opened only for input"),
                    new RuntimeScalar(""),
                    "io");
            return new RuntimeScalar(); // undef
        } catch (Exception e) {
            getGlobalVariable("main::!").set("File operation failed: " + e.getMessage());
            return scalarFalse;
        }
    }

    /**
     * Prints the elements to the specified file handle with a separator and a newline at the end.
     *
     * @param runtimeList
     * @param fileHandle  The file handle to write to.
     * @return A RuntimeScalar indicating the result of the write operation.
     */
    public static RuntimeScalar say(RuntimeList runtimeList, RuntimeScalar fileHandle) {
        RuntimeIO fh = fileHandle.getRuntimeIO();
        if (fh instanceof TieHandle tieHandle) {
            RuntimeList args = new RuntimeList();
            args.elements.addAll(runtimeList.elements);
            args.elements.add(new RuntimeScalar("\n"));
            return TieHandle.tiedPrint(tieHandle, args);
        }

        StringBuilder sb = new StringBuilder();
        String separator = getGlobalVariable("main::,").toString(); // fetch $,
        boolean first = true;

        // Iterate through elements and append them with the separator
        for (RuntimeBase element : runtimeList.elements) {
            if (!first) {
                sb.append(separator);
            }
            sb.append(element.toString());
            first = false;
        }

        // Append the newline character
        sb.append("\n");

        try {
            // Write the content to the file handle
            if (fh == null || ((fh.ioHandle == null || fh.ioHandle instanceof ClosedIOHandle) && fh.directoryIO == null)) {
                getGlobalVariable("main::!").set(9);
                return scalarFalse;
            }
            return fh.write(sb.toString());
        } catch (java.nio.channels.NonWritableChannelException e) {
            // Writing to a read-only filehandle (opened with "<")
            getGlobalVariable("main::!").set("Bad file descriptor");
            WarnDie.warnWithCategory(
                    new RuntimeScalar("Filehandle opened only for input"),
                    new RuntimeScalar(""),
                    "io");
            return new RuntimeScalar(); // undef
        } catch (Exception e) {
            getGlobalVariable("main::!").set("File operation failed: " + e.getMessage());
            return scalarFalse;
        }
    }

    /**
     * Reads EOF flag from a file handle.
     *
     * @param fileHandle The file handle.
     * @return A RuntimeScalar with the flag.
     */
    public static RuntimeScalar eof(RuntimeScalar fileHandle) {
        boolean argless = !fileHandle.getDefinedBoolean();
        RuntimeIO fh = fileHandle.getRuntimeIO();

        // Handle undefined or invalid filehandle
        if (fh == null) {
            if (argless) {
                RuntimeIO last = RuntimeIO.lastAccesseddHandle;
                if (last != null) {
                    return last.eof();
                }
                // Perl's eof() defaults to ARGV if ${^LAST_FH} is unset
                RuntimeIO argv = new RuntimeScalar("main::ARGV").getRuntimeIO();
                if (argv == null || argv.ioHandle == null || argv.ioHandle instanceof ClosedIOHandle) {
                    return scalarTrue;
                }
                return argv.eof();
            }
            GlobalVariable.getGlobalVariable("main::!")
                    .set(new RuntimeScalar(9));
            return scalarUndef;
        }

        if (fh instanceof TieHandle tieHandle) {
            return TieHandle.tiedEof(tieHandle, new RuntimeList());
        }

        return fh.eof();
    }

    public static RuntimeScalar eof(RuntimeList runtimeList, RuntimeScalar fileHandle) {
        boolean argless = !fileHandle.getDefinedBoolean();
        RuntimeIO fh = fileHandle.getRuntimeIO();

        // Handle undefined or invalid filehandle
        if (fh == null) {
            if (argless) {
                RuntimeIO last = RuntimeIO.lastAccesseddHandle;
                if (last != null) {
                    return last.eof();
                }
                RuntimeIO argv = new RuntimeScalar("main::ARGV").getRuntimeIO();
                if (argv == null || argv.ioHandle == null || argv.ioHandle instanceof ClosedIOHandle) {
                    return scalarTrue;
                }
                return argv.eof();
            }
            GlobalVariable.getGlobalVariable("main::!")
                    .set(new RuntimeScalar(9));
            return scalarUndef;
        }

        if (fh instanceof TieHandle tieHandle) {
            return TieHandle.tiedEof(tieHandle, runtimeList);
        }

        return fh.eof();
    }

    /**
     * System-level read operation that bypasses PerlIO layers.
     * sysread FILEHANDLE,SCALAR,LENGTH[,OFFSET]
     *
     * @param args Contains FILEHANDLE, TARGET, LENGTH and optional OFFSET
     * @return Number of bytes read, 0 at EOF, or undef on error
     */
    private static void setByteString(RuntimeScalar target, String value) {
        RuntimeScalar tmp = new RuntimeScalar(value);
        tmp.type = RuntimeScalarType.BYTE_STRING;
        target.set(tmp);
    }

    public static RuntimeScalar sysread(int ctx, RuntimeBase... args) {
        if (args.length < 3) {
            throw new PerlCompilerException("Not enough arguments for sysread");
        }

        RuntimeScalar fileHandle = args[0].scalar();

        RuntimeIO fh = fileHandle.getRuntimeIO();

        // Check if fh is null (invalid filehandle)
        if (fh == null) {
            getGlobalVariable("main::!").set("Bad file descriptor");
            WarnDie.warn(
                    new RuntimeScalar("sysread() on unopened filehandle"),
                    new RuntimeScalar("\n")
            );
            return new RuntimeScalar(); // undef
        }

        if (fh instanceof TieHandle tieHandle) {
            RuntimeScalar target = args[1].scalar().scalarDeref();
            RuntimeScalar length = args[2].scalar();
            RuntimeList tieArgs = args.length > 3
                    ? new RuntimeList(target, length, args[3].scalar())
                    : new RuntimeList(target, length);
            return TieHandle.tiedRead(tieHandle, tieArgs);
        }

        // Check for closed handle
        if (fh.ioHandle == null || fh.ioHandle instanceof ClosedIOHandle) {
            getGlobalVariable("main::!").set("Bad file descriptor");
            WarnDie.warn(
                    new RuntimeScalar("sysread() on closed filehandle"),
                    new RuntimeScalar("\n")
            );
            return new RuntimeScalar(); // undef
        }

        // Check for :utf8 layer
        if (hasUtf8Layer(fh)) {
            throw new PerlCompilerException("sysread() is not supported on handles with :utf8 layer");
        }

        RuntimeScalar target = args[1].scalar().scalarDeref();
        int length = args[2].scalar().getInt();
        int offset = 0;

        if (args.length > 3) {
            offset = args[3].scalar().getInt();
        }

        // Check for in-memory handles (ScalarBackedIO)
        // System Perl does not support sysread on in-memory file handles —
        // it returns undef and sets $! to "Bad file descriptor".
        IOHandle baseHandle = getBaseHandle(fh.ioHandle);

        if (baseHandle instanceof ScalarBackedIO scalarIO) {
            getGlobalVariable("main::!").set("Bad file descriptor");
            return new RuntimeScalar(); // undef
        }

        // Try to perform the system read
        RuntimeScalar result;
        try {
            result = baseHandle.sysread(length);
        } catch (Exception e) {
            // This might happen with write-only handles
            getGlobalVariable("main::!").set("Bad file descriptor");
            WarnDie.warn(
                    new RuntimeScalar("Filehandle opened only for output"),
                    new RuntimeScalar("\n")
            );
            return new RuntimeScalar(); // undef
        }

        // Check if the result indicates an error (like from ClosedIOHandle)
        if (!result.getDefinedBoolean()) {
            String errorMsg = getGlobalVariable("main::!").toString();

            if (errorMsg.toLowerCase().contains("closed")) {
                WarnDie.warn(
                        new RuntimeScalar("sysread() on closed filehandle"),
                        new RuntimeScalar("\n")
                );
            } else if (errorMsg.toLowerCase().contains("output") || errorMsg.toLowerCase().contains("write")) {
                WarnDie.warn(
                        new RuntimeScalar("Filehandle opened only for output"),
                        new RuntimeScalar("\n")
                );
            }
            return new RuntimeScalar(); // undef
        }

        String data = result.toString();
        int bytesRead = data.length();

        if (bytesRead == 0) {
            // EOF or zero-byte read
            if (offset == 0) {
                // Clear the buffer when no offset is specified
                setByteString(target, "");
            }
            // Otherwise preserve the buffer when using offset
            return new RuntimeScalar(0);
        }

        // Handle offset
        String currentValue = target.toString();
        int currentLength = currentValue.length();

        if (offset < 0) {
            // Negative offset counts from end
            offset = currentLength + offset;
            if (offset < 0) {
                offset = 0;
            }
        }

        // Pad with nulls if needed
        if (offset > currentLength) {
            StringBuilder padded = new StringBuilder(currentValue);
            while (padded.length() < offset) {
                padded.append('\0');
            }
            currentValue = padded.toString();
        }

        // Place the data at the specified offset
        StringBuilder newValue = new StringBuilder();
        if (offset > 0) {
            newValue.append(currentValue, 0, Math.min(offset, currentValue.length()));
        }
        newValue.append(data);

        setByteString(target, newValue.toString());
        return new RuntimeScalar(bytesRead);
    }

    /**
     * System-level write operation that bypasses PerlIO layers.
     * syswrite FILEHANDLE,SCALAR[,LENGTH[,OFFSET]]
     *
     * @param args Contains FILEHANDLE, SCALAR, optional LENGTH and OFFSET
     * @return Number of bytes written, or undef on error
     */
    public static RuntimeScalar syswrite(int ctx, RuntimeBase... args) {
        if (args.length < 2) {
            throw new PerlCompilerException("Not enough arguments for syswrite");
        }

        RuntimeScalar fileHandle = args[0].scalar();
        RuntimeIO fh = fileHandle.getRuntimeIO();

        // Check TieHandle FIRST (before closed handle check), matching sysread/print pattern.
        // TieHandle extends RuntimeIO which initializes ioHandle as ClosedIOHandle,
        // so the closed-handle check would incorrectly catch tied handles.
        if (fh instanceof TieHandle tieHandle) {
            RuntimeScalar data = args[1].scalar();
            int dataLen = data.toString().length();
            RuntimeScalar lengthArg = args.length > 2 ? args[2].scalar() : new RuntimeScalar(dataLen);
            if (args.length > 3) {
                return TieHandle.tiedWrite(tieHandle, data, lengthArg, args[3].scalar());
            } else {
                return TieHandle.tiedWrite(tieHandle, data, lengthArg, new RuntimeScalar(0));
            }
        }

        // Check if fh is null or closed (after TieHandle check)
        if (fh == null || fh.ioHandle == null || fh.ioHandle instanceof ClosedIOHandle) {
            getGlobalVariable("main::!").set("Bad file descriptor");
            WarnDie.warn(
                    new RuntimeScalar("syswrite() on closed filehandle"),
                    new RuntimeScalar("\n")
            );
            return new RuntimeScalar(); // undef
        }

        // Check for :utf8 layer
        if (hasUtf8Layer(fh)) {
            throw new PerlCompilerException("syswrite() is not supported on handles with :utf8 layer");
        }

        String data = args[1].scalar().toString();
        int length = data.length();
        int offset = 0;

        // Handle optional LENGTH parameter
        if (args.length > 2) {
            length = args[2].scalar().getInt();
        }

        // Handle optional OFFSET parameter
        if (args.length > 3) {
            offset = args[3].scalar().getInt();
        }

        // Handle negative offset
        if (offset < 0) {
            offset = data.length() + offset;
            if (offset < 0) {
                return RuntimeIO.handleIOError("Offset outside string");
            }
        }

        // Check offset bounds
        if (offset > data.length()) {
            return RuntimeIO.handleIOError("Offset outside string");
        }

        // Calculate actual length to write
        int availableLength = data.length() - offset;
        if (length > availableLength) {
            length = availableLength;
        }

        // Check for characters > 255
        String toWrite = data.substring(offset, offset + length);
        StringParser.assertNoWideCharacters(toWrite, "syswrite");

        // Check for in-memory handles (ScalarBackedIO)
        IOHandle baseHandle = getBaseHandle(fh.ioHandle);
        if (baseHandle instanceof ScalarBackedIO) {
            getGlobalVariable("main::!").set("Invalid argument");
            return new RuntimeScalar(); // undef
        }

        // Try to perform the system write
        RuntimeScalar result;
        try {
            result = baseHandle.syswrite(toWrite);
        } catch (Exception e) {
            // Handle various exceptions
            String exceptionType = e.getClass().getSimpleName();
            String msg = e.getMessage();

            if (e instanceof java.nio.channels.ClosedChannelException ||
                    (msg != null && msg.contains("closed"))) {
                // Closed channel
                getGlobalVariable("main::!").set("Bad file descriptor");
                WarnDie.warn(
                        new RuntimeScalar("syswrite() on closed filehandle"),
                        new RuntimeScalar("\n")
                );
                return new RuntimeScalar(); // undef
            } else if (e instanceof java.nio.channels.NonWritableChannelException ||
                    exceptionType.contains("NonWritableChannel")) {
                // Read-only handle
                getGlobalVariable("main::!").set("Bad file descriptor");
                WarnDie.warn(
                        new RuntimeScalar("Filehandle opened only for input"),
                        new RuntimeScalar("\n")
                );
                return new RuntimeScalar(); // undef
            } else {
                // Other errors
                getGlobalVariable("main::!").set(msg != null ? msg : "I/O error");
                return new RuntimeScalar(); // undef
            }
        }

        // Check if the result indicates an error
        if (!result.getDefinedBoolean()) {
            String errorMsg = getGlobalVariable("main::!").toString().toLowerCase();
            if (errorMsg.contains("closed")) {
                WarnDie.warn(
                        new RuntimeScalar("syswrite() on closed filehandle"),
                        new RuntimeScalar("\n")
                );
            } else if (errorMsg.contains("input") || errorMsg.contains("read")) {
                WarnDie.warn(
                        new RuntimeScalar("Filehandle opened only for input"),
                        new RuntimeScalar("\n")
                );
            }
        }

        return result;
    }

    /**
     * Checks if the handle has a :utf8 layer.
     */
    private static boolean hasUtf8Layer(RuntimeIO fh) {
        IOHandle handle = fh.ioHandle;
        while (handle instanceof LayeredIOHandle layered) {
            String layers = layered.getCurrentLayers();
            if (layers.contains(":utf8") || layers.contains(":encoding")) {
                return true;
            }
            handle = layered.getDelegate();
        }
        return false;
    }

    /**
     * Gets the base handle by unwrapping all layers.
     */
    private static IOHandle getBaseHandle(IOHandle handle) {
        while (handle instanceof LayeredIOHandle layered) {
            handle = layered.getDelegate();
        }
        return handle;
    }

    /**
     * Opens a file using system-level open flags.
     *
     * @param runtimeList The list containing filehandle, filename, mode, and optional perms
     * @return A RuntimeScalar indicating success (1) or failure (0)
     */
    public static RuntimeScalar sysopen(int ctx, RuntimeBase... args) {
        // sysopen FILEHANDLE,FILENAME,MODE
        // sysopen FILEHANDLE,FILENAME,MODE,PERMS

        if (args.length < 3) {
            throw new PerlCompilerException("Not enough arguments for sysopen");
        }

        RuntimeScalar fileHandle = args[0].scalar();
        String fileName = args[1].toString();
        int mode = args[2].scalar().getInt();
        int perms = 0666; // Default permissions (octal)

        if (args.length >= 4) {
            perms = args[3].scalar().getInt();
        }

        // Convert numeric flags to mode string for RuntimeIO
        String modeStr = "";

        // Common flag combinations
        int O_RDONLY = 0;
        int O_WRONLY = 1;
        int O_RDWR = 2;
        int O_CREAT = 0100; // 64 in decimal
        int O_EXCL = 0200;  // 128 in decimal
        int O_APPEND = 02000; // 1024 in decimal
        int O_TRUNC = 01000;  // 512 in decimal

        // Determine the base mode
        int baseMode = mode & 3; // Get the lowest 2 bits

        if (baseMode == O_RDONLY) {
            modeStr = "<";
        } else if (baseMode == O_WRONLY) {
            if ((mode & O_APPEND) != 0) {
                modeStr = ">>";
            } else if ((mode & O_TRUNC) != 0 || (mode & O_CREAT) != 0) {
                modeStr = ">";
            } else {
                modeStr = ">";
            }
        } else if (baseMode == O_RDWR) {
            if ((mode & O_APPEND) != 0) {
                modeStr = "+>>";
            } else {
                modeStr = "+<";
            }
        }

        // If creating a new file, apply the permissions
        if ((mode & O_CREAT) != 0) {
            File file = RuntimeIO.resolveFile(fileName);
            boolean existed = file.exists();
            // O_EXCL: "error if O_CREAT and the file already exists"
            if ((mode & O_EXCL) != 0 && existed) {
                getGlobalVariable("main::!").set("File exists");
                return scalarFalse;
            }
            if (!existed) {
                try {
                    file.createNewFile();
                    // Apply permissions to the newly created file
                    applyFilePermissions(file.toPath(), perms);
                } catch (IOException e) {
                    // Failed to create file
                    getGlobalVariable("main::!").set(e.getMessage());
                    return scalarFalse;
                }
            }
        }

        RuntimeIO fh = RuntimeIO.open(fileName, modeStr);
        if (fh == null) {
            return scalarFalse;
        }

        // Set IO slot on the glob, following the same pattern as open() and socket()
        RuntimeGlob targetGlob = null;
        if ((fileHandle.type == RuntimeScalarType.GLOB || fileHandle.type == RuntimeScalarType.GLOBREFERENCE)
                && fileHandle.value instanceof RuntimeGlob glob) {
            targetGlob = glob;
        } else if ((fileHandle.type == RuntimeScalarType.STRING || fileHandle.type == RuntimeScalarType.BYTE_STRING)
                && fileHandle.value instanceof String name) {
            if (!name.isEmpty() && name.matches("^[A-Za-z_][A-Za-z0-9_]*(::[A-Za-z_][A-Za-z0-9_]*)*$")) {
                String fullName = name.contains("::") ? name : ("main::" + name);
                targetGlob = GlobalVariable.getGlobalIO(fullName);
                RuntimeScalar newGlob = new RuntimeScalar();
                newGlob.type = RuntimeScalarType.GLOBREFERENCE;
                newGlob.value = targetGlob;
                fileHandle.set(newGlob);
            }
        }

        if (targetGlob != null) {
            targetGlob.setIO(fh);
        } else {
            RuntimeScalar newGlob = new RuntimeScalar();
            newGlob.type = RuntimeScalarType.GLOBREFERENCE;
            RuntimeGlob anonGlob = new RuntimeGlob(null).setIO(fh);
            newGlob.value = anonGlob;
            RuntimeIO.registerGlobForFdRecycling(anonGlob, fh);
            fileHandle.set(newGlob);
        }
        return scalarTrue;
    }

    /**
     * Helper method to apply Unix-style permissions to a file.
     * Uses PosixFilePermissions on Unix-like systems, falls back to basic permissions on Windows.
     *
     * @param path The path to the file
     * @param mode The Unix permission mode (octal)
     * @return true if permissions were successfully applied, false otherwise
     */
    public static boolean applyFilePermissions(Path path, int mode) {
        try {
            // Check if POSIX permissions are supported
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                // Use POSIX permissions on Unix-like systems
                Set<PosixFilePermission> perms = new HashSet<>();

                // Owner permissions
                if ((mode & 0400) != 0) perms.add(PosixFilePermission.OWNER_READ);
                if ((mode & 0200) != 0) perms.add(PosixFilePermission.OWNER_WRITE);
                if ((mode & 0100) != 0) perms.add(PosixFilePermission.OWNER_EXECUTE);

                // Group permissions
                if ((mode & 040) != 0) perms.add(PosixFilePermission.GROUP_READ);
                if ((mode & 020) != 0) perms.add(PosixFilePermission.GROUP_WRITE);
                if ((mode & 010) != 0) perms.add(PosixFilePermission.GROUP_EXECUTE);

                // Others permissions
                if ((mode & 04) != 0) perms.add(PosixFilePermission.OTHERS_READ);
                if ((mode & 02) != 0) perms.add(PosixFilePermission.OTHERS_WRITE);
                if ((mode & 01) != 0) perms.add(PosixFilePermission.OTHERS_EXECUTE);

                Files.setPosixFilePermissions(path, perms);
            } else {
                // Fall back to basic permissions on Windows
                File file = path.toFile();

                // Windows only supports read/write permissions, not execute
                boolean ownerRead = (mode & 0400) != 0;
                boolean ownerWrite = (mode & 0200) != 0;
                boolean ownerExecute = (mode & 0100) != 0;

                // On Windows, we can only set owner permissions
                file.setReadable(ownerRead, true);
                file.setWritable(ownerWrite, true);
                file.setExecutable(ownerExecute, true);

                // If any group/other has read permission, make readable by all
                if ((mode & 044) != 0) {
                    file.setReadable(true, false);
                }
                // If any group/other has write permission, make writable by all
                if ((mode & 022) != 0) {
                    file.setWritable(true, false);
                }
                // If any group/other has execute permission, make executable by all
                if ((mode & 011) != 0) {
                    file.setExecutable(true, false);
                }
            }
            return true;
        } catch (IOException | SecurityException e) {
            // Permission denied or other error
            return false;
        }
    }

    /**
     * Executes a Perl format against a filehandle.
     * write FILEHANDLE
     * write
     * <p>
     * This function looks up the format associated with the filehandle name,
     * executes it with the current values of format variables, and writes
     * the formatted output to the filehandle.
     *
     * @param ctx  The runtime context
     * @param args Optional filehandle argument (defaults to currently selected handle)
     * @return A RuntimeScalar indicating success (1) or failure (0)
     */
    public static RuntimeScalar write(int ctx, RuntimeBase... args) {
        String formatName;
        RuntimeIO fh = RuntimeIO.stdout; // Default output handle

        if (args.length == 0) {
            // No arguments: write() - use STDOUT format to STDOUT handle
            formatName = "STDOUT";
        } else {
            // One argument: write FORMAT_NAME - use named format to STDOUT handle
            RuntimeScalar arg = args[0].scalar();

            // Check if argument is a glob reference (which contains the format name)
            if (arg.type == RuntimeScalarType.GLOBREFERENCE && arg.value instanceof RuntimeGlob glob) {
                formatName = glob.globName;
            } else {
                // Check if argument is a filehandle or format name
                RuntimeIO argFh = arg.getRuntimeIO();
                if (argFh != null) {
                    // Argument is a filehandle - determine format name from handle
                    fh = argFh;
                    if (fh == RuntimeIO.stdout) {
                        formatName = "STDOUT";
                    } else if (fh == RuntimeIO.stderr) {
                        formatName = "STDERR";
                    } else if (fh == RuntimeIO.stdin) {
                        formatName = "STDIN";
                    } else {
                        formatName = "STDOUT"; // Default fallback
                    }
                } else {
                    // Argument is a format name string (most common case)
                    formatName = arg.toString();
                    // Normalize the format name
                    formatName = NameNormalizer.normalizeVariableName(formatName, "main");
                }
            }
        }

        // Look up the format
        RuntimeFormat format = GlobalVariable.getGlobalFormatRef(formatName);

        if (format == null || !format.isFormatDefined()) {
            // Format not found or not defined
            String errorMsg = "Undefined format \"" + formatName + "\" called";
            getGlobalVariable("main::!").set(errorMsg);
            throw new RuntimeException(errorMsg);
        }

        try {
            // Execute the format with arguments from current scope
            // For now, we'll pass empty arguments and let the format execution handle variable lookup
            // In a full implementation, this would collect format variables from the current scope
            RuntimeList formatArgs = new RuntimeList();

            // TODO: Collect format variables from current scope
            // This would involve scanning for variables referenced in the format's argument lines
            // and collecting their current values from the symbol table
            // For now, the format execution will need to handle variable lookup internally

            String formattedOutput = format.execute(formatArgs);

            // Write the formatted output to the filehandle
            RuntimeScalar writeResult = fh.write(formattedOutput);

            return writeResult;

        } catch (Exception e) {
            getGlobalVariable("main::!").set("Format execution failed: " + e.getMessage());
            return scalarFalse;
        }
    }

    /**
     * Executes a Perl format with explicit arguments.
     * This is a helper method for testing and advanced format usage.
     *
     * @param formatName The name of the format to execute
     * @param args       The arguments to pass to the format
     * @param fileHandle The filehandle to write to
     * @return A RuntimeScalar indicating success (1) or failure (0)
     */
    public static RuntimeScalar writeFormat(String formatName, RuntimeList args, RuntimeScalar fileHandle) {
        RuntimeIO fh = fileHandle.getRuntimeIO();

        if (fh == null) {
            getGlobalVariable("main::!").set("Bad file descriptor");
            return scalarFalse;
        }

        // Look up the format
        RuntimeFormat format = GlobalVariable.getGlobalFormatRef(formatName);

        if (format == null || !format.isFormatDefined()) {
            getGlobalVariable("main::!").set("Undefined format \"" + formatName + "\" called");
            return scalarFalse;
        }

        try {
            String formattedOutput = format.execute(args);
            return fh.write(formattedOutput);
        } catch (Exception e) {
            getGlobalVariable("main::!").set("Format execution failed: " + e.getMessage());
            return scalarFalse;
        }
    }

    /**
     * Implements the formline operator.
     * Formats text according to a format template and appends to $^A.
     *
     * @param ctx  The runtime context
     * @param args The arguments: format template followed by values
     * @return The current value of $^A after appending
     */
    public static RuntimeScalar formline(int ctx, RuntimeBase... args) {
        if (args.length < 1) {
            throw new PerlCompilerException("Not enough arguments for formline");
        }

        // Get the format template
        String formatTemplate = args[0].scalar().toString();

        // For simple cases (like constants in index.t), if there are no format fields,
        // just append the template string directly to $^A
        if (!formatTemplate.contains("@") && !formatTemplate.contains("^")) {
            // Simple case: no format fields, just append the string
            RuntimeScalar accumulator = getGlobalVariable(GlobalContext.encodeSpecialVar("A"));
            String currentValue = accumulator.toString();
            accumulator.set(currentValue + formatTemplate);
            return scalarTrue;
        }

        // Create arguments list for format processing
        RuntimeList formatArgs = new RuntimeList();
        for (int i = 1; i < args.length; i++) {
            formatArgs.add(args[i]);
        }

        // For complex format templates with @ or ^ fields, use RuntimeFormat
        // Note: This is a simplified implementation - full format support would require
        // parsing the format template properly
        try {
            // Create a temporary RuntimeFormat to process the template
            RuntimeFormat tempFormat = new RuntimeFormat("FORMLINE_TEMP", formatTemplate);

            // Parse the format template as picture lines
            List<FormatLine> lines = new ArrayList<>();
            lines.add(new PictureLine(formatTemplate, new ArrayList<>(), formatTemplate, 0));
            tempFormat.setCompiledLines(lines);

            // Execute the format and get the result
            String formattedOutput = tempFormat.execute(formatArgs);

            // Append to $^A
            RuntimeScalar accumulator = getGlobalVariable(GlobalContext.encodeSpecialVar("A"));
            String currentValue = accumulator.toString();
            accumulator.set(currentValue + formattedOutput);

            // Return success (1)
            return scalarTrue;
        } catch (Exception e) {
            throw new PerlCompilerException("formline failed: " + e.getMessage());
        }
    }

    /**
     * Extracts a clean filehandle name from a string representation.
     * Removes prefixes like "*main::" and GLOB references for cleaner error messages.
     */
    private static String extractFilehandleName(String argStr) {
        if (argStr == null) return "unknown";

        // Remove *main:: prefix if present
        if (argStr.startsWith("*main::")) {
            return argStr.substring(7);
        }

        // Handle GLOB(0x...) format - extract just the reference part
        if (argStr.startsWith("GLOB(") && argStr.endsWith(")")) {
            return argStr; // Keep the GLOB reference as is for now
        }

        return argStr;
    }

    // Socket I/O operators implementation

    /**
     * socket(SOCKET, DOMAIN, TYPE, PROTOCOL)
     * Creates a socket and associates it with SOCKET filehandle.
     * Like POSIX socket(), creates a generic socket that can be used for either
     * connect() (client) or bind()+listen() (server).
     */
    public static RuntimeScalar socket(int ctx, RuntimeBase... args) {
        if (args.length < 4) {
            getGlobalVariable("main::!").set("Not enough arguments for socket");
            return scalarFalse;
        }

        try {
            RuntimeScalar socketHandle = args[0].scalar();
            int domain = args[1].scalar().getInt();
            int type = args[2].scalar().getInt();
            int protocol = args[3].scalar().getInt();

            // Map Perl socket constants to Java
            ProtocolFamily family;
            if (domain == 2) { // AF_INET
                family = StandardProtocolFamily.INET;
            } else if (domain == 10) { // AF_INET6
                family = StandardProtocolFamily.INET6;
            } else if (domain == 1) { // AF_UNIX
                family = StandardProtocolFamily.UNIX;
            } else {
                getGlobalVariable("main::!").set("Unsupported socket domain: " + domain);
                return scalarFalse;
            }

            RuntimeIO socketIO;
            if (type == 1) { // SOCK_STREAM (TCP)
                // Create a SocketChannel with protocol family for proper IPv4/IPv6 handling.
                // This ensures getsockname() returns the right address family.
                SocketChannel channel = SocketChannel.open(family);
                SocketIO socketIOHandle = new SocketIO(channel, family);
                socketIO = new RuntimeIO(socketIOHandle);
            } else if (type == 2) { // SOCK_DGRAM (UDP)
                DatagramChannel channel = DatagramChannel.open(family);
                SocketIO socketIOHandle = new SocketIO(channel, family);
                socketIO = new RuntimeIO(socketIOHandle);
            } else {
                getGlobalVariable("main::!").set("Unsupported socket type: " + type);
                return scalarFalse;
            }

            // Assign a small sequential fileno for select() support
            socketIO.assignFileno();

            // Set IO slot on the glob, following the same pattern as open()
            RuntimeGlob targetGlob = null;
            if ((socketHandle.type == RuntimeScalarType.GLOB || socketHandle.type == RuntimeScalarType.GLOBREFERENCE)
                    && socketHandle.value instanceof RuntimeGlob glob) {
                targetGlob = glob;
            }

            if (targetGlob != null) {
                targetGlob.setIO(socketIO);
            } else {
                // Create a new anonymous GLOB and assign it to the lvalue
                RuntimeScalar newGlob = new RuntimeScalar();
                newGlob.type = RuntimeScalarType.GLOBREFERENCE;
                RuntimeGlob anonGlob = new RuntimeGlob(null).setIO(socketIO);
                newGlob.value = anonGlob;
                RuntimeIO.registerGlobForFdRecycling(anonGlob, socketIO);
                socketHandle.set(newGlob);
                // Mark this scalar as the IO owner so scopeExitCleanup will
                // unregister the fd when the variable goes out of scope.
                // Copies (via set()) won't have ioOwner=true, preventing
                // premature fd unregistration when copies go out of scope
                // (e.g., method argument copies in IO::Handle::fileno).
                socketHandle.ioOwner = true;
            }
            return scalarTrue;

        } catch (Exception e) {
            getGlobalVariable("main::!").set("Socket creation failed: " + e.getMessage());
            return scalarFalse;
        }
    }

    /**
     * Parses a Perl sockaddr_in packed binary address.
     * Format: 2-byte family + 2-byte port + 4-byte IP address + 8 bytes padding
     *
     * @param packedAddress The packed binary socket address
     * @return An array containing [host, port] or null if parsing fails
     */
    private static String[] parseSockaddrIn(String packedAddress) {
        try {
            // Quick check: if it looks like a text string (contains ':' or '.'), 
            // it's probably not a binary sockaddr_in structure
            if (packedAddress.contains(":") || packedAddress.matches(".*[0-9]+\\.[0-9]+.*")) {
                return null; // This is a text address, not binary sockaddr_in
            }

            byte[] bytes = packedAddress.getBytes(StandardCharsets.ISO_8859_1); // Get raw bytes

            if (bytes.length < 8) {
                return null; // Too short for sockaddr_in
            }

            // Check if first 2 bytes indicate AF_INET (family = 2)
            int family = ((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF);
            if (family != 2) { // AF_INET = 2
                return null; // Not a valid sockaddr_in structure
            }

            // Extract port (bytes 2-3, network byte order)
            int port = ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF);

            // Extract IP address (bytes 4-7)
            int ip1 = bytes[4] & 0xFF;
            int ip2 = bytes[5] & 0xFF;
            int ip3 = bytes[6] & 0xFF;
            int ip4 = bytes[7] & 0xFF;

            String host = ip1 + "." + ip2 + "." + ip3 + "." + ip4;

            return new String[]{host, String.valueOf(port)};

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * bind(SOCKET, NAME)
     * Binds a socket to an address.
     */
    public static RuntimeScalar bind(int ctx, RuntimeBase... args) {
        if (args.length < 2) {
            getGlobalVariable("main::!").set("Not enough arguments for bind");
            return scalarFalse;
        }

        try {
            RuntimeScalar socketHandle = args[0].scalar();
            RuntimeScalar address = args[1].scalar();

            RuntimeIO socketIO = socketHandle.getRuntimeIO();
            if (socketIO == null) {
                getGlobalVariable("main::!").set("Invalid socket handle for bind");
                return scalarFalse;
            }

            // Parse Perl-style packed socket address (sockaddr_in format)
            String addressStr = address.toString();
            String[] parts = parseSockaddrIn(addressStr);

            // Fallback to "host:port" string format if binary parsing fails
            if (parts == null) {
                parts = addressStr.split(":");
                if (parts.length != 2) {
                    getGlobalVariable("main::!").set("Invalid address format for bind (expected sockaddr_in or host:port)");
                    return scalarFalse;
                }
            }

            String host = parts[0];
            int port;
            try {
                port = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                getGlobalVariable("main::!").set("Invalid port number for bind");
                return scalarFalse;
            }

            // Delegate to RuntimeIO's bind method
            return socketIO.bind(host, port);

        } catch (Exception e) {
            getGlobalVariable("main::!").set("Bind failed: " + e.getMessage());
            return scalarFalse;
        }
    }

    /**
     * connect(SOCKET, NAME)
     * Connects a socket to an address.
     */
    public static RuntimeScalar connect(int ctx, RuntimeBase... args) {
        if (args.length < 2) {
            getGlobalVariable("main::!").set("Not enough arguments for connect");
            return scalarUndef;
        }

        try {
            RuntimeScalar socketHandle = args[0].scalar();
            RuntimeScalar address = args[1].scalar();

            RuntimeIO socketIO = socketHandle.getRuntimeIO();
            if (socketIO == null) {
                getGlobalVariable("main::!").set("Invalid socket handle for connect");
                return scalarUndef;
            }

            // Parse Perl-style packed socket address (sockaddr_in format)
            String addressStr = address.toString();
            String[] parts = parseSockaddrIn(addressStr);

            // Fallback to "host:port" string format if binary parsing fails
            if (parts == null) {
                parts = addressStr.split(":");
                if (parts.length != 2) {
                    getGlobalVariable("main::!").set("Invalid address format for connect (expected sockaddr_in or host:port)");
                    return scalarUndef;
                }
            }

            String host = parts[0];
            int port;
            try {
                port = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                getGlobalVariable("main::!").set("Invalid port number for connect");
                return scalarUndef;
            }

            // Delegate to RuntimeIO's connect method
            return socketIO.connect(host, port);

        } catch (Exception e) {
            getGlobalVariable("main::!").set("Connect failed: " + e.getMessage());
            return scalarUndef;
        }
    }

    /**
     * listen(SOCKET, QUEUESIZE)
     * Puts a socket into listening mode.
     */
    public static RuntimeScalar listen(int ctx, RuntimeBase... args) {
        if (args.length < 2) {
            getGlobalVariable("main::!").set("Not enough arguments for listen");
            return scalarFalse;
        }

        try {
            RuntimeScalar socketHandle = args[0].scalar();
            int queueSize = args[1].scalar().getInt();

            RuntimeIO socketIO = socketHandle.getRuntimeIO();
            if (socketIO == null) {
                getGlobalVariable("main::!").set("Invalid socket handle for listen");
                return scalarFalse;
            }

            // Delegate to RuntimeIO's listen method
            return socketIO.listen(queueSize);

        } catch (Exception e) {
            getGlobalVariable("main::!").set("Listen failed: " + e.getMessage());
            return scalarFalse;
        }
    }

    /**
     * accept(NEWSOCKET, GENERICSOCKET)
     * Accepts a connection on a listening socket.
     * Returns the packed sockaddr of the remote peer on success, false on failure.
     */
    public static RuntimeScalar accept(int ctx, RuntimeBase... args) {
        if (args.length < 2) {
            getGlobalVariable("main::!").set("Not enough arguments for accept");
            return scalarFalse;
        }

        try {
            RuntimeScalar newSocketHandle = args[0].scalar();
            RuntimeScalar listenSocketHandle = args[1].scalar();

            RuntimeIO listenRuntimeIO = listenSocketHandle.getRuntimeIO();
            if (listenRuntimeIO == null || !(listenRuntimeIO.ioHandle instanceof SocketIO listenSocketIO)) {
                getGlobalVariable("main::!").set("Invalid listening socket handle for accept");
                return scalarFalse;
            }

            // Accept the connection - returns a new SocketIO for the client
            SocketIO clientSocketIO = listenSocketIO.acceptConnection();
            if (clientSocketIO == null) {
                return scalarFalse;
            }

            // Wrap in RuntimeIO and associate with the NEWSOCKET glob
            RuntimeIO clientRuntimeIO = new RuntimeIO(clientSocketIO);
            // Assign a small sequential fileno for select() support
            clientRuntimeIO.assignFileno();

            RuntimeGlob targetGlob = null;
            if ((newSocketHandle.type == RuntimeScalarType.GLOB || newSocketHandle.type == RuntimeScalarType.GLOBREFERENCE)
                    && newSocketHandle.value instanceof RuntimeGlob glob) {
                targetGlob = glob;
            }

            if (targetGlob != null) {
                targetGlob.setIO(clientRuntimeIO);
            } else {
                // Create a new anonymous GLOB and assign it to the lvalue
                RuntimeScalar newGlob = new RuntimeScalar();
                newGlob.type = RuntimeScalarType.GLOBREFERENCE;
                RuntimeGlob anonGlob = new RuntimeGlob(null).setIO(clientRuntimeIO);
                newGlob.value = anonGlob;
                RuntimeIO.registerGlobForFdRecycling(anonGlob, clientRuntimeIO);
                newSocketHandle.set(newGlob);
            }

            // Return the packed sockaddr of the remote peer
            return clientSocketIO.getpeername();

        } catch (Exception e) {
            getGlobalVariable("main::!").set("Accept failed: " + e.getMessage());
            return scalarFalse;
        }
    }

    /**
     * pipe(READHANDLE, WRITEHANDLE)
     * Creates a pair of connected pipes.
     */
    public static RuntimeScalar pipe(int ctx, RuntimeBase... args) {
        if (args.length < 2) {
            getGlobalVariable("main::!").set("Not enough arguments for pipe");
            return scalarFalse;
        }

        try {
            // The arguments should be lvalue RuntimeScalars that can be modified
            RuntimeScalar readHandle = (RuntimeScalar) args[0];
            RuntimeScalar writeHandle = (RuntimeScalar) args[1];

            // Reject references - pipe() doesn't accept \$scalar
            if (readHandle.type == RuntimeScalarType.REFERENCE) {
                throw new RuntimeException("Bad filehandle: " + readHandle);
            }
            if (writeHandle.type == RuntimeScalarType.REFERENCE) {
                throw new RuntimeException("Bad filehandle: " + writeHandle);
            }

            // Create connected pipes using Java's PipedInputStream/PipedOutputStream
            java.io.PipedInputStream pipeIn = new java.io.PipedInputStream();
            java.io.PipedOutputStream pipeOut = new java.io.PipedOutputStream(pipeIn);

            // Create IOHandle implementations for the pipe ends.
            // Use createPair() so they share a writerClosed flag for EOF detection.
            InternalPipeHandle[] pair = InternalPipeHandle.createPair(pipeIn, pipeOut);
            InternalPipeHandle readerHandle = pair[0];
            InternalPipeHandle writerHandle = pair[1];

            // Create RuntimeIO objects for the handles
            RuntimeIO readerIO = new RuntimeIO();
            readerIO.ioHandle = readerHandle;

            RuntimeIO writerIO = new RuntimeIO();
            writerIO.ioHandle = writerHandle;

            // Handle autovivification for read handle (like open() does)
            RuntimeGlob readGlob = null;
            if ((readHandle.type == RuntimeScalarType.GLOB || readHandle.type == RuntimeScalarType.GLOBREFERENCE) 
                    && readHandle.value instanceof RuntimeGlob glob) {
                readGlob = glob;
            }
            if (readGlob != null) {
                readGlob.setIO(readerIO);
            } else {
                // Create a new anonymous GLOB and assign it to the lvalue
                RuntimeScalar newGlob = new RuntimeScalar();
                newGlob.type = RuntimeScalarType.GLOBREFERENCE;
                RuntimeGlob anonGlob = new RuntimeGlob(null).setIO(readerIO);
                newGlob.value = anonGlob;
                RuntimeIO.registerGlobForFdRecycling(anonGlob, readerIO);
                readHandle.set(newGlob);
            }

            // Handle autovivification for write handle (like open() does)
            RuntimeGlob writeGlob = null;
            if ((writeHandle.type == RuntimeScalarType.GLOB || writeHandle.type == RuntimeScalarType.GLOBREFERENCE) 
                    && writeHandle.value instanceof RuntimeGlob glob) {
                writeGlob = glob;
            }
            if (writeGlob != null) {
                writeGlob.setIO(writerIO);
            } else {
                // Create a new anonymous GLOB and assign it to the lvalue
                RuntimeScalar newGlob = new RuntimeScalar();
                newGlob.type = RuntimeScalarType.GLOBREFERENCE;
                RuntimeGlob anonGlob2 = new RuntimeGlob(null).setIO(writerIO);
                newGlob.value = anonGlob2;
                RuntimeIO.registerGlobForFdRecycling(anonGlob2, writerIO);
                writeHandle.set(newGlob);
            }

            return scalarTrue;

        } catch (Exception e) {
            getGlobalVariable("main::!").set("Pipe creation failed: " + e.getMessage());
            return scalarFalse;
        }
    }

    /**
     * truncate(FILEHANDLE, LENGTH) or truncate(EXPR, LENGTH)
     * Updated to use the new API signature pattern.
     */
    public static RuntimeScalar truncate(int ctx, RuntimeBase... args) {
        if (args.length < 2) {
            getGlobalVariable("main::!").set("Not enough arguments for truncate");
            return scalarFalse;
        }

        try {
            RuntimeBase firstArg = args[0];
            long length = args[1].scalar().getLong();

            // Check if first argument is a filehandle or a filename
            if (firstArg.scalar().getRuntimeIO() != null) {
                // First argument is a filehandle
                RuntimeIO fh = firstArg.scalar().getRuntimeIO();
                return fh.truncate(length);
            } else {
                // First argument is a filename
                String filename = firstArg.scalar().toString();
                try {
                    Path path = Path.of(filename);
                    FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE);
                    channel.truncate(length);
                    channel.close();
                    return scalarTrue;
                } catch (IOException e) {
                    getGlobalVariable("main::!").set("Truncate failed: " + e.getMessage());
                    return scalarFalse;
                }
            }

        } catch (Exception e) {
            getGlobalVariable("main::!").set("Truncate failed: " + e.getMessage());
            return scalarFalse;
        }
    }

    /**
     * flock(FILEHANDLE, OPERATION)
     * Applies or removes an advisory lock on a file.
     *
     * OPERATION is a bitmask:
     *   LOCK_SH (1) - Shared lock (for reading)
     *   LOCK_EX (2) - Exclusive lock (for writing)
     *   LOCK_UN (8) - Unlock
     *   LOCK_NB (4) - Non-blocking (can be OR'd with SH or EX)
     *
     * Returns true on success, false on failure.
     */
    public static RuntimeScalar flock(int ctx, RuntimeBase... args) {
        if (args.length < 2) {
            getGlobalVariable("main::!").set("Not enough arguments for flock");
            return scalarFalse;
        }

        try {
            RuntimeScalar fileHandle = args[0].scalar();
            int operation = args[1].scalar().getInt();

            RuntimeIO fh = fileHandle.getRuntimeIO();
            if (fh == null) {
                getGlobalVariable("main::!").set(9); // EBADF - Bad file descriptor
                return scalarFalse;
            }

            if (fh.ioHandle == null) {
                getGlobalVariable("main::!").set(9); // EBADF
                return scalarFalse;
            }

            return fh.ioHandle.flock(operation);

        } catch (Exception e) {
            getGlobalVariable("main::!").set("flock failed: " + e.getMessage());
            return scalarFalse;
        }
    }

    /**
     * fcntl(FILEHANDLE, FUNCTION, SCALAR)
     * Implements file control operations.
     * 
     * Common FUNCTION values (from Fcntl):
     *   F_GETFD (1) - Get file descriptor flags
     *   F_SETFD (2) - Set file descriptor flags  
     *   F_GETFL (3) - Get file status flags
     *   F_SETFL (4) - Set file status flags
     *   
     * Uses jnr-posix for native fcntl when a real file descriptor is available.
     */
    public static RuntimeScalar fcntl(int ctx, RuntimeBase... args) {
        if (args.length < 3) {
            getGlobalVariable("main::!").set("Not enough arguments for fcntl");
            return scalarFalse;
        }

        try {
            RuntimeScalar fileHandle = args[0].scalar();
            int function = args[1].scalar().getInt();
            int arg = args[2].scalar().getInt();

            RuntimeIO fh = fileHandle.getRuntimeIO();
            if (fh == null || fh.ioHandle == null) {
                getGlobalVariable("main::!").set(9); // EBADF - Bad file descriptor
                return scalarUndef;
            }

            // Get the file descriptor number
            RuntimeScalar filenoResult = fh.ioHandle.fileno();
            int fd = filenoResult.getDefinedBoolean() ? filenoResult.getInt() : -1;

            // If we have a valid native fd, use FFM POSIX
            if (fd >= 0 && !NativeUtils.IS_WINDOWS) {
                try {
                    int result = FFMPosix.get().fcntl(fd, function, arg);
                    if (result == -1) {
                        getGlobalVariable("main::!").set(FFMPosix.get().errno());
                        return scalarUndef;
                    }
                    return new RuntimeScalar(result);
                } catch (Exception e) {
                    // Fall through to stub implementation
                }
            }

            // Stub implementation for when native fcntl isn't available
            // Values from Fcntl.pm: F_GETFD=1, F_SETFD=2, F_GETFL=3, F_SETFL=4
            switch (function) {
                case 1: // F_GETFD - Get file descriptor flags
                    // Return 1 (FD_CLOEXEC would be set) to satisfy code that checks `unless $flags`
                    return new RuntimeScalar(1);
                    
                case 2: // F_SETFD - Set file descriptor flags (e.g., FD_CLOEXEC)
                    // Accept but ignore - stub can't set FD_CLOEXEC
                    return scalarTrue;
                    
                case 3: // F_GETFL - Get file status flags
                    // Return 0 (O_RDONLY)
                    return new RuntimeScalar(0);
                    
                case 4: // F_SETFL - Set file status flags
                    // Accept but ignore
                    return scalarTrue;
                    
                default:
                    // Unsupported function
                    getGlobalVariable("main::!").set("Unsupported fcntl function: " + function);
                    return scalarUndef;
            }

        } catch (Exception e) {
            getGlobalVariable("main::!").set("fcntl failed: " + e.getMessage());
            return scalarUndef;
        }
    }

    /**
     * ioctl(FILEHANDLE, FUNCTION, SCALAR)
     * Implements device control operations via FFM native ioctl.
     *
     * Perl semantics: returns "0 but true" when ioctl returns 0,
     * the actual integer for non-zero success, undef on error (with $! set).
     */
    public static RuntimeScalar ioctl(int ctx, RuntimeBase... args) {
        if (args.length < 3) {
            getGlobalVariable("main::!").set("Not enough arguments for ioctl");
            return scalarFalse;
        }

        try {
            RuntimeScalar fileHandle = args[0].scalar();
            long request = args[1].scalar().getLong();
            RuntimeScalar scalarArg = args[2].scalar();

            RuntimeIO fh = fileHandle.getRuntimeIO();
            if (fh == null || fh.ioHandle == null) {
                getGlobalVariable("main::!").set(9); // EBADF
                return scalarUndef;
            }

            // Get the native file descriptor
            RuntimeScalar filenoResult = fh.ioHandle.fileno();
            int fd = filenoResult.getDefinedBoolean() ? filenoResult.getInt() : -1;

            if (fd < 0 || NativeUtils.IS_WINDOWS) {
                getGlobalVariable("main::!").set("ioctl not supported on this handle");
                return scalarUndef;
            }

            // Determine if this is a pointer-type or int-type ioctl.
            // Perl ioctl passes either a packed binary string (pointer-type)
            // or an integer scalar (int-type, e.g., TIOCSCTTY with arg 0).
            // We detect by checking if the scalar looks like a binary buffer
            // (length > 4 is a good heuristic — struct winsize is 8 bytes).
            String scalarStr = scalarArg.toString();
            byte[] buf = scalarStr.getBytes(StandardCharsets.ISO_8859_1);
            int result;

            if (buf.length >= 8) {
                // Pointer argument — pass the scalar's bytes as a buffer
                result = FFMPosix.get().ioctlWithPointer(fd, request, buf);
                if (result >= 0) {
                    // Write modified buffer back to the scalar (for read-type ioctls like TIOCGWINSZ)
                    scalarArg.set(new String(buf, StandardCharsets.ISO_8859_1));
                }
            } else {
                // Integer argument (e.g., TIOCSCTTY with arg 0, or TIOCNOTTY)
                int intArg = scalarArg.getInt();
                result = FFMPosix.get().ioctlWithInt(fd, request, intArg);
            }

            if (result < 0) {
                getGlobalVariable("main::!").set(FFMPosix.get().errno());
                return scalarUndef;
            }

            // Perl convention: ioctl returns "0 but true" for 0, or the integer value
            if (result == 0) {
                return new RuntimeScalar("0 but true");
            }
            return new RuntimeScalar(result);

        } catch (UnsupportedOperationException e) {
            getGlobalVariable("main::!").set("ioctl not implemented on this platform");
            return scalarUndef;
        } catch (Exception e) {
            getGlobalVariable("main::!").set("ioctl failed: " + e.getMessage());
            return scalarUndef;
        }
    }

    /**
     * getsockname(SOCKET)
     * Returns the packed sockaddr structure for the local end of the socket.
     */
    public static RuntimeScalar getsockname(int ctx, RuntimeBase... args) {
        if (args.length < 1) {
            getGlobalVariable("main::!").set("Not enough arguments for getsockname");
            return scalarFalse;
        }

        try {
            RuntimeScalar socketHandle = args[0].scalar();
            RuntimeIO socketIO = socketHandle.getRuntimeIO();

            if (socketIO == null) {
                getGlobalVariable("main::!").set("Invalid socket handle for getsockname");
                return scalarFalse;
            }

            // Get the local socket address and pack it into sockaddr_in format
            return socketIO.getsockname();

        } catch (Exception e) {
            getGlobalVariable("main::!").set("getsockname failed: " + e.getMessage());
            return scalarFalse;
        }
    }

    /**
     * getpeername(SOCKET)
     * Returns the packed sockaddr structure for the remote end of the socket.
     */
    public static RuntimeScalar getpeername(int ctx, RuntimeBase... args) {
        if (args.length < 1) {
            getGlobalVariable("main::!").set("Not enough arguments for getpeername");
            return scalarFalse;
        }

        try {
            RuntimeScalar socketHandle = args[0].scalar();
            RuntimeIO socketIO = socketHandle.getRuntimeIO();

            if (socketIO == null) {
                getGlobalVariable("main::!").set("Invalid socket handle for getpeername");
                return scalarFalse;
            }

            // Get the remote socket address and pack it into sockaddr_in format
            return socketIO.getpeername();

        } catch (Exception e) {
            getGlobalVariable("main::!").set("getpeername failed: " + e.getMessage());
            return scalarFalse;
        }
    }

    /**
     * send(SOCKET, MSG, FLAGS [, TO])
     * Sends a message on a socket
     */
    public static RuntimeScalar send(int ctx, RuntimeBase... args) {
        if (args.length < 3) {
            getGlobalVariable("main::!").set("Not enough arguments for send");
            return scalarFalse;
        }

        try {
            RuntimeScalar socketHandle = args[0].scalar();
            String message = args[1].toString();
            int flags = args[2].scalar().getInt();

            RuntimeIO socketIO = socketHandle.getRuntimeIO();
            if (socketIO == null) {
                getGlobalVariable("main::!").set("Invalid socket handle for send");
                return scalarFalse;
            }

            // Check if this is a UDP socket with a TO address (4th arg)
            if (socketIO.ioHandle instanceof SocketIO sio && sio.isDatagramSocket()) {
                if (args.length >= 4) {
                    // 4th arg is packed sockaddr_in (destination address)
                    String packedAddr = args[3].toString();
                    byte[] addrBytes = packedAddr.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
                    if (addrBytes.length >= 8) {
                        int port = ((addrBytes[2] & 0xFF) << 8) | (addrBytes[3] & 0xFF);
                        String ip = String.format("%d.%d.%d.%d",
                                addrBytes[4] & 0xFF, addrBytes[5] & 0xFF,
                                addrBytes[6] & 0xFF, addrBytes[7] & 0xFF);
                        InetSocketAddress target = new InetSocketAddress(ip, port);
                        byte[] data = message.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
                        int sent = sio.sendTo(data, target);
                        return new RuntimeScalar(sent);
                    }
                }
                // No TO address — send to connected peer (not typical for UDP)
                getGlobalVariable("main::!").set("send: UDP requires destination address");
                return scalarUndef;
            }

            // TCP: ignore flags and TO address - send via stream
            RuntimeScalar result = socketIO.write(message);

            if (result != null && !result.equals(scalarFalse)) {
                return new RuntimeScalar(message.length()); // Return number of bytes sent
            } else {
                getGlobalVariable("main::!").set("Send failed");
                return scalarUndef;
            }

        } catch (Exception e) {
            getGlobalVariable("main::!").set("send failed: " + e.getMessage());
            return scalarUndef;
        }
    }

    /**
     * recv(SOCKET, SCALAR, LENGTH [, FLAGS])
     * Receives a message from a socket
     */
    public static RuntimeScalar recv(int ctx, RuntimeBase... args) {
        if (args.length < 3) {
            getGlobalVariable("main::!").set("Not enough arguments for recv");
            return scalarFalse;
        }

        try {
            RuntimeScalar socketHandle = args[0].scalar();
            RuntimeScalar buffer = args[1].scalar().scalarDeref();
            int length = args[2].scalar().getInt();
            int flags = args.length > 3 ? args[3].scalar().getInt() : 0;

            RuntimeIO socketIO = socketHandle.getRuntimeIO();
            if (socketIO == null) {
                getGlobalVariable("main::!").set("Invalid socket handle for recv");
                return scalarFalse;
            }

            // Check if this is a UDP socket
            if (socketIO.ioHandle instanceof SocketIO sio && sio.isDatagramSocket()) {
                byte[] data = sio.recvFrom(length);
                if (data != null) {
                    buffer.set(new String(data, java.nio.charset.StandardCharsets.ISO_8859_1));
                    // Return the sender's packed sockaddr (Perl recv() returns this)
                    return sio.getLastReceivedFrom();
                } else {
                    buffer.set("");
                    return scalarUndef;
                }
            }

            // TCP: Read data from socket stream
            RuntimeScalar data = socketIO.ioHandle.read(length);
            if (data != null && !data.equals(scalarUndef)) {
                buffer.set(data.toString());
                // For TCP, return the empty string (Perl returns "" for connected sockets)
                return new RuntimeScalar("");
            } else {
                buffer.set("");
                return scalarUndef;
            }

        } catch (Exception e) {
            getGlobalVariable("main::!").set("recv failed: " + e.getMessage());
            return scalarUndef;
        }
    }

    /**
     * shutdown(SOCKET, HOW)
     * Shuts down a socket connection
     * HOW: 0 = further receives disallowed, 1 = further sends disallowed, 2 = both
     */
    public static RuntimeScalar shutdown(int ctx, RuntimeBase... args) {
        if (args.length < 2) {
            getGlobalVariable("main::!").set("Not enough arguments for shutdown");
            return scalarFalse;
        }

        try {
            RuntimeScalar socketHandle = args[0].scalar();
            int how = args[1].scalar().getInt();

            RuntimeIO socketIO = socketHandle.getRuntimeIO();
            if (socketIO == null) {
                getGlobalVariable("main::!").set("Invalid socket handle for shutdown");
                return scalarFalse;
            }

            // For now, implement basic shutdown by closing the socket
            // In a full implementation, we would handle the different HOW values:
            // 0 = SHUT_RD (shutdown reading), 1 = SHUT_WR (shutdown writing), 2 = SHUT_RDWR (shutdown both)
            if (socketIO.ioHandle instanceof SocketIO) {
                // For simplicity, just return success - actual socket shutdown would be more complex
                return scalarTrue;
            } else {
                getGlobalVariable("main::!").set("Not a socket handle for shutdown");
                return scalarFalse;
            }

        } catch (Exception e) {
            getGlobalVariable("main::!").set("shutdown failed: " + e.getMessage());
            return scalarFalse;
        }
    }

    /**
     * setsockopt(SOCKET, LEVEL, OPTNAME, OPTVAL)
     * Sets socket options
     */
    public static RuntimeScalar setsockopt(int ctx, RuntimeBase... args) {
        if (args.length < 4) {
            getGlobalVariable("main::!").set("Not enough arguments for setsockopt");
            return scalarFalse;
        }

        try {
            RuntimeScalar socketHandle = args[0].scalar();
            int level = args[1].scalar().getInt();
            int optname = args[2].scalar().getInt();
            RuntimeScalar optvalScalar = args[3].scalar();
            String optval = optvalScalar.toString();

            RuntimeIO socketIO = socketHandle.getRuntimeIO();
            if (socketIO == null) {
                getGlobalVariable("main::!").set("Invalid socket handle for setsockopt");
                return scalarFalse;
            }

            // Handle socket option setting
            if (socketIO.ioHandle instanceof SocketIO socketIOHandle) {

                // Extract the integer value from the optval - handle both integer and string representations
                int optionValue = 0;

                // Use Perl's looksLikeNumber logic to determine how to handle the value
                if (ScalarUtils.looksLikeNumber(optvalScalar)) {
                    // This is a number - get it directly as an integer
                    optionValue = optvalScalar.getInt();
                } else if (optval.length() == 4) {
                    // This might be a packed binary value - check if it contains non-printable characters
                    boolean isPacked = false;
                    for (int i = 0; i < optval.length(); i++) {
                        char c = optval.charAt(i);
                        if (c < 32 || c > 126) { // Non-printable ASCII characters suggest binary data
                            isPacked = true;
                            break;
                        }
                    }

                    if (isPacked) {
                        // Unpack as little-endian integer (packed format)
                        byte[] bytes = optval.getBytes(StandardCharsets.ISO_8859_1);
                        optionValue = (bytes[0] & 0xFF) |
                                ((bytes[1] & 0xFF) << 8) |
                                ((bytes[2] & 0xFF) << 16) |
                                ((bytes[3] & 0xFF) << 24);
                    } else {
                        // Try to parse as string number
                        try {
                            optionValue = Integer.parseInt(optval.trim());
                        } catch (NumberFormatException e) {
                            // If it's not a parseable number, treat non-empty string as 1, empty as 0
                            optionValue = optval.length() > 0 ? 1 : 0;
                        }
                    }
                } else {
                    // Try to parse as string number
                    try {
                        optionValue = Integer.parseInt(optval.trim());
                    } catch (NumberFormatException e) {
                        // If it's not a parseable number, treat non-empty string as 1, empty as 0
                        optionValue = optval.length() > 0 ? 1 : 0;
                    }
                }

                // Use Java's native socket option support via SocketIO
                boolean success = socketIOHandle.setSocketOption(level, optname, optionValue);

                return success ? scalarTrue : scalarFalse;
            } else {
                getGlobalVariable("main::!").set("Not a socket handle for setsockopt");
                return scalarFalse;
            }

        } catch (Exception e) {
            getGlobalVariable("main::!").set("setsockopt failed: " + e.getMessage());
            return scalarFalse;
        }
    }

    /**
     * getsockopt(SOCKET, LEVEL, OPTNAME)
     * Gets socket options
     */
    public static RuntimeScalar getsockopt(int ctx, RuntimeBase... args) {
        if (args.length < 3) {
            getGlobalVariable("main::!").set("Not enough arguments for getsockopt");
            return scalarUndef;
        }

        try {
            RuntimeScalar socketHandle = args[0].scalar();
            int level = args[1].scalar().getInt();
            int optname = args[2].scalar().getInt();

            RuntimeIO socketIO = socketHandle.getRuntimeIO();
            if (socketIO == null) {
                getGlobalVariable("main::!").set("Invalid socket handle for getsockopt");
                return scalarUndef;
            }

            // Handle socket option retrieval
            if (socketIO.ioHandle instanceof SocketIO socketIOHandle) {

                // Use Java's native socket option support via SocketIO
                int optionValue = socketIOHandle.getSocketOption(level, optname);

                // For SO_ERROR, check actual socket connection status
                if (level == Socket.SOL_SOCKET && optname == Socket.SO_ERROR) {
                    optionValue = socketIOHandle.getSocketError();
                }

                // Pack the option value as a 4-byte integer and return it
                return new RuntimeScalar(pack("i", optionValue));
            } else {
                getGlobalVariable("main::!").set("Not a socket handle for getsockopt");
                return scalarUndef;
            }

        } catch (Exception e) {
            getGlobalVariable("main::!").set("getsockopt failed: " + e.getMessage());
            return scalarUndef;
        }
    }

    /**
     * Helper method to pack an integer as a binary string (simplified version)
     */
    private static String pack(String template, int value) {
        // Simple implementation for "i" template (signed integer)
        if ("i".equals(template)) {
            byte[] bytes = new byte[4];
            bytes[0] = (byte) (value & 0xFF);
            bytes[1] = (byte) ((value >> 8) & 0xFF);
            bytes[2] = (byte) ((value >> 16) & 0xFF);
            bytes[3] = (byte) ((value >> 24) & 0xFF);
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
        return "";
    }

    /**
     * Find a RuntimeIO handle by its file descriptor number.
     * Checks multiple registries: IOOperator's local fileDescriptorMap, standard fds,
     * and the RuntimeIO fileno registry (which includes dup'd handles and sockets).
     */
    private static RuntimeIO findFileHandleByDescriptor(int fd) {
        // Check if we have it in our mapping
        RuntimeIO handle = fileDescriptorMap.get(fd);
        if (handle != null) {
            return handle;
        }

        // Handle standard file descriptors
        switch (fd) {
            case 0: // STDIN
                return RuntimeIO.stdin;
            case 1: // STDOUT
                return RuntimeIO.stdout;
            case 2: // STDERR
                return RuntimeIO.stderr;
            default:
                // Check the RuntimeIO fileno registry (used by all file/pipe/socket handles)
                RuntimeIO fromRegistry = RuntimeIO.getByFileno(fd);
                if (fromRegistry != null) {
                    return fromRegistry;
                }
                return null; // Unknown file descriptor
        }
    }

    /**
     * Create a duplicate of a RuntimeIO handle.
     * This creates a new RuntimeIO that shares the same underlying IOHandle.
     */
    /**
     * Opens a filehandle by duplicating an existing one (for 2-argument open with dup mode).
     * This handles cases like: open(my $fh, ">&1")
     *
     * @param fileName The file descriptor number or handle name
     * @param mode     The duplication mode (>&, <&, etc.)
     * @return RuntimeIO handle that duplicates the original, or null on error
     */
    public static RuntimeIO openFileHandleDup(String fileName, String mode) {
        boolean isParsimonious = mode.endsWith("="); // &= modes reuse file descriptor

        RuntimeIO sourceHandle = null;

        // Check if it's a numeric file descriptor
        if (fileName.matches("^\\d+$")) {
            int fd = Integer.parseInt(fileName);
            sourceHandle = findFileHandleByDescriptor(fd);
            if (sourceHandle == null || sourceHandle.ioHandle == null) {
                throw new PerlCompilerException("Bad file descriptor: " + fd);
            }
        } else {
            // Handle named filehandles — always use glob table to get the CURRENT handle,
            // not the static RuntimeIO.stdout/stdin/stderr fields which may be stale
            // after redirections like open(STDOUT, ">file") + open(STDOUT, ">&SAVED").
            String normalizedName;
            if (fileName.equalsIgnoreCase("STDIN") || fileName.equalsIgnoreCase("STDOUT") || fileName.equalsIgnoreCase("STDERR")) {
                normalizedName = "main::" + fileName.toUpperCase();
            } else if (!fileName.contains("::")) {
                // Try current package first, then fall back to main::
                String currentPkg = RuntimeCode.getCurrentPackage();
                if (currentPkg.endsWith("::")) {
                    currentPkg = currentPkg.substring(0, currentPkg.length() - 2);
                }
                if (currentPkg != null && !currentPkg.isEmpty() && !currentPkg.equals("main")) {
                    String currentPkgName = currentPkg + "::" + fileName;
                    RuntimeGlob currentGlob = GlobalVariable.getGlobalIO(currentPkgName);
                    if (currentGlob != null) {
                        sourceHandle = currentGlob.getRuntimeIO();
                        if (sourceHandle != null && sourceHandle.ioHandle != null) {
                            normalizedName = null; // Already found
                        } else {
                            normalizedName = "main::" + fileName;
                        }
                    } else {
                        normalizedName = "main::" + fileName;
                    }
                } else {
                    normalizedName = "main::" + fileName;
                }
            } else {
                normalizedName = fileName;
            }

            if (sourceHandle == null) {
                RuntimeGlob glob = GlobalVariable.getGlobalIO(normalizedName);
                if (glob != null) {
                    sourceHandle = glob.getRuntimeIO();
                }
                if (sourceHandle == null || sourceHandle.ioHandle == null) {
                    // Last resort: try static fields for standard handles
                    switch (fileName.toUpperCase()) {
                        case "STDIN": sourceHandle = RuntimeIO.stdin; break;
                        case "STDOUT": sourceHandle = RuntimeIO.stdout; break;
                        case "STDERR": sourceHandle = RuntimeIO.stderr; break;
                        default:
                            throw new PerlCompilerException("Unsupported filehandle duplication: " + fileName);
                    }
                    if (sourceHandle == null || sourceHandle.ioHandle == null) {
                        throw new PerlCompilerException("Unsupported filehandle duplication: " + fileName);
                    }
                }
            }
        }

        if (isParsimonious) {
            return createBorrowedHandle(sourceHandle);
        } else {
            return duplicateFileHandle(sourceHandle);
        }
    }

    /**
     * Creates a borrowed (parsimonious dup) handle that shares the source's IOHandle and fileno.
     * The borrowed handle delegates all I/O to the source but does NOT close the underlying
     * resource when closed — only flushes. Both handles report the same fileno.
     */
    private static RuntimeIO createBorrowedHandle(RuntimeIO source) {
        if (source == null || source.ioHandle == null || source.ioHandle instanceof ClosedIOHandle) {
            // Same as duplicateFileHandle — reject closed handles for &= mode too.
            RuntimeIO.handleIOError("Bad file descriptor");
            return null;
        }
        RuntimeIO borrowed = new RuntimeIO();
        borrowed.ioHandle = new BorrowedIOHandle(source.ioHandle);
        borrowed.currentLineNumber = source.currentLineNumber;
        // Share the source's fileno (parsimonious dup = same fd)
        int sourceFd = source.getAssignedFileno();
        if (sourceFd < 0) {
            RuntimeScalar nativeFd = source.ioHandle.fileno();
            sourceFd = nativeFd.getDefinedBoolean() ? nativeFd.getInt() : source.assignFileno();
        }
        borrowed.registerExternalFd(sourceFd);
        return borrowed;
    }

    private static RuntimeIO duplicateFileHandle(RuntimeIO original) {
        if (original == null || original.ioHandle == null || original.ioHandle instanceof ClosedIOHandle) {
            // Reject closed handles — in Perl 5, dup of a closed fd fails with EBADF.
            // Without this check, ClosedIOHandle gets wrapped in DupIOHandle and
            // open($fh, '>&STDERR') succeeds when STDERR is closed (bug: returns true
            // instead of false, preventing the "or die(...)" pattern).
            RuntimeIO.handleIOError("Bad file descriptor");
            return null;
        }

        RuntimeIO duplicate = new RuntimeIO();
        duplicate.currentLineNumber = original.currentLineNumber;

        if (original.ioHandle instanceof DupIOHandle existingDup) {
            // Already reference-counted — add another dup sharing the same delegate
            duplicate.ioHandle = DupIOHandle.addDup(existingDup);
        } else {
            // First duplication — wrap both original and duplicate in DupIOHandles
            // so they share a refcount and get distinct filenos.

            // If the original is a LayeredIOHandle (e.g. STDOUT after
            // `binmode :encoding(utf8)`), we must preserve the layers on both
            // sides of the dup. Otherwise PerlIO::get_layers loses the layers
            // on the original and Test2::Util::clone_io drops them on the
            // duplicate, leading to spurious "Wide character in print"
            // warnings. We do this by dup'ing the LayeredIOHandle's inner
            // delegate and re-wrapping each side in its own LayeredIOHandle
            // that shares the same active layers.
            LayeredIOHandle layeredWrapper = null;
            IOHandle dupTarget = original.ioHandle;
            if (original.ioHandle instanceof LayeredIOHandle lh) {
                layeredWrapper = lh;
                dupTarget = lh.getDelegate();
            }

            int origFd = original.getAssignedFileno();
            if (origFd < 0) {
                // Not in the registry — ask the IOHandle directly
                RuntimeScalar fdScalar = original.ioHandle.fileno();
                origFd = fdScalar.getDefinedBoolean() ? fdScalar.getInt() : -1;
            }
            if (origFd < 0) {
                // Still no fd — assign a new one
                origFd = original.assignFileno();
            }
            DupIOHandle[] pair = DupIOHandle.createPair(dupTarget, origFd);

            if (layeredWrapper != null) {
                LayeredIOHandle origLayered = new LayeredIOHandle(pair[0]);
                origLayered.activeLayers.addAll(layeredWrapper.activeLayers);
                LayeredIOHandle dupLayered = new LayeredIOHandle(pair[1]);
                dupLayered.activeLayers.addAll(layeredWrapper.activeLayers);
                original.ioHandle = origLayered;
                duplicate.ioHandle = dupLayered;
            } else {
                original.ioHandle = pair[0];  // Replace original's handle with refcounted wrapper
                duplicate.ioHandle = pair[1]; // New handle with unique fd
            }
        }

        // Register the duplicate's fd in RuntimeIO's fileno registry
        int dupFd = duplicate.ioHandle.fileno().getInt();
        if (dupFd >= 0) {
            duplicate.registerExternalFd(dupFd);
        }

        if (System.getenv("JPERL_IO_DEBUG") != null) {
            String origFileno;
            try {
                origFileno = original.ioHandle.fileno().toString();
            } catch (Throwable t) {
                origFileno = "<err>";
            }
            String dupFileno;
            try {
                dupFileno = duplicate.ioHandle.fileno().toString();
            } catch (Throwable t) {
                dupFileno = "<err>";
            }
            System.err.println("[JPERL_IO_DEBUG] duplicateFileHandle: origIoHandle=" + original.ioHandle.getClass().getName() +
                    " origFileno=" + origFileno +
                    " dupFileno=" + dupFileno +
                    " origIoHandleId=" + System.identityHashCode(original.ioHandle) +
                    " dupIoHandleId=" + System.identityHashCode(duplicate.ioHandle));
            System.err.flush();
        }

        return duplicate;
    }

    /**
     * Register a RuntimeIO handle with a file descriptor number for duplication support.
     */
    public static void registerFileDescriptor(int fd, RuntimeIO handle) {
        if (handle != null) {
            fileDescriptorMap.put(fd, handle);
        }
    }

    /**
     * Unregister a file descriptor when the handle is closed.
     */
    public static void unregisterFileDescriptor(int fd) {
        fileDescriptorMap.remove(fd);
    }

    /**
     * Create a pair of connected sockets (socketpair operator)
     * This creates two connected sockets that can communicate with each other
     */
    public static RuntimeScalar socketpair(int ctx, RuntimeBase... args) {
        if (args.length < 5) {
            throw new PerlCompilerException("Not enough arguments for socketpair");
        }

        try {
            // The first two arguments are the socket handle scalars
            RuntimeScalar sock1Handle = args[0].scalar();
            RuntimeScalar sock2Handle = args[1].scalar();

            // Use NIO SocketChannels so that select() can monitor these sockets
            ServerSocketChannel serverChannel = ServerSocketChannel.open();
            serverChannel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            int port = ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();

            // Create the first socket channel and connect it
            SocketChannel channel1 = SocketChannel.open();
            channel1.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));

            // Accept the connection to get the second socket channel
            SocketChannel channel2 = serverChannel.accept();

            // Close the server channel as we no longer need it
            serverChannel.close();

            // Create RuntimeIO objects for both sockets using NIO channels
            RuntimeIO io1 = new RuntimeIO();
            io1.ioHandle = new SocketIO(channel1, StandardProtocolFamily.INET);
            io1.assignFileno();

            RuntimeIO io2 = new RuntimeIO();
            io2.ioHandle = new SocketIO(channel2, StandardProtocolFamily.INET);
            io2.assignFileno();

            // Set IO slot on each handle, following the same pattern as socket()
            setSocketOnHandle(sock1Handle, io1);
            setSocketOnHandle(sock2Handle, io2);

            return scalarTrue;

        } catch (IOException e) {
            // Set $! to the error message
            getGlobalVariable("main::!").set(e.getMessage());
            return scalarFalse;
        }
    }

    /**
     * Helper to set a RuntimeIO on a socket handle, auto-vivifying a glob if needed.
     */
    private static void setSocketOnHandle(RuntimeScalar handle, RuntimeIO io) {
        RuntimeGlob targetGlob = null;
        if ((handle.type == RuntimeScalarType.GLOB || handle.type == RuntimeScalarType.GLOBREFERENCE)
                && handle.value instanceof RuntimeGlob glob) {
            targetGlob = glob;
        }
        if (targetGlob != null) {
            targetGlob.setIO(io);
        } else {
            // Create a new anonymous GLOB and assign it to the lvalue
            RuntimeScalar newGlob = new RuntimeScalar();
            newGlob.type = RuntimeScalarType.GLOBREFERENCE;
            RuntimeGlob anonGlob = new RuntimeGlob(null).setIO(io);
            newGlob.value = anonGlob;
            RuntimeIO.registerGlobForFdRecycling(anonGlob, io);
            handle.set(newGlob);
        }
    }

    // =================================================================
    // Adapter overloads for MiscOpcodeHandler (int ctx, RuntimeBase... args) signature
    // =================================================================

    public static RuntimeScalar seek(int ctx, RuntimeBase... args) {
        if (args.length < 3) throw new PerlCompilerException("Not enough arguments for seek");
        RuntimeList list = new RuntimeList();
        for (int i = 1; i < args.length; i++) list.add(args[i]);
        return seek(args[0].scalar(), list);
    }

    public static RuntimeScalar tell(int ctx, RuntimeBase... args) {
        RuntimeScalar fh = args.length > 0 ? args[0].scalar() : new RuntimeScalar();
        return tell(fh);
    }

    public static RuntimeScalar binmode(int ctx, RuntimeBase... args) {
        if (args.length < 1) throw new PerlCompilerException("Not enough arguments for binmode");
        RuntimeList list = new RuntimeList();
        for (int i = 1; i < args.length; i++) list.add(args[i]);
        return binmode(args[0].scalar(), list);
    }

    public static RuntimeScalar eof(int ctx, RuntimeBase... args) {
        RuntimeScalar fh = args.length > 0 ? args[0].scalar() : new RuntimeScalar();
        return eof(fh);
    }

    public static RuntimeScalar printf(int ctx, RuntimeBase... args) {
        if (args.length < 1) throw new PerlCompilerException("Not enough arguments for printf");
        RuntimeScalar fh = args[0].scalar();
        RuntimeList list = new RuntimeList();
        for (int i = 1; i < args.length; i++) {
            if (args[i] instanceof RuntimeArray array) {
                for (int j = 0; j < array.size(); j++) {
                    list.add(array.get(j));
                }
            } else {
                list.add(args[i]);
            }
        }
        return printf(list, fh);
    }

    public static RuntimeScalar readline(int ctx, RuntimeBase... args) {
        RuntimeScalar fh = args.length > 0 ? args[0].scalar() : new RuntimeScalar("main::STDIN");
        return (RuntimeScalar) Readline.readline(fh, ctx);
    }

    public static RuntimeScalar sysseek(int ctx, RuntimeBase... args) {
        if (args.length < 3) throw new PerlCompilerException("Not enough arguments for sysseek");
        RuntimeList list = new RuntimeList();
        for (int i = 1; i < args.length; i++) list.add(args[i]);
        return sysseek(args[0].scalar(), list);
    }

    public static RuntimeScalar read(int ctx, RuntimeBase... args) {
        return sysread(ctx, args);
    }

}
