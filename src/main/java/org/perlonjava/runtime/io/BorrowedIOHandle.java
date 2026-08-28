package org.perlonjava.runtime.io;

import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.perlonjava.runtime.runtimetypes.RuntimeIO.handleIOException;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarTrue;

/**
 * A non-owning IOHandle wrapper for Perl's parsimonious dup semantics ({@code >&=} / {@code <&=}).
 *
 * <h3>Background: parsimonious dup in Perl</h3>
 * <p>When Perl executes {@code open(F, ">&=STDOUT")}, it performs an {@code fdopen()} —
 * creating a new FILE* that shares the same fd as STDOUT. The key semantic difference
 * from a full dup ({@code >&}) is:</p>
 * <ul>
 *   <li>Both handles share the <em>same</em> file descriptor (same fileno).</li>
 *   <li>Closing the new handle ({@code close F}) does <em>not</em> close the underlying
 *       resource — the original handle (STDOUT) remains fully operational.</li>
 *   <li>This is a lightweight alias — no new OS-level file descriptor is allocated.</li>
 * </ul>
 *
 * <h3>Implementation</h3>
 * <p>BorrowedIOHandle delegates all I/O operations to the underlying delegate IOHandle,
 * but shares a lifecycle with the handle it borrows. This ensures that closing one
 * of several parsimonious aliases does not invalidate the others, while the
 * underlying resource is eventually closed when the last related handle closes.</p>
 *
 * <p>Unlike {@link DupIOHandle}, this wrapper:</p>
 * <ul>
 *   <li>Does NOT allocate a new fd number (shares the delegate's fileno)</li>
 *   <li>Uses reference counting only to retain the shared resource's lifetime</li>
 *   <li>Is much simpler — just a thin delegation layer with a close-guard</li>
 * </ul>
 *
 * @see DupIOHandle  for full dup semantics ({@code >&}) with reference counting
 * @see IOOperator#openFileHandleDup(String, String)  where this is created
 */
public class BorrowedIOHandle implements IOHandle {

    @Override
    public ThreadInheritancePolicy threadInheritancePolicy() {
        return ThreadInheritancePolicy.WRAPPER_COPY;
    }

    /** The underlying handle shared by all parsimonious aliases. */
    private final IOHandle delegate;
    /** Shared lifecycle for the source handle and every parsimonious alias. */
    private final AtomicInteger refCount;
    /** Per-instance closed flag. Once true, all I/O operations on THIS wrapper fail. */
    private boolean closed = false;

    /**
     * Creates a BorrowedIOHandle wrapping the given delegate.
     *
     * @param delegate the underlying IOHandle to borrow
     */
    public BorrowedIOHandle(IOHandle delegate) {
        this(delegate, new AtomicInteger(1));
    }

    private BorrowedIOHandle(IOHandle delegate, AtomicInteger refCount) {
        this.delegate = delegate;
        this.refCount = refCount;
    }

    /**
     * Replaces an ordinary source handle with the source side of a borrowed
     * pair, and returns the alias side. Both wrappers retain the delegate until
     * they have each been closed.
     */
    public static BorrowedIOHandle[] createPair(IOHandle delegate) {
        AtomicInteger refCount = new AtomicInteger(2);
        return new BorrowedIOHandle[] {
                new BorrowedIOHandle(delegate, refCount),
                new BorrowedIOHandle(delegate, refCount)
        };
    }

    /** Creates another parsimonious alias sharing this handle's lifecycle. */
    public static BorrowedIOHandle addBorrow(BorrowedIOHandle existing) {
        existing.refCount.incrementAndGet();
        return new BorrowedIOHandle(existing.delegate, existing.refCount);
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
    public int writeSome(String string) {
        return closed ? -1 : delegate.writeSome(string);
    }

    @Override
    public RuntimeScalar flush() {
        if (closed) return scalarTrue;
        return delegate.flush();
    }

    @Override
    public RuntimeScalar sync() {
        if (closed) return scalarTrue;
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
        // Return the delegate's fileno — parsimonious dup shares the same fd
        return delegate.fileno();
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

    // ---- Close: retain the delegate while another related handle is live ----

    /**
     * Closes this borrowed handle.
     *
     * <p>Closes this wrapper while retaining the delegate until the final
     * source or alias wrapper closes. This lets an alias outlive the lexical
     * {@code sysopen} handle from which it was created.</p>
     */
    @Override
    public RuntimeScalar close() {
        if (closed) {
            return handleIOException(
                    new java.io.IOException("Handle is already closed."),
                    "Handle is already closed.");
        }
        closed = true;
        if (refCount.decrementAndGet() == 0) {
            return delegate.close();
        }
        delegate.flush();
        return scalarTrue;
    }

    private RuntimeScalar handleClosed(String operation) {
        return handleIOException(
                new java.io.IOException("Cannot " + operation + " on a closed handle."),
                operation + " on closed handle failed");
    }
}
