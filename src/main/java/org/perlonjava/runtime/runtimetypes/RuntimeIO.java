package org.perlonjava.runtime.runtimetypes;

/*
    Additional Features

    Handling pipes (e.g., |- or -| modes).
    Handling in-memory file operations with ByteArrayInputStream or ByteArrayOutputStream.
    Implementing modes for read/write (+<, +>) operations.
 */

import org.perlonjava.runtime.io.*;
import org.perlonjava.runtime.operators.IOOperator;
import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.perlmodule.Warnings;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.perlonjava.runtime.runtimetypes.GlobalVariable.getGlobalIO;
import static org.perlonjava.runtime.runtimetypes.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarFalse;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarTrue;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarUndef;

/**
 * Represents a Perl-style I/O handle with support for files, directories, and streams.
 *
 * <p>This class provides a unified interface for all I/O operations in PerlOnJava,
 * mimicking Perl's filehandle behavior. It supports:
 * <ul>
 *   <li>File I/O with various modes (read, write, append, read-write)</li>
 *   <li>Standard I/O streams (STDIN, STDOUT, STDERR)</li>
 *   <li>Directory operations</li>
 *   <li>In-memory I/O with scalar references</li>
 *   <li>I/O layers for encoding and line-ending conversions</li>
 *   <li>Perl-compatible special variables (like $. for line numbers)</li>
 * </ul>
 *
 * <p>The class implements {@link RuntimeScalarReference} to allow filehandles
 * to be used as scalar values in Perl code, supporting operations like:
 * <pre>
 * my $fh = *STDOUT;
 * my $ref = \*STDERR;
 * </pre>
 *
 * <p>File modes supported:
 * <ul>
 *   <li>{@code <} - Read mode (default)</li>
 *   <li>{@code >} - Write mode (truncate)</li>
 *   <li>{@code >>} - Append mode</li>
 *   <li>{@code +<} - Read-write mode</li>
 *   <li>{@code +>} - Read-write mode (truncate)</li>
 * </ul>
 *
 * @see IOHandle
 * @see DirectoryIO
 * @see RuntimeScalarReference
 */
public class RuntimeIO extends RuntimeScalar {

