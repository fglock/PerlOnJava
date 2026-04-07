package org.perlonjava.runtime.io;

import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.perlonjava.runtime.runtimetypes.RuntimeIO.handleIOException;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.*;

/**
 * A reference-counted IOHandle wrapper that enables proper filehandle duplication
 * semantics — the Java equivalent of POSIX {@code dup(2)}.
 *
 * <h3>Background: Perl's filehandle duplication</h3>
 * <p>When Perl executes {@code open(SAVE, ">&STDERR")}, it creates a new file descriptor
 * (via dup()) that shares the same underlying file description. Both fds are independent:
 * closing one does not affect the other. The underlying OS resource (file, pipe, socket)
 * is only released when ALL duplicates are closed.</p>
 *
 * <p>Java doesn't expose POSIX file descriptors, so we simulate this behavior with
 * reference-counted wrappers around an underlying {@link IOHandle} delegate.</p>
 *
 * <h3>Architecture</h3>
 * <pre>
 *   ┌──────────────────────┐     ┌──────────────────────┐
 *   │ DupIOHandle (fd=1)   │     │ DupIOHandle (fd=5)   │
 *   │ closed=false         │     │ closed=false         │
 *   │ refCount ─────────────┼─────┼─▶ AtomicInteger(2)  │
 *   │ delegate ─────────────┼─────┼─▶ StandardIO         │
 *   └──────────────────────┘     └──────────────────────┘
 *                                      (shared)
 * </pre>
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li><b>Creation via {@link #createPair(IOHandle, int)}</b>: Called the first time
 *       a handle is duplicated. Creates two DupIOHandles sharing the same delegate and
 *       a new {@code AtomicInteger(2)} refCount. The first wrapper preserves the
 *       original's fd; the second gets a new fd from {@link FileDescriptorTable}.</li>
 *   <li><b>Subsequent dups via {@link #addDup(DupIOHandle)}</b>: Increments the shared
 *       refCount and creates a new DupIOHandle with a new fd. No limit on how many
 *       dups can be created.</li>
 *   <li><b>Close</b>: Each DupIOHandle tracks its own {@code closed} flag. On close:
 *       <ul>
 *         <li>Marks itself as closed (further I/O operations will fail)</li>
 *         <li>Unregisters its fd from {@link FileDescriptorTable}</li>
 *         <li>Decrements the shared refCount</li>
 *         <li>If refCount reaches 0 (last dup closed), actually closes the delegate</li>
 *         <li>If refCount > 0 (other dups still open), just flushes the delegate</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <h3>Thread safety</h3>
 * <p>The refCount uses {@link AtomicInteger} for thread-safe decrement. The {@code closed}
 * flag is per-instance and not synchronized — this matches the Perl model where each
 * filehandle is used by a single thread. If concurrent access is needed in the future,
 * the closed flag should be made volatile or synchronized.</p>
 *
 * <h3>fd number management</h3>
 * <p>Each DupIOHandle holds a synthetic fd number assigned by {@link FileDescriptorTable}.
 * The {@link #fileno()} method returns this fd (not the delegate's fd), so each duplicate
 * has a unique fileno as Perl expects. This fd is registered in FileDescriptorTable for
 * lookup by select() and in RuntimeIO for lookup by {@code findFileHandleByDescriptor()}.</p>
 *
 * @see IOOperator#duplicateFileHandle(RuntimeIO)  where DupIOHandles are created
 * @see IOOperator#openFileHandleDup(String, String)  entry point for Perl's open() dup modes
 * @see FileDescriptorTable  synthetic fd allocation and lookup
 */
public class DupIOHandle implements IOHandle {

    /** The underlying I/O implementation (StandardIO, FileIOHandle, etc.) — never another DupIOHandle. */
    private final IOHandle delegate;
    /** Shared across all dups of the same delegate. Decremented on close; delegate closed at zero. */
    private final AtomicInteger refCount;
    /** Per-instance closed flag. Once true, all I/O operations on THIS dup return errors. */
    private boolean closed = false;
    /** Synthetic fd number unique to this dup, assigned by FileDescriptorTable. */
    private final int fd;

    /**
     * Creates a DupIOHandle wrapping the given delegate with a shared refcount.
     * Allocates a new fd from FileDescriptorTable.
     */
    DupIOHandle(IOHandle delegate, AtomicInteger refCount) {
        this.delegate = delegate;
        this.refCount = refCount;
        this.fd = FileDescriptorTable.register(this);
    }

    /**
     * Creates a DupIOHandle wrapping the given delegate with a shared refcount
     * and an explicit fd number (used to preserve the original handle's fileno).
     */
    DupIOHandle(IOHandle delegate, AtomicInteger refCount, int explicitFd) {
        this.delegate = delegate;
        this.refCount = refCount;
        this.fd = explicitFd;
        FileDescriptorTable.registerAt(explicitFd, this);
    }

    /**
     * Creates a pair of DupIOHandles sharing the same delegate and refcount.
     * The first handle preserves the original's fileno; the second gets a new fd.
     *
     * @param delegate the underlying IOHandle to share
     * @param originalFd the fd number to preserve for the original handle
     * @return array of two DupIOHandles [forOriginal, forDuplicate]
     */
    public static DupIOHandle[] createPair(IOHandle delegate, int originalFd) {
        AtomicInteger refCount = new AtomicInteger(2);
        DupIOHandle a = new DupIOHandle(delegate, refCount, originalFd);
        DupIOHandle b = new DupIOHandle(delegate, refCount);
        return new DupIOHandle[]{a, b};
    }

