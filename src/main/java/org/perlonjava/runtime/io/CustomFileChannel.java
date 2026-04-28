package org.perlonjava.runtime.io;

import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarCache;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

import static org.perlonjava.runtime.runtimetypes.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.runtimetypes.RuntimeIO.handleIOException;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.getScalarInt;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarTrue;

/**
 * A custom file channel implementation that provides Perl-compatible I/O operations.
 *
 * <p>This class wraps Java's {@link FileChannel} to provide an implementation of
 * {@link IOHandle} that supports file-based I/O operations. It handles character
 * encoding/decoding, EOF detection, and provides Perl-style return values for
 * all operations.
 *
 * <p>Key features:
 * <ul>
 *   <li>Supports both file path and file descriptor based construction</li>
 *   <li>Handles multi-byte character sequences correctly across read boundaries</li>
 *   <li>Tracks EOF state for Perl-compatible EOF detection</li>
 *   <li>Provides atomic position-based operations (tell, seek)</li>
 *   <li>Supports file truncation</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>
 * // Open a file for reading
 * Set&lt;StandardOpenOption&gt; options = Set.of(StandardOpenOption.READ);
 * CustomFileChannel channel = new CustomFileChannel(Paths.get("file.txt"), options);
 *
 * // Read data
 * RuntimeScalar data = channel.read(1024, StandardCharsets.UTF_8);
 *
 * // Check EOF
 * if (channel.eof().getBoolean()) {
 *     // End of file reached
 * }
 * </pre>
 *
 * @see IOHandle
 * @see FileChannel
 */
public class CustomFileChannel implements IOHandle {

    // Perl flock constants
    private static final int LOCK_SH = 1;  // Shared lock
    private static final int LOCK_EX = 2;  // Exclusive lock
    private static final int LOCK_NB = 4;  // Non-blocking
    private static final int LOCK_UN = 8;  // Unlock

