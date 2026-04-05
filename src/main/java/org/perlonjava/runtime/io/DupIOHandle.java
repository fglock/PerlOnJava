package org.perlonjava.runtime.io;

import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.perlonjava.runtime.runtimetypes.RuntimeIO.handleIOException;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.*;

/**
 * A reference-counted IOHandle wrapper that enables proper filehandle duplication.
 *
 * <p>When Perl does {@code open(SAVE, ">&STDERR")}, it creates a new file descriptor
 * (via dup()) that shares the same underlying file description. Both fds are independent:
 * closing one does not affect the other. The underlying resource is only released when
 * ALL duplicates are closed.
 *
 * <p>This class implements that semantic by wrapping a delegate IOHandle with a shared
 * reference count. Each DupIOHandle tracks its own closed state. When close() is called,
 * the refcount is decremented; the delegate is only actually closed when the last
 * DupIOHandle is closed.
 */
public class DupIOHandle implements IOHandle {

    private final IOHandle delegate;
    private final AtomicInteger refCount;
    private boolean closed = false;
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
    public boolean isBlocking() {
        if (closed) return true;
        return delegate.isBlocking();
    }

    @Override
    public boolean setBlocking(boolean blocking) {
        if (closed) return blocking;
        return delegate.setBlocking(blocking);
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