    /**
     * Creates an additional duplicate sharing the same delegate and refcount
     * as an existing DupIOHandle.
     *
     * @param existing an existing DupIOHandle to share with
     * @return a new DupIOHandle sharing the same delegate
     */
    public static DupIOHandle addDup(DupIOHandle existing) {
        existing.refCount.incrementAndGet();
        return new DupIOHandle(existing.delegate, existing.refCount);
    }

    /**
     * Creates an additional duplicate at a specific fd number, sharing the same
     * delegate and refcount as an existing DupIOHandle. Used by POSIX::dup2()
     * where the target fd is specified.
     *
     * @param existing   an existing DupIOHandle to share with
     * @param explicitFd the fd number to assign to the new duplicate
     * @return a new DupIOHandle sharing the same delegate at the specified fd
     */
    public static DupIOHandle addDupAt(DupIOHandle existing, int explicitFd) {
        existing.refCount.incrementAndGet();
        return new DupIOHandle(existing.delegate, existing.refCount, explicitFd);
    }

    /**
     * Creates a pair of DupIOHandles with explicit fd numbers for both.
     * Used by POSIX::dup2() when the source is not yet a DupIOHandle.
     */
    public static DupIOHandle[] createPairAt(IOHandle delegate, int originalFd, int newFd) {
        AtomicInteger refCount = new AtomicInteger(2);
        DupIOHandle a = new DupIOHandle(delegate, refCount, originalFd);
        DupIOHandle b = new DupIOHandle(delegate, refCount, newFd);
        return new DupIOHandle[]{a, b};
    }

    /**
     * Returns the file descriptor number for this duplicate.
     */
    public int getFd() {
        return fd;
    }

    /**
     * Returns the underlying delegate IOHandle.
     */
    public IOHandle getDelegate() {
        return delegate;
    }

    // ---- Delegated I/O operations (check closed state first) ----

    @Override
    public RuntimeScalar write(String string) {
        if (closed) return handleClosed("write");
        return delegate.write(string);
    }

    @Override
    public RuntimeScalar flush() {
        if (closed) return scalarFalse;
        return delegate.flush();
    }

    @Override
    public RuntimeScalar sync() {
        if (closed) return scalarFalse;
        return delegate.sync();
    }

    @Override
    public RuntimeScalar doRead(int maxBytes, Charset charset) {
        if (closed) return handleClosed("read");
        return delegate.doRead(maxBytes, charset);
    }

    @Override
    public RuntimeScalar fileno() {
        if (closed) return handleClosed("fileno");
        return new RuntimeScalar(fd);
    }

    @Override
    public RuntimeScalar eof() {
        if (closed) return scalarTrue;
        return delegate.eof();
    }

    @Override
    public RuntimeScalar tell() {
        if (closed) return handleClosed("tell");
        return delegate.tell();
    }

    @Override
    public RuntimeScalar seek(long pos, int whence) {
        if (closed) return handleClosed("seek");
        return delegate.seek(pos, whence);
    }

    @Override
    public RuntimeScalar truncate(long length) {
        if (closed) return handleClosed("truncate");
        return delegate.truncate(length);
    }

    @Override
    public RuntimeScalar flock(int operation) {
        if (closed) return handleClosed("flock");
        return delegate.flock(operation);
    }

    @Override
    public RuntimeScalar bind(String address, int port) {
        if (closed) return handleClosed("bind");
        return delegate.bind(address, port);
    }

    @Override
    public RuntimeScalar connect(String address, int port) {
        if (closed) return handleClosed("connect");
        return delegate.connect(address, port);
    }

    @Override
    public RuntimeScalar listen(int backlog) {
        if (closed) return handleClosed("listen");
        return delegate.listen(backlog);
    }

    @Override
    public RuntimeScalar accept() {
        if (closed) return handleClosed("accept");
        return delegate.accept();
    }

    @Override
    public RuntimeScalar sysread(int length) {
        if (closed) return handleClosed("sysread");
        return delegate.sysread(length);
    }

    @Override
    public RuntimeScalar syswrite(String data) {
        if (closed) return handleClosed("syswrite");
        return delegate.syswrite(data);
    }

    // ---- Close with reference counting ----

    /**
     * Closes this duplicate handle.
     *
     * <p>Semantics:
     * <ol>
     *   <li>If already closed, returns an error (matches Perl's "close on closed fh").</li>
     *   <li>Marks this instance as closed — subsequent I/O operations will fail.</li>
     *   <li>Unregisters this fd from {@link FileDescriptorTable}.</li>
     *   <li>Decrements the shared refCount atomically.</li>
     *   <li>If this was the <em>last</em> duplicate (refCount → 0), closes the delegate.</li>
     *   <li>Otherwise, just flushes the delegate to ensure buffered data is written.</li>
     * </ol>
     */
    @Override
    public RuntimeScalar close() {
        if (closed) {
            return handleIOException(
                    new java.io.IOException("Handle is already closed."),
                    "Handle is already closed.");
        }
        closed = true;
        FileDescriptorTable.unregister(fd);

        int remaining = refCount.decrementAndGet();
        if (remaining <= 0) {
            // Last reference — actually close the underlying handle
            return delegate.close();
        }
        // Other duplicates still open — just flush, don't close the delegate
        delegate.flush();
        return scalarTrue;
    }

    private RuntimeScalar handleClosed(String operation) {
        return handleIOException(
                new java.io.IOException("Cannot " + operation + " on a closed handle."),
                operation + " on closed handle failed");
    }
}