    // Platform-specific ENOTEMPTY (only errno that differs across platforms in handleIOException)
    private static final int ENOTEMPTY;
    static {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac") || os.contains("darwin")) {
            ENOTEMPTY = 66;
        } else if (os.contains("win")) {
            ENOTEMPTY = 41;
        } else {
            ENOTEMPTY = 39; // Linux
        }
    }

    /**
     * Mapping of Perl file modes to their corresponding Java NIO StandardOpenOption sets.
     * This allows easy conversion from Perl-style mode strings to Java NIO options.
     */
    private static final Map<String, Set<StandardOpenOption>> MODE_OPTIONS = new HashMap<>();

    /**
     * Maximum number of file handles to keep in the LRU cache.
     * Older handles are flushed (not closed) when this limit is exceeded.
     */
    private static final int MAX_OPEN_HANDLES = 100;

    /**
     * LRU (Least Recently Used) cache for managing open file handles.
     * This helps prevent resource exhaustion by limiting open handles and
     * automatically flushing less recently used ones.
     */
    private static final Map<IOHandle, Boolean> openHandles = new LinkedHashMap<IOHandle, Boolean>(MAX_OPEN_HANDLES, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<IOHandle, Boolean> eldest) {
            if (size() > MAX_OPEN_HANDLES) {
                try {
                    // Flush but don't close the eldest handle
                    eldest.getKey().flush();
                } catch (Exception e) {
                    // Handle exception if needed
                }
                return true;
            }
            return false;
        }
    };

    private static final Map<Long, Process> childProcesses = new java.util.concurrent.ConcurrentHashMap<>();

    // ---- I/O state is now per-PerlRuntime. These static accessors delegate to current runtime. ----

    /** Returns the standard output handle for the current runtime. */
    public static RuntimeIO getStdout() { return PerlRuntime.current().ioStdout; }
    /** Sets the standard output handle for the current runtime. */
    public static void setStdout(RuntimeIO io) { PerlRuntime.current().ioStdout = io; }

    /** Returns the standard error handle for the current runtime. */
    public static RuntimeIO getStderr() { return PerlRuntime.current().ioStderr; }
    /** Sets the standard error handle for the current runtime. */
    public static void setStderr(RuntimeIO io) { PerlRuntime.current().ioStderr = io; }

    /** Returns the standard input handle for the current runtime. */
    public static RuntimeIO getStdin() { return PerlRuntime.current().ioStdin; }
    /** Sets the standard input handle for the current runtime. */
    public static void setStdin(RuntimeIO io) { PerlRuntime.current().ioStdin = io; }

    /** Returns the last accessed handle for the current runtime. */
    public static RuntimeIO getLastAccessedHandle() { return PerlRuntime.current().ioLastAccessedHandle; }
    /** Sets the last accessed handle for the current runtime. */
    public static void setLastAccessedHandle(RuntimeIO io) { PerlRuntime.current().ioLastAccessedHandle = io; }

    /** Returns the last readline handle name for the current runtime. */
    public static String getLastReadlineHandleName() { return PerlRuntime.current().ioLastReadlineHandleName; }
    /** Sets the last readline handle name for the current runtime. */
    public static void setLastReadlineHandleName(String name) { PerlRuntime.current().ioLastReadlineHandleName = name; }

    /** Returns the last written handle for the current runtime. */
    public static RuntimeIO getLastWrittenHandle() { return PerlRuntime.current().ioLastWrittenHandle; }
    /** Sets the last written handle for the current runtime. */
    public static void setLastWrittenHandle(RuntimeIO io) { PerlRuntime.current().ioLastWrittenHandle = io; }

    /** Returns the currently selected output handle for the current runtime. */
    public static RuntimeIO getSelectedHandle() { return PerlRuntime.current().ioSelectedHandle; }
    /** Sets the currently selected output handle for the current runtime. */
    public static void setSelectedHandle(RuntimeIO io) { PerlRuntime.current().ioSelectedHandle = io; }

    /**
     * Fileno registry for select() support.
     * <p>
     * Maps small sequential integers ("virtual fds") to RuntimeIO objects,
     * allowing 4-arg select() to find handles from bit-vector fileno values
     * and dup-by-fd operations like {@code open(*STDOUT, ">&3")}.
     * <p>
     * Standard fds 0-2 are reserved for stdin/stdout/stderr and are handled
     * natively by StandardIO (not through this registry).
     * <p>
     * <b>Fd recycling:</b> Freed fds are collected in a queue and reused by
     * {@code assignFileno()} (lowest available first) to mimic OS fd allocation.
     * This is safe because fds are only freed on explicit {@code close()} —
     * the eager {@code closeIOOnDrop()} was removed from variable assignment
     * (see RuntimeScalar.setLarge), so fds are never prematurely freed while
     * other variables still reference the handle.
     */
    private static final AtomicInteger nextFileno = new AtomicInteger(3);
    private static final ConcurrentHashMap<Integer, RuntimeIO> filenoToIO = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<RuntimeIO, Integer> ioToFileno = new ConcurrentHashMap<>();
    /** Pool of released fd numbers available for reuse. Perl reuses fds (lowest available),
     *  so we must do the same to pass tests like io/perlio_leaks.t that verify fd recycling. */
    private static final ConcurrentLinkedQueue<Integer> recycledFds = new ConcurrentLinkedQueue<>();

    /**
     * GC-based fd recycling for anonymous lexical filehandles.
     * <p>
     * In Perl 5, lexical filehandles ({@code my $fh}) are closed via DESTROY
     * when the SV's refcount drops to zero at scope exit. PerlOnJava doesn't
     * implement DESTROY or reference counting; the JVM uses tracing GC instead.
     * <p>
     * To reclaim fds from abandoned lexical handles, we register a
     * {@link PhantomReference} on the anonymous {@link RuntimeGlob} created by
     * {@code open(my $fh, ...)}. When the glob becomes phantom-reachable (all
     * RuntimeScalars referencing it have been abandoned), the PhantomReference
     * is enqueued. {@link #processAbandonedGlobs()} polls this queue, closes
     * the associated RuntimeIO, and frees the fd for reuse.
     * <p>
     * {@code processAbandonedGlobs()} is called from {@link #assignFileno()}.
     * If no recycled fds are available, {@code System.gc()} is called as a hint
     * to encourage the JVM to enqueue phantom references sooner.
     */
    private static final ReferenceQueue<RuntimeGlob> globGCQueue = new ReferenceQueue<>();
    private static final ConcurrentHashMap<PhantomReference<RuntimeGlob>, RuntimeIO> phantomToIO = new ConcurrentHashMap<>();

    /**
     * Registers an anonymous RuntimeGlob for GC-based fd recycling.
     * When the glob becomes unreachable (all variables referencing it are
     * reassigned or go out of scope), the associated RuntimeIO will be
     * closed and its fd freed.
     *
     * @param glob the anonymous RuntimeGlob to track
     * @param io   the RuntimeIO whose fd should be freed when the glob is collected
     */
    public static void registerGlobForFdRecycling(RuntimeGlob glob, RuntimeIO io) {
        PhantomReference<RuntimeGlob> phantom = new PhantomReference<>(glob, globGCQueue);
        phantomToIO.put(phantom, io);
    }

    /**
     * Processes the GC reference queue: closes RuntimeIO handles whose
     * parent RuntimeGlob has been collected, freeing their fds for reuse.
     */
    public static void processAbandonedGlobs() {
        Reference<? extends RuntimeGlob> ref;
        while ((ref = globGCQueue.poll()) != null) {
            RuntimeIO io = phantomToIO.remove(ref);
            if (io != null && !(io.ioHandle instanceof ClosedIOHandle)) {
                io.close();
            }
        }
    }

    /**
     * Assigns a fileno to this RuntimeIO and registers it in the fd→IO maps.
     * Reuses released fd numbers when available (lowest first via the recycle pool),
     * otherwise allocates the next sequential fd. This matches POSIX semantics where
     * close() releases an fd and the next open() reuses the lowest available fd.
     * <p>
     * If this RuntimeIO already has an assigned fileno, returns it (idempotent).
     * Called eagerly from {@code duplicateFileHandle()} for dup'd handles
     * (so dup-by-fd like {@code open(*FH, ">&3")} can find them), and lazily
     * from {@code fileno()} for regular file/pipe handles.
     *
     * @return the assigned fileno (always >= 3)
     */
    public int assignFileno() {
        Integer existing = ioToFileno.get(this);
        if (existing != null) {
            return existing;
        }
        // First, process any GC'd globs to free their fds
        processAbandonedGlobs();
        // Try to reuse the lowest freed fd
        int fd = tryRecycleLowestFd();
        if (fd >= 0) {
            filenoToIO.put(fd, this);
            ioToFileno.put(this, fd);
            FileDescriptorTable.advancePast(fd);
            return fd;
        }
        // No recycled fds — if there are tracked anonymous globs that might
        // be collectible, hint the GC and retry a few times with short sleeps
        // to give the ReferenceHandler thread time to process the queue.
        if (!phantomToIO.isEmpty()) {
            for (int attempt = 0; attempt < 5; attempt++) {
                System.gc();
                try { Thread.sleep(1); } catch (InterruptedException e) { break; }
                processAbandonedGlobs();
                fd = tryRecycleLowestFd();
                if (fd >= 0) {
                    filenoToIO.put(fd, this);
                    ioToFileno.put(this, fd);
                    FileDescriptorTable.advancePast(fd);
                    return fd;
                }
            }
        }
        // Allocate a fresh fd
        fd = nextFileno.getAndIncrement();
        filenoToIO.put(fd, this);
        ioToFileno.put(this, fd);
        FileDescriptorTable.advancePast(fd);
        return fd;
    }

    /**
     * Tries to recycle the lowest available freed fd.
     * Returns -1 if none available.
     */
    private static int tryRecycleLowestFd() {
        List<Integer> candidates = new ArrayList<>();
        Integer recycled;
        while ((recycled = recycledFds.poll()) != null) {
            candidates.add(recycled);
        }
        if (candidates.isEmpty()) {
            return -1;
        }
        Collections.sort(candidates);
        int fd = candidates.get(0);
        // Put back the rest
        for (int i = 1; i < candidates.size(); i++) {
            recycledFds.add(candidates.get(i));
        }
        return fd;
    }

    /**
     * Gets the assigned fileno for this RuntimeIO, or -1 if not assigned.
     */
    public int getAssignedFileno() {
        Integer fd = ioToFileno.get(this);
        return fd != null ? fd : -1;
    }

    /**
     * Looks up a RuntimeIO by its assigned fileno.
     */
    public static RuntimeIO getByFileno(int fd) {
        return filenoToIO.get(fd);
    }

    /**
     * Registers this RuntimeIO at a specific fd number (e.g. one already assigned
     * by FileDescriptorTable for pipes). Advances nextFileno past this fd to
     * prevent future collisions with assignFileno().
     *
     * @param fd the file descriptor number to register at
     */
    public void registerExternalFd(int fd) {
        filenoToIO.put(fd, this);
        ioToFileno.put(this, fd);
        // Advance nextFileno past this fd to avoid collisions
        nextFileno.updateAndGet(current -> Math.max(current, fd + 1));
    }

    /**
     * Unregisters this RuntimeIO from the fileno registry and returns
     * the fd to the recycle pool for reuse by future {@link #assignFileno()} calls.
     */
    public void unregisterFileno() {
        Integer fd = ioToFileno.remove(this);
        if (fd != null) {
            filenoToIO.remove(fd);
            // Return fd to the recycle pool so it can be reused (POSIX: lowest available)
            recycledFds.add(fd);
        }
    }

    /**
     * Advances the nextFileno counter past the given fd value.
     * Called by FileDescriptorTable.register() to keep the two fd allocation
     * systems in sync and prevent fd collisions.
     *
     * @param fd the fd value to advance past
     */
    public static void advanceFilenoCounterPast(int fd) {
        nextFileno.updateAndGet(current -> Math.max(current, fd + 1));
    }

    static {
        // Initialize mode options mapping
        MODE_OPTIONS.put("<", EnumSet.of(StandardOpenOption.READ));
        MODE_OPTIONS.put(">", EnumSet.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));
        MODE_OPTIONS.put(">>", EnumSet.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.APPEND));
        MODE_OPTIONS.put("+<", EnumSet.of(StandardOpenOption.READ, StandardOpenOption.WRITE));
        MODE_OPTIONS.put("+>", EnumSet.of(StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));
        MODE_OPTIONS.put("+>>", EnumSet.of(StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE));
    }

    /**
     * Line number counter for the current filehandle, corresponds to Perl's $. variable.
     * Incremented for each line read from this handle.
     */
    public int currentLineNumber = 0;
    /**
     * The underlying I/O handle that performs actual I/O operations.
     * Can be a file, socket, pipe, or custom I/O implementation.
     */
    public IOHandle ioHandle = new ClosedIOHandle(); // Initialize with ClosedIOHandle
    /**
     * Directory handle for directory operations (opendir, readdir, etc.).
     * Mutually exclusive with ioHandle - a RuntimeIO is either a file or directory handle.
     */
    public DirectoryIO directoryIO;
    /**
     * The name of the glob that owns this IO handle (e.g., "main::STDOUT").
     * Used for stringification when the filehandle is used in string context.
     * Null if this handle is not associated with a named glob.
     */
    public String globName;
    /**
     * Flag indicating if this handle has unflushed output.
     * Used to determine when automatic flushing is needed.
     */
    boolean needFlush;
    boolean autoFlush;

    /**
     * Creates a new uninitialized I/O handle.
     * The handle must be opened before use.
     */
    public RuntimeIO() {
    }

    /**
     * Creates a new I/O handle wrapping an existing IOHandle.
     * Used for sockets, pipes, and custom I/O implementations.
     *
     * @param ioHandle the IOHandle implementation to wrap
     */
    public RuntimeIO(IOHandle ioHandle) {
        this.ioHandle = ioHandle;
    }

    /**
     * Creates a new directory handle.
     *
     * @param directoryIO the directory I/O implementation
     */
    public RuntimeIO(DirectoryIO directoryIO) {
        this.directoryIO = directoryIO;
    }

    /**
     * Checks if this handle is in byte mode (no encoding layers).
     *
     * <p>In Perl, reads from handles without encoding layers (e.g., :raw, :bytes,
     * or default mode) produce byte strings (UTF-8 flag off). Reads from handles
     * with encoding layers (e.g., :utf8, :encoding(UTF-8)) produce character
     * strings (UTF-8 flag on).</p>
     *
     * @return true if the handle produces byte data (no encoding layers active)
     */
    public boolean isByteMode() {
        if (ioHandle instanceof LayeredIOHandle layered) {
            return !layered.hasEncodingLayer();
        }
        // Non-layered handles (CustomFileChannel, etc.) are always byte mode
        return true;
    }

    public static void registerChildProcess(Process p) {
        if (p != null) childProcesses.put(p.pid(), p);
    }

    public static Process getChildProcess(long pid) {
        return childProcesses.get(pid);
    }

    public static Process removeChildProcess(long pid) {
        return childProcesses.remove(pid);
    }

    /**
     * Sets a custom output stream as the last accessed handle.
     * This is primarily used for testing and special output redirection.
     *
     * @param out the OutputStream to wrap
     */
    public static void setCustomOutputStream(OutputStream out) {
        setLastWrittenHandle(new RuntimeIO(new CustomOutputStreamHandle(out)));
    }

    /**
     * Handles I/O errors by setting the Perl $! variable with errno.
     *
     * @param errno the errno number to set in $!
     * @return RuntimeScalar false value for error indication
     */
    public static RuntimeScalar handleIOError(int errno) {
        getGlobalVariable("main::!").set(errno);
        return scalarFalse;
    }

    /**
     * Handles I/O errors by setting the Perl $! variable.
     * This is a legacy method that sets errno to 0 with a custom message.
     *
     * @param message the error message to set in $!
     * @return RuntimeScalar false value for error indication
     */
    public static RuntimeScalar handleIOError(String message) {
        // For backward compatibility, set to EIO with custom message via string
        getGlobalVariable("main::!").set(message);
        return scalarFalse;
    }

    /**
     * Handles IOException by detecting the error type and setting appropriate errno.
     *
     * @param e       the IOException that occurred
     * @param message additional context about the operation that failed (unused, kept for compatibility)
     * @return RuntimeScalar false value for error indication
     */
    public static RuntimeScalar handleIOException(Exception e, String message) {
        return handleIOException(e, message, 5); // Default to EIO
    }

    /**
     * Handles IOException by detecting the error type and setting appropriate errno.
     *
     * @param e           the IOException that occurred
     * @param message     additional context about the operation that failed (unused, kept for compatibility)
     * @param defaultErrno the errno to use if exception type is not recognized
     * @return RuntimeScalar false value for error indication
     */
    public static RuntimeScalar handleIOException(Exception e, String message, int defaultErrno) {
        int errno = defaultErrno;
        
        // Detect specific exception types
        if (e instanceof java.nio.file.AccessDeniedException) {
            errno = 13; // EACCES
        } else if (e instanceof java.nio.file.NoSuchFileException) {
            errno = 2; // ENOENT
        } else if (e instanceof java.nio.file.FileAlreadyExistsException) {
            errno = 17; // EEXIST
        } else if (e instanceof java.nio.file.DirectoryNotEmptyException) {
            errno = ENOTEMPTY;
        } else if (e instanceof java.io.FileNotFoundException) {
            errno = 2; // ENOENT
        } else if (e != null && e.getMessage() != null) {
            String msg = e.getMessage().toLowerCase();
            if (msg.contains("is a directory")) {
                errno = 21; // EISDIR
            } else if (msg.contains("no such file")) {
                errno = 2; // ENOENT
            } else if (msg.contains("permission denied") || msg.contains("access denied")) {
                errno = 13; // EACCES
            } else if (msg.contains("file exists")) {
                errno = 17; // EEXIST
            } else if (msg.contains("directory not empty")) {
                errno = ENOTEMPTY;
            } else if (msg.contains("invalid argument")) {
                errno = 22; // EINVAL
            }
        }
        
        return handleIOError(errno);
    }

    /**
     * Initializes the standard I/O handles (STDIN, STDOUT, STDERR).
     * This method should be called during runtime initialization.
     */
    public static void initStdHandles() {
        // Initialize STDOUT, STDERR, STDIN in the main package
        getGlobalIO("main::STDOUT").setIO(getStdout());
        getGlobalIO("main::STDERR").setIO(getStderr());
        getGlobalIO("main::STDIN").setIO(getStdin());
        setLastAccessedHandle(null);
        setLastWrittenHandle(getStdout());
        setSelectedHandle(getStdout());
    }

    /**
     * Opens a file for reading with default mode.
     *
     * <p>Special filenames:
     * <ul>
     *   <li>"-" or "<-" - Opens standard input</li>
     *   <li>">-" - Opens standard output</li>
     * </ul>
     *
     * @param fileName the name of the file to open
     * @return RuntimeIO handle for the opened file, or null on error
     */
    public static RuntimeIO open(String fileName) {
        RuntimeIO fh = new RuntimeIO();

        // Trim leading and trailing whitespace
        fileName = fileName.trim();

        // Parse mode from filename for 2-argument open
        String mode = "<";  // default read mode
        String actualFileName = fileName;
        int modeEndIndex = 0;

        // Check for mode at the beginning of the filename
        // Must check longer patterns first to avoid incorrect matches
        if (fileName.startsWith("+<&=")) {
            mode = "+<&=";
            modeEndIndex = 4;
        } else if (fileName.startsWith(">&=")) {
            mode = ">&=";
            modeEndIndex = 3;
        } else if (fileName.startsWith("<&=")) {
            mode = "<&=";
            modeEndIndex = 3;
        } else if (fileName.startsWith("+>>")) {
            mode = "+>>";
            modeEndIndex = 3;
        } else if (fileName.startsWith("+<&")) {
            mode = "+<&";
            modeEndIndex = 3;
        } else if (fileName.startsWith("+>")) {
            mode = "+>";
            modeEndIndex = 2;
        } else if (fileName.startsWith("+<")) {
            mode = "+<";
            modeEndIndex = 2;
        } else if (fileName.startsWith(">>")) {
            mode = ">>";
            modeEndIndex = 2;
        } else if (fileName.startsWith(">&")) {
            mode = ">&";
            modeEndIndex = 2;
        } else if (fileName.startsWith("<&")) {
            mode = "<&";
            modeEndIndex = 2;
        } else if (fileName.startsWith(">")) {
            mode = ">";
            modeEndIndex = 1;
        } else if (fileName.startsWith("<")) {
            mode = "<";
            modeEndIndex = 1;
        }

        // Extract filename, skipping any spaces after the mode
        if (modeEndIndex > 0) {
            actualFileName = fileName.substring(modeEndIndex).trim();
        }

        // Handle special filenames
        if ("-".equals(actualFileName) || actualFileName.isEmpty()) {
            if (mode.contains("&")) {
                // Empty fd in dup mode (e.g., ">&" with no fd number) is an error.
                // This happens when fileno() returns undef and the caller does
                // open(*STDOUT, ">&" . fileno($fh)) — the undef concatenates to ">&".
                handleIOError("Bad file descriptor");
                return null;
            }
            if (mode.equals(">") || mode.equals(">>")) {
                // ">-" or just ">" means stdout
                fh.ioHandle = new CustomOutputStreamHandle(System.out);
            } else {
                // "<-" or just "<" means stdin
                fh.ioHandle = new StandardIO(System.in);
            }
            return fh;
        } else {
            // Regular file open with parsed mode
            return open(actualFileName, mode);
        }
    }

    /**
     * Opens a file with a specific mode and optional I/O layers.
     *
     * <p>The mode string can include I/O layers after a colon:
     * <pre>
     * open("file.txt", ">:utf8")     // Write mode with UTF-8 encoding
     * open("file.txt", "<:crlf")      // Read mode with CRLF conversion
     * open("file.txt", ">>:encoding(UTF-16)") // Append with UTF-16
     * </pre>
     *
     * @param fileName the name of the file to open
     * @param mode     the mode string, optionally including I/O layers
     * @return RuntimeIO handle for the opened file, or null on error
     */
    public static RuntimeIO open(String fileName, String mode) {
        RuntimeIO fh = new RuntimeIO();
        try {
            String ioLayers = "";
            // Check if mode contains IO layers (indicated by ':')
            int colonIndex = mode.indexOf(':');
            if (colonIndex != -1) {
                // Extract I/O layers from mode string
                ioLayers = mode.substring(colonIndex);
                mode = mode.substring(0, colonIndex);

                // If empty fileMode, default to read mode
                if (mode.isEmpty()) {
                    mode = "<";
                }
            }

            // Handle filehandle duplication modes
            if (mode.equals("<&") || mode.equals(">&") || mode.equals("+<&") ||
                    mode.equals("<&=") || mode.equals(">&=") || mode.equals("+<&=")) {
                // For 2-argument open with dup mode, delegate to IOOperator
                // This handles: open(my $fh, ">&1") where ">&1" is parsed as mode=">&", fileName="1"
                return IOOperator.openFileHandleDup(fileName, mode);
            }

            // Handle JAR resource paths (e.g., "jar:PERL5LIB/DBI.pm")
            if (Jar.isJarPath(fileName)) {
                if (!mode.equals("<") && !mode.isEmpty()) {
                    // JAR resources are read-only
                    handleIOError("Cannot write to JAR resource: " + fileName);
                    return null;
                }
                try {
                    InputStream is = Jar.openInputStream(fileName);
                    if (is == null) {
                        getGlobalVariable("main::!").set(2);  // ENOENT
                        return null;
                    }
                    // Use SeekableJarHandle to support seek operations (needed by Module::Metadata)
                    fh.ioHandle = new SeekableJarHandle(is);
                    addHandle(fh.ioHandle);
                    fh.binmode(ioLayers);
                    return fh;
                } catch (IOException e) {
                    handleIOException(e, "open failed");
                    return null;
                }
            }

            Path filePath = resolvePath(fileName, "open");
            if (filePath == null) {
                getGlobalVariable("main::!").set(2);
                return null;
            }
            Set<StandardOpenOption> options = fh.convertMode(mode);

            // Initialize ioHandle with CustomFileChannel
            fh.ioHandle = new CustomFileChannel(filePath, options);

            // Fileno is assigned lazily when fileno() is called

            // Add the handle to the LRU cache
            addHandle(fh.ioHandle);

            // Truncate the file if mode is '>' (already done by TRUNCATE_EXISTING)
            if (">".equals(mode)) {
                fh.ioHandle.truncate(0);
            }
            // Position at end of file for append mode
            if (">>".equals(mode) || "+>>".equals(mode)) {
                RuntimeScalar size = fh.ioHandle.tell();
                fh.ioHandle.seek(size.getLong()); // Move to end for appending
                if (fh.ioHandle instanceof CustomFileChannel cfc) {
                    cfc.setAppendMode(true);
                } else if (fh.ioHandle instanceof LayeredIOHandle layered && layered.getDelegate() instanceof CustomFileChannel cfc) {
                    cfc.setAppendMode(true);
                }
            }

            // Apply any I/O layers
            fh.binmode(ioLayers);

        } catch (IOException e) {
            handleIOException(e, "open failed");
            fh = null;
        }
        return fh;
    }

    /**
     * Opens an in-memory file backed by a scalar reference.
     *
     * <p>This allows treating a string variable as a file:
     * <pre>
     * my $data = "";
     * open(my $fh, ">", \$data);
     * print $fh "Hello";  # $data now contains "Hello"
     * </pre>
     *
     * @param scalarRef a reference to a scalar that will back the file
     * @param mode      the file mode (">", "<", ">>")
     * @return RuntimeIO handle for the scalar-backed file, or null on error
     */
    public static RuntimeIO open(RuntimeScalar scalarRef, String mode) {
        RuntimeIO fh = new RuntimeIO();

        // Check if the argument is a scalar reference
        if (scalarRef.type != RuntimeScalarType.REFERENCE) {
            handleIOError("Not a SCALAR reference");
            return null;
        }

        // Get the referenced scalar
        RuntimeScalar targetScalar = (RuntimeScalar) scalarRef.value;

        // Parse I/O layers from mode string
        String ioLayers = "";
        int colonIndex = mode.indexOf(':');
        if (colonIndex != -1) {
            // Extract I/O layers from mode string
            ioLayers = mode.substring(colonIndex);
            mode = mode.substring(0, colonIndex);

            // If empty fileMode, default to read mode
            if (mode.isEmpty()) {
                mode = "<";
            }
        }

        // Handle different modes
        if (mode.equals(">") || mode.equals(">>")) {
            // Check if the scalar is read-only before attempting write operations
            try {
                if (mode.equals(">")) {
                    // Truncate for write mode - this will throw if read-only
                    // Match Perl behavior: if scalar was undef, keep it undef;
                    // if it was defined, truncate to empty string
                    if (targetScalar.getDefinedBoolean()) {
                        targetScalar.set("");
                    } else {
                        // Still need to check read-only for undef scalars
                        targetScalar.set(new RuntimeScalar());
                    }
                } else if (mode.equals(">>")) {
                    // For append mode, test if scalar is writable by setting it to itself
                    targetScalar.set(targetScalar.toString());
                }
            } catch (RuntimeException e) {
                if (e.getMessage() != null && e.getMessage().contains("Modification of a read-only value attempted")) {
                    // Handle read-only scalar gracefully
                    // Set $! to EACCES (13) - Permission denied
                    GlobalVariable.getGlobalVariable("main::!").set(13);
                    // Issue warning if $^W is set (lexical warning support for runtime is TODO)
                    // $^W is stored as main::W (W is ASCII 87, so 87 - 'A' + 1 = 23)
                    if (GlobalVariable.getGlobalVariable("main::" + Character.toString('W' - 'A' + 1)).getBoolean()) {
                        WarnDie.warn(new RuntimeScalar("Modification of a read-only value attempted"), new RuntimeScalar(""));
                    }
                    return null;
                }
                throw e; // Re-throw if it's a different error
            }
        }
        // For "<" (read) mode, no special handling needed

        // Create ScalarBackedIO
        ScalarBackedIO scalarIO = new ScalarBackedIO(targetScalar);

        if (mode.equals(">>")) {
            // Enable append mode - writes always go to the end
            scalarIO.setAppendMode(true);
        }

        fh.ioHandle = scalarIO;
        addHandle(fh.ioHandle);

        // Apply any I/O layers
        fh.binmode(ioLayers);

        return fh;
    }

    /**
     * Opens a pipe with a specific mode and optional I/O layers.
     *
     * @param runtimeList the mode string, optionally including I/O layers, followed by an external command and optional parameters
     * @return RuntimeIO handle for the opened file, or null on error
     */
    public static RuntimeIO openPipe(RuntimeList runtimeList) {
        RuntimeIO fh = new RuntimeIO();
        try {
            List<String> strings = new ArrayList<>();
            for (RuntimeBase entity : runtimeList.elements) {
                strings.add(entity.toString());
            }
            String arg = strings.getFirst();
            String mode = null;
            String ioLayers = "";
            boolean noShell = false;  // Flag to bypass shell interpretation

            if (strings.size() > 1) {
                if (arg.startsWith("|-")) {
                    mode = ">";
                    arg = arg.substring(2);
                } else if (arg.startsWith("-|")) {
                    mode = "<";
                    arg = arg.substring(2);
                }

                // Check if mode contains IO layers (indicated by ':')
                int colonIndex = arg.indexOf(':');
                if (colonIndex == 0) {
                    // Extract I/O layers from mode string
                    ioLayers = arg;
                    arg = "";
                }
            } else if (strings.size() == 1) {
                if (arg.startsWith("|")) {
                    mode = ">";
                    arg = arg.substring(1);
                } else if (arg.endsWith("|")) {
                    mode = "<";
                    arg = arg.substring(0, arg.length() - 1);
                }
            }

            // Check for :noshell layer - bypasses shell for single-arg pipe open
            // Usage: open($fh, "-|:noshell", $cmd) to execute $cmd literally without shell
            if (ioLayers.contains(":noshell")) {
                noShell = true;
                ioLayers = ioLayers.replace(":noshell", "");
            }

            if (arg.isEmpty()) {
                strings.removeFirst();
            } else {
                strings.set(0, arg);
            }

            // System.out.println("open pipe: mode=" + mode + " cmd=" + strings + " layers=" + ioLayers + " noShell=" + noShell);

            if (">".equals(mode)) {
                // When noShell is true, always use list constructor to bypass shell
                if (strings.size() == 1 && !noShell) {
                    fh.ioHandle = new PipeOutputChannel(strings.getFirst());
                } else {
                    fh.ioHandle = new PipeOutputChannel(strings);
                }
            } else if ("<".equals(mode)) {
                // When noShell is true, always use list constructor to bypass shell
                if (strings.size() == 1 && !noShell) {
                    fh.ioHandle = new PipeInputChannel(strings.getFirst());
                } else {
                    fh.ioHandle = new PipeInputChannel(strings);
                }
            } else {
                handleIOError("open failed: invalid mode for pipe");
                return null;
            }

            // Fileno is assigned lazily when fileno() is called

            // Add the handle to the LRU cache
            addHandle(fh.ioHandle);

            // Apply any I/O layers (excluding the already-processed :noshell)
            if (!ioLayers.isEmpty()) {
                fh.binmode(ioLayers);
            }
        } catch (IOException e) {
            handleIOException(e, "open failed");
            fh = null;
        }
        return fh;
    }

    /**
     * Converts a filename to a Path object relative to the current directory.
     *
     * @param fileName the filename to convert
     * @return Path object for the file
     */
    public static Path resolvePath(String fileName) {
        return resolvePath(fileName, "path");
    }

    public static Path resolvePath(String fileName, String opName) {
        String sanitized = sanitizePathname(opName, fileName);
        if (sanitized == null) {
            return null;
        }

        Path path = Paths.get(sanitized);

        // If the path is already absolute, return it as-is
        if (path.isAbsolute()) {
            return path.toAbsolutePath();
        }

        // For relative paths, resolve against current directory
        return Paths.get(System.getProperty("user.dir")).resolve(sanitized).toAbsolutePath();
    }

    /**
     * Flushes stdout and stderr if they have pending output.
     * This is typically called before operations that might block,
     * ensuring prompts are displayed.
     */
    public static void flushFileHandles() {
        // Flush stdout and stderr before sleep, in case we are displaying a prompt
        RuntimeIO out = getStdout();
        RuntimeIO err = getStderr();
        if (out.needFlush) {
            out.flush();
        }
        if (err.needFlush) {
            err.flush();
        }
    }

    /**
     * Adds a handle to the LRU cache of open handles.
     *
     * @param handle the IOHandle to cache
     */
    public static void addHandle(IOHandle handle) {
        synchronized (openHandles) {
            openHandles.put(handle, Boolean.TRUE);
        }
    }

    /**
     * Removes a handle from the LRU cache.
     *
     * @param handle the IOHandle to remove
     */
    public static void removeHandle(IOHandle handle) {
        synchronized (openHandles) {
            openHandles.remove(handle);
        }
    }

    /**
     * Flushes all open file handles.
     * This ensures all buffered data is written without closing files.
     */
    public static void flushAllHandles() {
        synchronized (openHandles) {
            for (IOHandle handle : openHandles.keySet()) {
                handle.flush();
            }
        }
        flushFileHandles();
    }

    /**
     * Closes all open file handles.
     * This is typically called during program shutdown.
     */
    public static void closeAllHandles() {
        flushAllHandles();
        synchronized (openHandles) {
            for (IOHandle handle : openHandles.keySet()) {
                try {
                    handle.close();
                    handle = new ClosedIOHandle();
                } catch (Exception e) {
                    // Handle exception if needed
                }
            }
            openHandles.clear(); // Clear the cache after closing all handles
        }
    }

    /**
     * Extracts a RuntimeIO from various Perl scalar types.
     *
     * <p>This method handles several common filehandle representations:
     * <ul>
     *   <li>Direct I/O handles (RuntimeIO stored directly in the scalar)</li>
     *   <li>Glob references (\*STDOUT) - dereferences to get the glob's IO slot</li>
     *   <li>Globs (*STDOUT) - accesses the glob's IO slot directly</li>
     *   <li>Symbolic names ("STDOUT") - looks up the global handle by name</li>
     * </ul>
     *
     * <p><b>IMPORTANT for detached glob copies:</b> When a glob is captured via
     * {@code do { local *FH; *FH }}, the returned glob is a "detached copy" created
     * by {@link RuntimeGlob#createDetachedCopy()}. This copy has its own IO slot
     * that is independent of the global *FH after the local scope ends. This method
     * must extract the IO from the detached copy's own IO slot, NOT from the global
     * handle looked up by name. The fallback lookup by globName (lines 820-828) should
     * only be used when the glob's own IO slot is genuinely null/empty, not just
     * because it contains an empty RuntimeScalar.
     *
     * @param runtimeScalar the scalar containing or referencing an I/O handle
     * @return the extracted RuntimeIO, or null if no IO handle found
     */
    public static RuntimeIO getRuntimeIO(RuntimeScalar runtimeScalar) {
        RuntimeIO fh = null;
        boolean ioDebug = System.getenv("JPERL_IO_DEBUG") != null;
        
        if (ioDebug) {
            System.err.println("[JPERL_IO_DEBUG] getRuntimeIO ENTRY: type=" + runtimeScalar.type +
                " valueClass=" + (runtimeScalar.value != null ? runtimeScalar.value.getClass().getSimpleName() : "null") +
                " valueId=" + (runtimeScalar.value != null ? System.identityHashCode(runtimeScalar.value) : 0));
            System.err.flush();
        }
        
        // Handle: my $fh2 = \*STDOUT;
        // Handle: my $fh = *STDOUT;

        // If this is a tied scalar, fetch the underlying value first
        if (runtimeScalar.type == RuntimeScalarType.TIED_SCALAR) {
            // Check if this is a tied filehandle (TieHandle) or a regular tied scalar
            if (runtimeScalar.value instanceof TieHandle tieHandle) {
                // For tied filehandles, return the TieHandle directly
                return tieHandle;
            } else if (runtimeScalar.value instanceof TiedVariableBase) {
                // For regular tied scalars, call tiedFetch()
                runtimeScalar = runtimeScalar.tiedFetch();
            } else {
                // Unknown type, return null
                return null;
            }
        }

        if (runtimeScalar.isString()) {
            String name = runtimeScalar.toString();
            String packageName = "main";  // XXX TODO: get the current package name
            if (name.equals("STDOUT") || name.equals("STDERR") || name.equals("STDIN")) {
                packageName = "main";
            }

            // Normalize the name to include the package qualifier
            // This converts "HANDLE" to "Package::HANDLE" format
            name = NameNormalizer.normalizeVariableName(name, packageName);
            runtimeScalar = GlobalVariable.getGlobalIO(name);
        }

        if (runtimeScalar.value instanceof RuntimeGlob runtimeGlob) {
            RuntimeScalar ioScalar = runtimeGlob.getIO();
            if (ioDebug) {
                System.err.println("[JPERL_IO_DEBUG] getRuntimeIO: glob=" + runtimeGlob.globName +
                    " globId=" + System.identityHashCode(runtimeGlob) +
                    " ioScalar=" + (ioScalar != null ? ioScalar.type + "/" + System.identityHashCode(ioScalar) : "null") +
                    " ioValue=" + (ioScalar != null ? (ioScalar.value != null ? ioScalar.value.getClass().getSimpleName() + "/" + System.identityHashCode(ioScalar.value) : "null") : "N/A"));
                System.err.flush();
            }
            if (ioScalar != null) {
                fh = ioScalar.getRuntimeIO();
            }
            // If the glob's IO part is null, try to look up the global handle by name.
            // IMPORTANT: This fallback should only be used when the detached copy's own
            // IO slot genuinely has no IO handle. For the `do { local *FH; *FH }` pattern,
            // the detached copy's IO slot IS the correct place to look.
            if (fh == null && runtimeGlob.globName != null) {
                if (ioDebug) {
                    System.err.println("[JPERL_IO_DEBUG] getRuntimeIO: fallback lookup for " + runtimeGlob.globName);
                    System.err.flush();
                }
                RuntimeGlob globalGlob = GlobalVariable.getExistingGlobalIO(runtimeGlob.globName);
                if (globalGlob != null) {
                    RuntimeScalar globalIoScalar = globalGlob.getIO();
                    if (globalIoScalar != null) {
                        fh = globalIoScalar.getRuntimeIO();
                    }
                }
            }
        } else if (runtimeScalar.value instanceof RuntimeIO runtimeIO) {
            // Direct I/O handle
            if (ioDebug) {
                System.err.println("[JPERL_IO_DEBUG] getRuntimeIO: found direct RuntimeIO id=" + System.identityHashCode(runtimeIO));
                System.err.flush();
            }
            fh = runtimeIO;
        }

        if (ioDebug) {
            System.err.println("[JPERL_IO_DEBUG] getRuntimeIO EXIT: fh=" + (fh != null ? System.identityHashCode(fh) : "null"));
            System.err.flush();
        }

        if (fh == null) {
            // Check if object is eligible for overloading `*{}`
            int blessId = RuntimeScalarType.blessedId(runtimeScalar);
            if (blessId < 0) {
                // Prepare overload context and check if object is eligible for overloading
                OverloadContext ctx = OverloadContext.prepare(blessId);
                if (ctx != null) {
                    // Try overload method
                    RuntimeScalar result = ctx.tryOverload("(*{}", new RuntimeArray(runtimeScalar));
                    // If the subroutine returns the object itself then it will not be called again
                    if (result != null && result.value.hashCode() != runtimeScalar.value.hashCode()) {
                        return getRuntimeIO(result);
                    }
                }
            }
        }

        return fh;
    }

    /**
     * Helper method to convert a Path to a File, resolving relative paths first.
     */
    public static File resolveFile(String pathString) {
        Path path = resolvePath(pathString, "path");
        return path != null ? path.toFile() : null;
    }

    public static File resolveFile(String pathString, String opName) {
        Path path = resolvePath(pathString, opName);
        return path != null ? path.toFile() : null;
    }

    public static String sanitizePathname(String opName, String fileName) {
        if (fileName == null) {
            return null;
        }

        String s = fileName;
        while (!s.isEmpty() && s.charAt(s.length() - 1) == '\0') {
            s = s.substring(0, s.length() - 1);
        }
        if (s.indexOf('\0') >= 0) {
            // Check both compile-time and runtime warning suppression
            if (Warnings.warningManager.isWarningEnabled("syscalls") 
                    && !WarningFlags.isWarningSuppressedAtRuntime("syscalls")) {
                String display = fileName.replace("\0", "\\0");
                WarnDie.warn(
                        new RuntimeScalar("Invalid \\\\0 character in pathname for " + opName + ": " + display),
                        new RuntimeScalar("")
                );
            }
            return null;
        }
        return s;
    }

    public static String sanitizeGlobPattern(String pattern) {
        if (pattern == null) {
            return null;
        }

        String s = pattern;
        while (!s.isEmpty() && s.charAt(s.length() - 1) == '\0') {
            s = s.substring(0, s.length() - 1);
        }
        if (s.indexOf('\0') >= 0) {
            // Check both compile-time and runtime warning suppression
            if (Warnings.warningManager.isWarningEnabled("syscalls")
                    && !WarningFlags.isWarningSuppressedAtRuntime("syscalls")) {
                String display = pattern.replace("\0", "\\0");
                WarnDie.warn(
                        new RuntimeScalar("Invalid \\\\0 character in pattern for glob: " + display),
                        new RuntimeScalar("")
                );
            }
            return null;
        }
        return s;
    }

    /**
     * Duplicates a filehandle for <& and >& operations.
     *
     * @param sourceHandle the source filehandle to duplicate
     * @param mode         the duplication mode ("<&" for read, ">&" for write)
     * @return RuntimeIO handle that shares the same underlying resource, or null on error
     */
    public static RuntimeIO duplicateHandle(RuntimeIO sourceHandle, String mode) {
        if (sourceHandle == null || sourceHandle.ioHandle == null) {
            handleIOError("Cannot duplicate invalid filehandle");
            return null;
        }

        // For now, return the original handle directly to avoid sharing issues
        // This is a simplified implementation - in a full implementation,
        // we would create a proper duplicate that shares the underlying file descriptor
        // but has separate buffering and close semantics
        return sourceHandle;
    }

    public long getPid() {
        Process p = null;
        if (ioHandle instanceof PipeInputChannel pic) {
            p = pic.getProcess();
        } else if (ioHandle instanceof PipeOutputChannel poc) {
            p = poc.getProcess();
        }
        if (p != null) {
            registerChildProcess(p);
            return p.pid();
        }
        return -1;
    }

    /**
     * Sets or changes the I/O layers for this handle.
     *
     * <p>If no layers are specified, checks the ${^OPEN} variable for defaults.
     * On Windows, defaults to :crlf, otherwise :raw.
     * Layers can be stacked, e.g., ":utf8:crlf".
     *
     * @param ioLayer the layer specification (e.g., ":utf8", ":crlf", ":raw")
     */
    public void binmode(String ioLayer) {
        if (ioLayer.isEmpty()) {
            // No layers specified, check ${^OPEN} for default layers
            ioLayer = getGlobalVariable(GlobalContext.OPEN).toString();
        }
        if (ioLayer.isEmpty() || ioLayer.equals(":")) {
            // If only ":" is specified, use OS default
            if (SystemUtils.osIsWindows()) {
                ioLayer = ":crlf";
            } else {
                ioLayer = ":raw";
            }
        }

        // Unwrap all layers to get to the base handle
        IOHandle baseHandle = ioHandle;
        while (baseHandle instanceof LayeredIOHandle) {
            baseHandle = ((LayeredIOHandle) baseHandle).getDelegate();
        }

        // Special handling for :raw mode - just use the base handle directly
        if (ioLayer.equals(":raw") || ioLayer.equals(":bytes") || ioLayer.equals(":unix")) {
            ioHandle = baseHandle;
            return;
        }

        // For other modes, wrap the IOHandle and set the ioLayer
        LayeredIOHandle wrappedHandle = new LayeredIOHandle(baseHandle);
        wrappedHandle.binmode(ioLayer);
        ioHandle = wrappedHandle;
    }

    /**
     * Converts a Perl-style mode string to Java NIO options.
     *
     * @param mode the Perl mode string
     * @return set of StandardOpenOption for the mode
     * @throws PerlCompilerException if the mode is not supported
     */
    private Set<StandardOpenOption> convertMode(String mode) {
        // Handle duplication modes - these are handled separately in open()
        if (mode.equals("<&") || mode.equals(">&") || mode.equals("+<&") ||
                mode.equals("<&=") || mode.equals(">&=") || mode.equals("+<&=")) {
            // Return read-write mode for dup operations
            return new HashSet<>(MODE_OPTIONS.get("+<"));
        }

        Set<StandardOpenOption> options = MODE_OPTIONS.get(mode);
        if (options == null) {
            throw new PerlCompilerException("Unknown open() mode '" + mode + "'");
        }
        return new HashSet<>(options);
    }

    /**
     * Returns a string representation of this I/O handle.
     * Format: globName if known (e.g., "main::STDOUT"), otherwise GLOB(0xHASHCODE)
     *
     * @return string representation
     */
    public String toString() {
        if (globName != null) {
            return globName;
        }
        return "GLOB(0x" + this.hashCode() + ")";
    }

    /**
     * Returns a string representation of an I/O reference.
     * Format: IO(0xHASHCODE)
     *
     * @return string representation of the reference
     */
    public String toStringRef() {
        // XXX TODO IO reference can be blessed
        // return (blessId == 0
        //         ? ref
        //         : NameNormalizer.getBlessStr(blessId) + "=" + ref);

        return "IO(0x" + this.hashCode() + ")";
    }

    /**
     * Gets the numeric value of this I/O reference.
     *
     * @return the hash code as an integer
     */
    public int getIntRef() {
        return this.hashCode();
    }

    /**
     * Gets the numeric value of this I/O reference as a double.
     *
     * @return the hash code as a double
     */
    public double getDoubleRef() {
        return this.hashCode();
    }

    /**
     * Gets the boolean value of this I/O reference.
     * I/O handles are always true in boolean context.
     *
     * @return true
     */
    public boolean getBooleanRef() {
        return true;
    }

    /**
     * Closes this I/O handle.
     * Removes from cache, flushes buffers, and releases resources.
     * <p>
     * For borrowed handles (parsimonious dup), only detaches from the ioHandle
     * without flushing or closing it — the owning handle will handle cleanup.
     *
     * @return RuntimeScalar indicating success/failure
     */
    public RuntimeScalar close() {
        removeHandle(ioHandle);
        unregisterFileno();
        ioHandle.flush();
        RuntimeScalar ret = ioHandle.close();
        ioHandle = new ClosedIOHandle();
        // Reset line number to 0 on close, matching Perl 5 behavior.
        // This ensures $. becomes 0 and error messages don't include
        // stale filehandle context after close.
        currentLineNumber = 0;
        return ret;
    }

    /**
     * Checks if this handle has reached end-of-file.
     * Updates the last accessed handle.
     *
     * @return RuntimeScalar with true if at EOF
     */
    public RuntimeScalar eof() {
        setLastAccessedHandle(this);
        return ioHandle.eof();
    }

    /**
     * Gets the current position in the file.
     * Updates the last accessed handle.
     *
     * @return RuntimeScalar with the current position
     */
    public RuntimeScalar tell() {
        setLastAccessedHandle(this);
        return ioHandle.tell();
    }

    /**
     * Seeks to a new position in the file.
     * Updates the last accessed handle.
     *
     * @param pos the position to seek to
     * @return RuntimeScalar indicating success/failure
     */
    public RuntimeScalar seek(long pos) {
        setLastAccessedHandle(this);
        return ioHandle.seek(pos);
    }

    /**
     * Flushes any buffered output.
     * Clears the needFlush flag.
     *
     * @return RuntimeScalar indicating success/failure
     */
    public RuntimeScalar flush() {
        needFlush = false;
        return ioHandle.flush();
    }

    public boolean isAutoFlush() {
        return autoFlush;
    }

    public void setAutoFlush(boolean autoFlush) {
        this.autoFlush = autoFlush;
        if (autoFlush) {
            flush();
        }
    }

    /**
     * Writes data to this handle.
     * Sets the needFlush flag.
     *
     * @param data the string data to write
     * @return RuntimeScalar indicating success/failure or bytes written
     */
    public RuntimeScalar write(String data) {
        needFlush = true;
        // Only flush lastAccessedHandle if it's a different handle AND doesn't share the same ioHandle
        // (duplicated handles share the same ioHandle, so flushing would be redundant and could cause deadlocks)
        RuntimeIO lastWritten = getLastWrittenHandle();
        if (lastWritten != null &&
                lastWritten != this &&
                lastWritten.needFlush &&
                lastWritten.ioHandle != this.ioHandle) {
            // Synchronize terminal output for stdout and stderr
            lastWritten.flush();
        }
        setLastWrittenHandle(this);

        // When no encoding layer is active, check for wide characters (> 0xFF).
        // Perl 5 warns and outputs UTF-8 encoding of the entire string in this case.
        if (isByteMode()) {
            boolean hasWide = false;
            for (int i = 0; i < data.length(); i++) {
                if (data.charAt(i) > 0xFF) {
                    hasWide = true;
                    break;
                }
            }
            if (hasWide) {
                WarnDie.warnWithCategory(
                        new RuntimeScalar("Wide character in print"),
                        new RuntimeScalar(""),
                        "utf8");
                // Encode as UTF-8, where each byte becomes a char (matching Perl 5 behavior)
                byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
                StringBuilder sb = new StringBuilder(bytes.length);
                for (byte b : bytes) {
                    sb.append((char) (b & 0xFF));
                }
                data = sb.toString();
            }
        }

        RuntimeScalar result = ioHandle.write(data);
        if (System.getenv("JPERL_IO_DEBUG") != null) {
            if (("main::STDOUT".equals(globName) || "main::STDERR".equals(globName)) &&
                    (ioHandle instanceof ClosedIOHandle || !result.getDefinedBoolean())) {
                System.err.println("[JPERL_IO_DEBUG] write failed: glob=" + globName +
                        " ioHandle=" + (ioHandle == null ? "null" : ioHandle.getClass().getName()) +
                        " defined=" + result.getDefinedBoolean() +
                        " errno=" + getGlobalVariable("main::!"));
                System.err.flush();
            }
        }
        if (autoFlush || data.endsWith("\n")) {
            ioHandle.flush();
        }
        return result;
    }

    /**
     * Gets the file descriptor number for this handle.
     * <p>
     * Resolution order:
     * <ol>
     *   <li>Check the fileno registry (for handles that already have an assigned fd)</li>
     *   <li>Ask the ioHandle for its native fd (StandardIO returns 0/1/2)</li>
     *   <li>For file channels and pipes, lazily assign a registry fd on first call —
     *       this avoids consuming fd numbers for handles whose fileno is never queried</li>
     * </ol>
     * <p>
     * The lazy assignment strategy is important for modules like Capture::Tiny
     * that open many temporary file handles but only need fileno() on a few.
     *
     * @return RuntimeScalar with the file descriptor number, or undef if not available
     */
    public RuntimeScalar fileno() {
        // Check registry first — already-assigned handles
        int fd = getAssignedFileno();
        if (fd >= 0) {
            return new RuntimeScalar(fd);
        }
        if (ioHandle == null) {
            return RuntimeScalarCache.scalarUndef;
        }
        // Try the native fileno first (StandardIO returns 0/1/2)
        RuntimeScalar nativeFd = ioHandle.fileno();
        if (nativeFd.getDefinedBoolean()) {
            return nativeFd;
        }
        // For file channels, pipes, and process handles, lazily assign a registry fileno
        if (ioHandle instanceof CustomFileChannel
                || ioHandle instanceof PipeInputChannel
                || ioHandle instanceof PipeOutputChannel
                || ioHandle instanceof InternalPipeHandle
                || ioHandle instanceof LayeredIOHandle
                || ioHandle instanceof SocketIO
                || ioHandle instanceof ProcessInputHandle
                || ioHandle instanceof ProcessOutputHandle) {
            fd = assignFileno();
            return new RuntimeScalar(fd);
        }
        return RuntimeScalarCache.scalarUndef;
    }

    /**
     * Binds this socket to a local address and port.
     * Only valid for socket handles.
     *
     * @param address the local address to bind to
     * @param port    the local port to bind to
     * @return RuntimeScalar indicating success/failure
     */
    public RuntimeScalar bind(String address, int port) {
        return ioHandle.bind(address, port);
    }

    /**
     * Connects this socket to a remote address and port.
     * Only valid for socket handles.
     *
     * @param address the remote address to connect to
     * @param port    the remote port to connect to
     * @return RuntimeScalar indicating success/failure
     */
    public RuntimeScalar connect(String address, int port) {
        return ioHandle.connect(address, port);
    }

    /**
     * Configures this socket to listen for incoming connections.
     * Only valid for socket handles.
     *
     * @param backlog the maximum number of pending connections
     * @return RuntimeScalar indicating success/failure
     */
    public RuntimeScalar listen(int backlog) {
        return ioHandle.listen(backlog);
    }

    /**
     * Accepts an incoming connection on this socket.
     * Only valid for socket handles in listening mode.
     *
     * @return RuntimeScalar containing the new socket handle, or false on error
     */
    public RuntimeScalar accept() {
        return ioHandle.accept();
    }

    /**
     * Truncates the file to the specified length.
     * Only valid for file handles.
     *
     * @param length the new length of the file
     * @return RuntimeScalar indicating success/failure
     */
    public RuntimeScalar truncate(long length) {
        return ioHandle.truncate(length);
    }

    /**
     * Gets the local socket address (getsockname equivalent).
     * Only valid for socket handles.
     *
     * @return RuntimeScalar containing packed sockaddr_in structure, or undef if not a socket
     */
    public RuntimeScalar getsockname() {
        if (ioHandle instanceof SocketIO) {
            return ((SocketIO) ioHandle).getsockname();
        }
        return scalarUndef;
    }

    /**
     * Gets the remote socket address (getpeername equivalent).
     * Only valid for socket handles.
     *
     * @return RuntimeScalar containing packed sockaddr_in structure, or undef if not a socket
     */
    public RuntimeScalar getpeername() {
        if (ioHandle instanceof SocketIO) {
            return ((SocketIO) ioHandle).getpeername();
        }
        return scalarUndef;
    }
}