    /**
     * Per-JVM registry of active shared flock() locks, keyed by canonical file path.
     * Java NIO's FileChannel.lock() treats all FileChannels within a single JVM as
     * the same process and throws OverlappingFileLockException if the same region is
     * locked twice, even for shared locks. POSIX flock() (which Perl exposes) allows
     * multiple shared locks on the same file from the same process.
     * <p>
     * To match POSIX semantics, we track shared locks per canonical path in this
     * map. The first shared-lock request acquires a real FileLock on the underlying
     * channel; subsequent shared-lock requests on the same file increment the
     * refCount without acquiring a new NIO lock. The real lock is released when the
     * last holder calls LOCK_UN or closes its handle.
     * <p>
     * This fixes DBICTest's global lock acquisition (t/lib/DBICTest.pm import), which
     * does sysopen() + flock(LOCK_SH) multiple times across nested module loads.
     * Without this, the second flock(LOCK_SH) call deadlocks inside await_flock().
     */
    private static final java.util.Map<String, SharedLockState> sharedLockRegistry =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Cleaner used to drop any flock() we still hold when this channel is GC'd
     * without an explicit Perl-level {@code close($fh)}. Path::Tiny's
     * {@code slurp}/{@code append} idiom returns from a sub while the locked
     * filehandle is still in scope, then immediately reopens the same path and
     * tries to take an EXCLUSIVE lock — which previously failed with
     * {@code Resource deadlock avoided} because the SHARED lock entry from the
     * abandoned channel was still in {@link #sharedLockRegistry}. The Cleaner
     * action releases registry/native locks deterministically once the JVM
     * notices the {@link CustomFileChannel} is unreachable.
     */
    private static final Cleaner LOCK_CLEANER = Cleaner.create();

    /**
     * State for a JVM-wide shared flock() on a file path. Contains the owning
     * FileLock (from the first acquirer) and a count of how many channels in this
     * JVM currently hold the shared lock.
     */
    private static final class SharedLockState {
        FileLock nioLock;
        int refCount;
    }

    /**
     * Canonical key for this channel's file, used to look up entries in
     * {@link #sharedLockRegistry}. Null when the channel was created from a file
     * descriptor (e.g., dup'd handles) and we have no path. Lookup falls back to
     * the plain NIO lock path in that case.
     */
    private final String lockKey;

    /**
     * True when this channel currently "holds" a shared lock via the JVM-wide
     * registry (rather than via its own NIO {@link #currentLock}). On release,
     * we decrement the registry's refCount instead of calling nioLock.release()
     * directly.
     */
    private boolean holdsSharedLockViaRegistry;

    /**
     * Mutable state shared with this channel's Cleaner action. Lives in a
     * separate object so the Cleaner can run it without retaining a reference
     * to {@code this} (a Cleaner action that captured the outer instance would
     * never trigger). Updated whenever this channel acquires or releases a
     * lock; the Cleaner runs at most once, when the channel is GC'd.
     */
    private final CleanupState cleanupState = new CleanupState();

    private final Cleaner.Cleanable cleanable = LOCK_CLEANER.register(this, cleanupState);

    /**
     * Cleaner action: runs when the {@link CustomFileChannel} becomes
     * unreachable without an explicit Perl-level {@code close($fh)}. Releases
     * any flock() entry the channel still owns so Path::Tiny's
     * {@code slurp}-then-{@code append({truncate=>1})} pattern doesn't get
     * stuck on a stale SHARED lock from the abandoned read handle.
     */
    private static final class CleanupState implements Runnable {
        volatile String lockKey;
        volatile boolean viaRegistry;
        volatile FileLock nioLock;

        @Override
        public void run() {
            try {
                if (viaRegistry && lockKey != null) {
                    synchronized (sharedLockRegistry) {
                        SharedLockState state = sharedLockRegistry.get(lockKey);
                        if (state != null) {
                            state.refCount--;
                            if (state.refCount <= 0) {
                                if (state.nioLock != null && state.nioLock.isValid()) {
                                    state.nioLock.release();
                                }
                                sharedLockRegistry.remove(lockKey);
                            }
                        }
                    }
                } else if (nioLock != null && nioLock.isValid()) {
                    nioLock.release();
                }
            } catch (IOException ignored) {
                // Best-effort cleanup; nothing useful to do on failure.
            }
            viaRegistry = false;
            nioLock = null;
        }
    }

    /**
     * The underlying Java NIO FileChannel for actual I/O operations
     */
    private final FileChannel fileChannel;

    private final Path filePath;

    private boolean isEOF;

    // When true, writes should always occur at end-of-file (Perl's append semantics).
    private boolean appendMode;

    /**
     * Current file lock, if any
     */
    private FileLock currentLock;

    /**
     * Helper for handling multi-byte character decoding across read boundaries
     */
    private CharsetDecoderHelper decoderHelper;

    /**
     * Creates a new CustomFileChannel for the specified file path.
     *
     * @param path    the path to the file to open
     * @param options the options specifying how the file is opened (READ, WRITE, etc.)
     * @throws IOException if an I/O error occurs opening the file
     */
    public CustomFileChannel(Path path, Set<StandardOpenOption> options) throws IOException {
        this.filePath = path;
        this.fileChannel = FileChannel.open(path, options);
        this.isEOF = false;
        this.appendMode = false;
        // Canonical path for the shared-lock registry. Fall back to absolute path
        // if canonicalization fails (e.g., the file was deleted after open).
        String key;
        try {
            key = path.toFile().getCanonicalPath();
        } catch (IOException e) {
            key = path.toAbsolutePath().toString();
        }
        this.lockKey = key;
    }

    /**
     * Creates a new CustomFileChannel from an existing file descriptor.
     *
     * <p>This constructor is useful for wrapping standard I/O streams (stdin, stdout, stderr)
     * or file descriptors obtained from native code.
     *
     * @param fd      the file descriptor to wrap
     * @param options the options specifying the mode (must contain either READ or WRITE)
     * @throws IOException              if an I/O error occurs
     * @throws IllegalArgumentException if options don't contain READ or WRITE
     */
    public CustomFileChannel(FileDescriptor fd, Set<StandardOpenOption> options) throws IOException {
        this.filePath = null;
        this.lockKey = null;
        if (options.contains(StandardOpenOption.READ)) {
            this.fileChannel = new FileInputStream(fd).getChannel();
        } else if (options.contains(StandardOpenOption.WRITE)) {
            this.fileChannel = new FileOutputStream(fd).getChannel();
        } else {
            throw new IllegalArgumentException("Invalid options for FileDescriptor");
        }
        this.isEOF = false;
        this.appendMode = false;
    }

    public Path getFilePath() {
        return filePath;
    }

    public void setAppendMode(boolean appendMode) {
        this.appendMode = appendMode;
    }

    /**
     * Reads data from the file with proper character encoding support.
     *
     * <p>This method handles multi-byte character sequences correctly, buffering
     * incomplete sequences until enough data is available to decode them properly.
     * This is crucial for UTF-8 and other variable-length encodings.
     *
     * @param maxBytes the maximum number of bytes to read
     * @param charset  the character encoding to use for decoding
     * @return RuntimeScalar containing the decoded string data
     */
    @Override
    public RuntimeScalar doRead(int maxBytes, Charset charset) {
        try {
            byte[] buffer = new byte[maxBytes];
            ByteBuffer byteBuffer = ByteBuffer.wrap(buffer);
            int bytesRead = fileChannel.read(byteBuffer);

            if (bytesRead == -1) {
                isEOF = true;
                return new RuntimeScalar("");
            }

            // Check if we've reached EOF (read less than requested)
            if (bytesRead < maxBytes) {
                isEOF = true;
            }

            // Also treat "at end of file" as EOF for Perl semantics (eof true after last successful read)
            try {
                if (fileChannel.position() >= fileChannel.size()) {
                    isEOF = true;
                }
            } catch (IOException e) {
                // ignore
            }

            byte[] result = new byte[bytesRead];
            System.arraycopy(buffer, 0, result, 0, bytesRead);
            return new RuntimeScalar(result);
        } catch (IOException e) {
            return handleIOException(e, "Read operation failed");
        }
    }

    /**
     * Writes a string to the file.
     *
     * <p>The string is converted to bytes using ISO-8859-1 encoding, which
     * preserves byte values for binary data. This allows the method to handle
     * both text and binary data correctly.
     *
     * @param string the string data to write
     * @return RuntimeScalar containing the number of bytes written
     */
    @Override
    public RuntimeScalar write(String string) {
        try {
            if (appendMode) {
                fileChannel.position(fileChannel.size());
            }
            // Check if string contains wide characters (codepoint > 255)
            // Perl 5 auto-upgrades to UTF-8 for wide chars on binary handles
            boolean hasWideChars = false;
            for (int i = 0; i < string.length(); i++) {
                if (string.charAt(i) > 255) {
                    hasWideChars = true;
                    break;
                }
            }
            byte[] data;
            if (hasWideChars) {
                // Encode as UTF-8, matching Perl 5 "Wide character in print" behavior
                data = string.getBytes(StandardCharsets.UTF_8);
            } else {
                data = new byte[string.length()];
                for (int i = 0; i < string.length(); i++) {
                    data[i] = (byte) string.charAt(i);
                }
            }
            ByteBuffer byteBuffer = ByteBuffer.wrap(data);
            fileChannel.write(byteBuffer);
            return scalarTrue;
        } catch (IOException e) {
            return handleIOException(e, "write failed");
        }
    }

    /**
     * Closes the file channel and releases any system resources.
     *
     * <p>Note: We intentionally do NOT call force() here. The OS will flush
     * buffers on close, and force() (fsync) is extremely slow. If explicit
     * sync-to-disk is needed, use {@link #sync()} before closing.
     *
     * @return RuntimeScalar with true value on success
     */
    @Override
    public RuntimeScalar close() {
        try {
            // Release any flock() we're still holding. For shared locks we may
            // be the last holder in the JVM — release via the registry so the
            // underlying NIO lock is freed exactly once.
            releaseCurrentLock();
            fileChannel.close();
            return scalarTrue;
        } catch (IOException e) {
            return handleIOException(e, "close failed");
        }
    }

    /**
     * Checks if end-of-file has been reached.
     *
     * <p>The EOF flag is set when a read operation returns -1 (no more data).
     *
     * @return RuntimeScalar with true if EOF reached, false otherwise
     */
    @Override
    public RuntimeScalar eof() {
        return new RuntimeScalar(isEOF);
    }

    /**
     * Gets the current position in the file.
     *
     * @return RuntimeScalar containing the current byte position, or -1 on error
     */
    @Override
    public RuntimeScalar tell() {
        try {
            return getScalarInt(fileChannel.position());
        } catch (IOException e) {
            handleIOException(e, "tell failed");
            return getScalarInt(-1);
        }
    }

    /**
     * Seeks to a new position in the file based on the whence parameter.
     *
     * <p>The whence parameter determines how the position is calculated:
     * <ul>
     *   <li>SEEK_SET (0): Set position to pos bytes from the beginning of the file</li>
     *   <li>SEEK_CUR (1): Set position to current position + pos bytes</li>
     *   <li>SEEK_END (2): Set position to end of file + pos bytes</li>
     * </ul>
     *
     * <p>Seeking clears the EOF flag since we may no longer be at the end of file.
     *
     * @param pos    the offset in bytes
     * @param whence the reference point for the offset (SEEK_SET, SEEK_CUR, or SEEK_END)
     * @return RuntimeScalar with true on success, false on failure
     */
    @Override
    public RuntimeScalar seek(long pos, int whence) {
        try {
            long newPosition;

            switch (whence) {
                case SEEK_SET: // from beginning
                    newPosition = pos;
                    break;
                case SEEK_CUR: // from current position
                    newPosition = fileChannel.position() + pos;
                    break;
                case SEEK_END: // from end of file
                    newPosition = fileChannel.size() + pos;
                    break;
                default:
                    return handleIOException(new IOException("Invalid whence value: " + whence), "seek failed");
            }

            // Ensure the new position is not negative
            if (newPosition < 0) {
                return handleIOException(new IOException("Negative seek position"), "seek failed");
            }

            fileChannel.position(newPosition);
            // Perl semantics: seeking to EOF sets eof flag, seeking elsewhere clears it.
            try {
                isEOF = (fileChannel.position() >= fileChannel.size());
            } catch (IOException e) {
                isEOF = false;
            }
            return scalarTrue;
        } catch (IOException e) {
            return handleIOException(e, "seek failed");
        }
    }

    /**
     * Flushes any buffered data to the underlying storage device.
     *
     * <p>For FileChannel, writes go directly to the OS buffer (no Java-side buffering),
     * so this is effectively a no-op. We intentionally do NOT call force() here
     * because fsync is extremely slow. Use {@link #sync()} for explicit disk sync.
     *
     * @return RuntimeScalar with true on success
     */
    @Override
    public RuntimeScalar flush() {
        // FileChannel has no Java-side buffer to flush.
        // We don't call force() here because it's extremely slow (fsync).
        // Use sync() if explicit disk synchronization is needed.
        return scalarTrue;
    }

    /**
     * Synchronizes file data to the underlying storage device (fsync).
     *
     * <p>This method forces all buffered data and metadata to be written to
     * the physical storage device. This is slow but guarantees data durability.
     * Use this only when you need to ensure data survives a system crash.
     *
     * @return RuntimeScalar with true on success
     */
    public RuntimeScalar sync() {
        try {
            fileChannel.force(true);
            return scalarTrue;
        } catch (IOException e) {
            return handleIOException(e, "sync failed");
        }
    }

    /**
     * Gets the file descriptor number for this channel.
     *
     * <p>Java's FileChannel does not expose the underlying OS file descriptor.
     * We return undef to match Perl's behavior for handles without a real fd.
     *
     * @return RuntimeScalar with undef (Java doesn't expose real fds)
     */
    @Override
    public RuntimeScalar fileno() {
        return RuntimeScalarCache.scalarUndef;
    }

    /**
     * Truncates the file to the specified length.
     *
     * <p>If the file is currently larger than the specified length, the extra data
     * is discarded. If the file is smaller, it is extended with null bytes.
     *
     * @param length the desired length of the file in bytes
     * @return RuntimeScalar with true on success
     * @throws IllegalArgumentException if length is negative
     */
    public RuntimeScalar truncate(long length) {
        try {
            if (length < 0) {
                throw new IllegalArgumentException("Invalid arguments for truncate operation.");
            }
            fileChannel.truncate(length);
            return scalarTrue;
        } catch (IOException e) {
            return handleIOException(e, "truncate failed");
        }
    }

    /**
     * Applies or removes an advisory lock on the file.
     *
     * <p>This implements Perl's flock() function using Java's FileLock API.
     * The operation is a bitmask of:
     * <ul>
     *   <li>LOCK_SH (1) - Shared lock (for reading, multiple processes can hold)</li>
     *   <li>LOCK_EX (2) - Exclusive lock (for writing, only one process can hold)</li>
     *   <li>LOCK_UN (8) - Unlock (release any held lock)</li>
     *   <li>LOCK_NB (4) - Non-blocking (can be OR'd with SH or EX)</li>
     * </ul>
     *
     * @param operation the lock operation bitmask
     * @return RuntimeScalar with true on success, false on failure
     */
    @Override
    public RuntimeScalar flock(int operation) {
        try {
            boolean nonBlocking = (operation & LOCK_NB) != 0;
            boolean unlock = (operation & LOCK_UN) != 0;
            boolean shared = (operation & LOCK_SH) != 0;
            boolean exclusive = (operation & LOCK_EX) != 0;

            if (unlock) {
                releaseCurrentLock();
                return scalarTrue;
            }

            // Release any existing lock before acquiring a new one
            releaseCurrentLock();

            if (exclusive || shared) {
                // shared=true for LOCK_SH, shared=false for LOCK_EX
                boolean isShared = shared && !exclusive;

                // For SHARED locks with a known path, consult the JVM-wide registry
                // so that multiple flock(LOCK_SH) calls on the same file from the
                // same JVM don't trip OverlappingFileLockException. This matches
                // POSIX flock() semantics (multiple shared locks per process are OK).
                if (isShared && lockKey != null) {
                    synchronized (sharedLockRegistry) {
                        SharedLockState state = sharedLockRegistry.get(lockKey);
                        if (state != null && state.nioLock != null && state.nioLock.isShared()) {
                            // Another CustomFileChannel in this JVM already holds a
                            // shared lock on this file — piggyback on it.
                            state.refCount++;
                            holdsSharedLockViaRegistry = true;
                            cleanupState.lockKey = lockKey;
                            cleanupState.viaRegistry = true;
                            cleanupState.nioLock = null;
                            return scalarTrue;
                        }
                        // No existing shared lock. Acquire one on our channel and
                        // register it so sibling channels can piggyback.
                        try {
                            FileLock lock = nonBlocking
                                    ? fileChannel.tryLock(0, Long.MAX_VALUE, true)
                                    : fileChannel.lock(0, Long.MAX_VALUE, true);
                            if (lock == null) {
                                getGlobalVariable("main::!").set(11); // EAGAIN/EWOULDBLOCK
                                return RuntimeScalarCache.scalarFalse;
                            }
                            SharedLockState newState = new SharedLockState();
                            newState.nioLock = lock;
                            newState.refCount = 1;
                            sharedLockRegistry.put(lockKey, newState);
                            currentLock = lock;
                            holdsSharedLockViaRegistry = true;
                            cleanupState.lockKey = lockKey;
                            cleanupState.viaRegistry = true;
                            cleanupState.nioLock = null;
                            return scalarTrue;
                        } catch (OverlappingFileLockException e) {
                            // Same JVM already holds a lock on this region that
                            // wasn't registered (e.g. a prior EXCLUSIVE lock from
                            // a different channel). Fall through to EAGAIN.
                            getGlobalVariable("main::!").set(11);
                            return RuntimeScalarCache.scalarFalse;
                        }
                    }
                }

                // Exclusive lock, or shared lock with no path (fd-only channel):
                // use the straight NIO path and accept its stricter semantics.
                if (nonBlocking) {
                    currentLock = fileChannel.tryLock(0, Long.MAX_VALUE, isShared);
                    if (currentLock == null) {
                        getGlobalVariable("main::!").set(11); // EAGAIN/EWOULDBLOCK
                        return RuntimeScalarCache.scalarFalse;
                    }
                } else {
                    try {
                        currentLock = fileChannel.lock(0, Long.MAX_VALUE, isShared);
                    } catch (OverlappingFileLockException e) {
                        // The same JVM already holds a lock on this region — most
                        // commonly a SHARED lock from a sibling CustomFileChannel
                        // whose Perl-level handle has gone out of scope but whose
                        // underlying RuntimeIO/lock hasn't been released yet
                        // (Path::Tiny's slurp() pattern: returns from a sub while
                        // the locked $fh is still in scope, then immediately calls
                        // append({truncate=>1}) which wants LOCK_EX). Try to clean
                        // up abandoned handles via the existing fd-recycling
                        // pathway, then retry once.
                        if (lockKey != null
                                && reclaimAbandonedSharedLock(lockKey)) {
                            currentLock = fileChannel.lock(0, Long.MAX_VALUE, isShared);
                        } else {
                            throw e;
                        }
                    }
                }
                cleanupState.lockKey = null;
                cleanupState.viaRegistry = false;
                cleanupState.nioLock = currentLock;
                return scalarTrue;
            }

            // Invalid operation (neither lock nor unlock specified)
            getGlobalVariable("main::!").set(22); // EINVAL
            return RuntimeScalarCache.scalarFalse;

        } catch (OverlappingFileLockException e) {
            // This happens when trying to lock a region already locked by this JVM
            getGlobalVariable("main::!").set(11); // EAGAIN
            return RuntimeScalarCache.scalarFalse;
        } catch (IOException e) {
            return handleIOException(e, "flock failed");
        }
    }

    /**
     * Try to reclaim a SHARED-lock registry entry whose holder has been
     * abandoned at the Perl level. Triggers the IO fd-recycling sweep
     * ({@link org.perlonjava.runtime.runtimetypes.RuntimeIO#processAbandonedGlobs()})
     * — and, if that doesn't drop the entry, gives the JVM a hint via
     * {@code System.gc()} so any pending {@link Cleaner} actions and
     * {@link java.lang.ref.PhantomReference}s for unreachable handles get
     * processed before we retry the lock acquisition.
     *
     * @return {@code true} if the registry entry for {@code key} was removed
     *     (so the caller should retry); {@code false} otherwise.
     */
    private static boolean reclaimAbandonedSharedLock(String key) {
        org.perlonjava.runtime.runtimetypes.RuntimeIO.processAbandonedGlobs();
        if (!sharedLockRegistry.containsKey(key)) {
            return true;
        }
        // Nudge the JVM to clean up any handles that are unreachable but
        // haven't yet been enqueued for collection (e.g. a Path::Tiny `slurp`
        // returned but its lexical $fh hasn't been GC'd in this microbench
        // window). System.gc() is a hint; on a normal JVM this is enough to
        // let the Cleaner action and PhantomReference for the abandoned
        // handle run before we retry. We block briefly to give those
        // background mechanisms a chance to actually fire.
        System.gc();
        for (int i = 0; i < 5 && sharedLockRegistry.containsKey(key); i++) {
            try {
                Thread.sleep(2);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            org.perlonjava.runtime.runtimetypes.RuntimeIO.processAbandonedGlobs();
        }
        return !sharedLockRegistry.containsKey(key);
    }

    /**
     * Release whatever lock this channel currently holds, whether directly via
     * {@link #currentLock} or via the shared-lock registry. Safe to call when
     * no lock is held.
     */
    private void releaseCurrentLock() throws IOException {
        if (holdsSharedLockViaRegistry && lockKey != null) {
            synchronized (sharedLockRegistry) {
                SharedLockState state = sharedLockRegistry.get(lockKey);
                if (state != null) {
                    state.refCount--;
                    if (state.refCount <= 0) {
                        // Last holder — release the real NIO lock.
                        if (state.nioLock != null && state.nioLock.isValid()) {
                            state.nioLock.release();
                        }
                        sharedLockRegistry.remove(lockKey);
                    }
                }
            }
            // currentLock may point to the registry's NIO lock; either the last
            // holder released it above, or another holder still needs it. Either
            // way, we must not call release() on it ourselves a second time.
            currentLock = null;
            holdsSharedLockViaRegistry = false;
            cleanupState.viaRegistry = false;
            cleanupState.nioLock = null;
            return;
        }
        if (currentLock != null) {
            if (currentLock.isValid()) {
                currentLock.release();
            }
            currentLock = null;
        }
        cleanupState.viaRegistry = false;
        cleanupState.nioLock = null;
    }

    @Override
    public RuntimeScalar sysread(int length) {
        try {
            ByteBuffer buffer = ByteBuffer.allocate(length);
            int bytesRead = fileChannel.read(buffer);  // Changed from 'channel' to 'fileChannel'

            if (bytesRead == -1) {
                // EOF - return empty string
                return new RuntimeScalar("");
            }

            buffer.flip();
            byte[] result = new byte[bytesRead];
            buffer.get(result);

            return new RuntimeScalar(result);
        } catch (IOException e) {
            String msg = e.getMessage();
            if (msg != null && msg.toLowerCase().contains("is a directory")) {
                // Treat EISDIR as EOF - don't set $!
                // This matches platforms that can "read directories as plain files"
                return new RuntimeScalar("");
            }
            getGlobalVariable("main::!").set(e.getMessage());
            return new RuntimeScalar(); // undef
        }
    }

    @Override
    public RuntimeScalar syswrite(String data) {
        try {
            // Convert string to bytes (each char is a byte 0-255)
            ByteBuffer buffer = ByteBuffer.allocate(data.length());
            for (int i = 0; i < data.length(); i++) {
                buffer.put((byte) (data.charAt(i) & 0xFF));
            }
            buffer.flip();

            int bytesWritten = fileChannel.write(buffer);  // Changed from 'channel' to 'fileChannel'
            return new RuntimeScalar(bytesWritten);
        } catch (IOException e) {
            getGlobalVariable("main::!").set(e.getMessage());
            return new RuntimeScalar(); // undef
        }
    }
}
