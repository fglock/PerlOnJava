package org.perlonjava.runtime.io;

import org.perlonjava.runtime.runtimetypes.RuntimeIO;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarCache;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Base interface for all I/O handle implementations in PerlOnJava.
 *
 * <h2>Flush vs Sync Semantics</h2>
 *
 * <p>This interface distinguishes between two related but distinct operations:</p>
 *
 * <h3>flush() - Buffer Flush</h3>
 * <p>Flushes any <b>application-level buffers</b> to the operating system's kernel buffer.
 * This is equivalent to Perl's {@code $fh->flush()} or C's {@code fflush()}.
 * After flush(), data is visible to other processes but may not yet be on physical disk.</p>
 *
 * <p>For unbuffered I/O (like Java NIO FileChannel), flush() is a no-op since writes
 * go directly to the kernel buffer.</p>
 *
 * <h3>sync() - Disk Synchronization</h3>
 * <p>Forces data to be written to the <b>physical storage device</b> (fsync).
 * This is equivalent to Perl's {@code IO::Handle->sync()} or POSIX {@code fsync()}.
 * This operation is slow but guarantees data durability in case of system crash.</p>
 *
 * <h3>Performance Note</h3>
 * <p>fsync() is extremely slow (can take 10-100ms per call). The previous implementation
 * incorrectly called fsync() on every flush() and close(), causing severe performance
 * issues for I/O-heavy workloads like Image::ExifTool (94% of time spent in fsync).</p>
 *
 * <p>Use sync() only when you need guaranteed durability (e.g., database commits,
 * critical configuration saves). For normal file operations, flush() or just close()
 * is sufficient - the OS will eventually write data to disk.</p>
 *
 * @see CustomFileChannel
 * @see StandardIO
 * @see LayeredIOHandle
 */
public interface IOHandle {

    int SEEK_SET = 0;  // Seek from beginning of file
    int SEEK_CUR = 1;  // Seek from current position
    int SEEK_END = 2;  // Seek from end of file

    // Buffer for pushed-back byte values
    ThreadLocal<Deque<Integer>> ungetBuffer = ThreadLocal.withInitial(ArrayDeque::new);

    /**
     * Writes data to this I/O handle.
     *
     * @param string the data to write
     * @return RuntimeScalar with true on success, false on failure
     */
    RuntimeScalar write(String string);

    /**
     * Closes this I/O handle and releases system resources.
     *
     * <p>Note: This does NOT call sync()/fsync. The OS will flush kernel buffers
     * on close. If you need guaranteed disk durability, call sync() before close().</p>
     *
     * @return RuntimeScalar with true on success
     */
    RuntimeScalar close();

    /**
     * Flushes application-level buffers to the operating system.
     *
     * <p>This is equivalent to Perl's {@code $fh->flush()} or C's {@code fflush()}.
     * After this call, data is in the OS kernel buffer and visible to other processes,
     * but may not yet be on physical disk.</p>
     *
     * <p>For unbuffered I/O (like Java NIO FileChannel), this is a no-op since
     * writes go directly to the kernel buffer.</p>
     *
     * <p><b>Note:</b> This does NOT call fsync(). Use {@link #sync()} if you need
     * guaranteed disk durability.</p>
     *
     * @return RuntimeScalar with true on success
     * @see #sync()
     */
    RuntimeScalar flush();

    /**
     * Synchronizes data to physical storage (fsync).
     *
     * <p>This is equivalent to Perl's {@code IO::Handle->sync()} or POSIX {@code fsync()}.
     * This forces all buffered data and metadata to be written to the physical storage
     * device, guaranteeing durability in case of system crash.</p>
     *
     * <p><b>Warning:</b> This operation is extremely slow (10-100ms typical).
     * Only use when you truly need guaranteed durability, such as:</p>
     * <ul>
     *   <li>Database transaction commits</li>
     *   <li>Critical configuration file saves</li>
     *   <li>Financial transaction logs</li>
     * </ul>
     *
     * <p>For normal file operations, just close() the file - the OS will
     * eventually write data to disk.</p>
     *
     * @return RuntimeScalar with true on success
     * @see #flush()
     */
    default RuntimeScalar sync() {
        // Default implementation: no-op for handles that don't support sync
        // (e.g., sockets, pipes, stdin/stdout)
        return RuntimeScalarCache.scalarTrue;
    }

    // Default ungetc implementation - takes a byte value (0-255)
    default RuntimeScalar ungetc(int byteValue) {
        if (byteValue == -1) {
            return new RuntimeScalar(0); // Cannot push back EOF
        }
        // Store the byte value directly - no validation needed for 0-255 range
        ungetBuffer.get().addFirst(byteValue);
        return new RuntimeScalar(byteValue);
    }

    // Helper method to check if there are buffered bytes
    default boolean hasBufferedChar() {
        return !ungetBuffer.get().isEmpty();
    }

    // Helper method to get next buffered byte value
    default int getBufferedChar() {
        Deque<Integer> buffer = ungetBuffer.get();
        return buffer.isEmpty() ? -1 : buffer.removeFirst();
    }

    // Clear the unget buffer (useful for seek operations, etc.)
    default void clearUngetBuffer() {
        ungetBuffer.get().clear();
    }

    // Public read methods that check unget buffer first
    default RuntimeScalar read(int maxBytes) {
        return read(maxBytes, StandardCharsets.ISO_8859_1);
    }

    default RuntimeScalar read(int maxBytes, Charset charset) {
        Deque<Integer> buffer = ungetBuffer.get();

        if (buffer.isEmpty()) {
            // No buffered values, delegate to actual read implementation
            return doRead(maxBytes, charset);
        }

        // We have buffered values - return them as byte values
        StringBuilder result = new StringBuilder();
        int bytesUsed = 0;

        // First, consume buffered values
        while (!buffer.isEmpty() && bytesUsed < maxBytes) {
            int value = buffer.removeFirst();
            // Treat stored values as byte values (0-255 range typically)
            result.append((char) (value & 0xFF));
            bytesUsed++;
        }

        // If we still have byte capacity, read from source
        if (bytesUsed < maxBytes) {
            RuntimeScalar sourceRead = doRead(maxBytes - bytesUsed, charset);
            if (sourceRead.getDefinedBoolean()) {
                result.append(sourceRead);
            }
        }

        return new RuntimeScalar(result.toString());
    }

    // Protected method that subclasses should override for actual reading
    default RuntimeScalar doRead(int maxBytes, Charset charset) {
        return RuntimeIO.handleIOError("read operation is not supported.");
    }

    default RuntimeScalar fileno() {
        return RuntimeIO.handleIOError("fileno operation is not supported.");
    }

    /**
     * Checks whether this handle has data available for reading without blocking.
     * <p>
     * Used by the 4-arg {@code select()} implementation to determine readiness
     * for non-socket handles (pipes, process streams) that cannot be registered
     * with Java NIO's {@link java.nio.channels.Selector}.
     * <p>
     * The default implementation returns {@code true} (always ready), which is
     * correct for regular files and handles where blocking isn't an issue.
     * Subclasses that wrap blocking streams (e.g., {@code ProcessInputHandle}
     * for subprocess pipes) should override this to check
     * {@code InputStream.available()} or equivalent.
     *
     * @return true if data is available or the handle is at EOF/closed; false if
     *         reading would block
     */
    default boolean isReadReady() {
        return true; // Regular files, closed handles, etc. are always "ready"
    }

    default RuntimeScalar eof() {
        return RuntimeIO.handleIOError("eof operation is not supported.");
    }

    default RuntimeScalar tell() {
        return RuntimeIO.handleIOError("tell operation is not supported.");
    }

    // Socket-specific methods
    default RuntimeScalar bind(String address, int port) {
        return RuntimeIO.handleIOError("Bind operation is not supported.");
    }

    default RuntimeScalar connect(String address, int port) {
        return RuntimeIO.handleIOError("Connect operation is not supported.");
    }

    default RuntimeScalar listen(int backlog) {
        return RuntimeIO.handleIOError("Listen operation is not supported.");
    }

    default RuntimeScalar accept() {
        return RuntimeIO.handleIOError("Accept operation is not supported.");
    }

    default RuntimeScalar seek(long pos, int whence) {
        // Clear unget buffer when seeking, as position changes
        clearUngetBuffer();
        return RuntimeIO.handleIOError("Seek operation is not supported.");
    }

    default RuntimeScalar seek(long pos) {
        return seek(pos, IOHandle.SEEK_SET);
    }

    default RuntimeScalar truncate(long length) {
        return RuntimeIO.handleIOError("Truncate operation is not supported.");
    }

    /**
     * Applies or removes an advisory lock on a file.
     *
     * <p>This is equivalent to Perl's {@code flock(FILEHANDLE, OPERATION)}.
     * The operation is a bitmask of:</p>
     * <ul>
     *   <li>LOCK_SH (1) - Shared lock (for reading)</li>
     *   <li>LOCK_EX (2) - Exclusive lock (for writing)</li>
     *   <li>LOCK_UN (8) - Unlock</li>
     *   <li>LOCK_NB (4) - Non-blocking (can be OR'd with SH or EX)</li>
     * </ul>
     *
     * @param operation the lock operation bitmask
     * @return RuntimeScalar with true on success, false on failure
     */
    default RuntimeScalar flock(int operation) {
        return RuntimeIO.handleIOError("flock operation is not supported on this handle type.");
    }

    // System-level I/O operations
    default RuntimeScalar sysread(int length) {
        return RuntimeIO.handleIOError("sysread operation is not supported.");
    }

    default RuntimeScalar syswrite(String data) {
        return RuntimeIO.handleIOError("syswrite operation is not supported.");
    }
}