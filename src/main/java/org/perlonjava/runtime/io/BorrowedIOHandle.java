package org.perlonjava.runtime.io;

import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.nio.charset.Charset;

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
 * but overrides {@link #close()} to only flush — never closing the delegate. This
 * ensures that after {@code close F}, the original handle (e.g. STDOUT) keeps working.</p>
 *
 * <p>Unlike {@link DupIOHandle}, this wrapper:</p>
 * <ul>
 *   <li>Does NOT allocate a new fd number (shares the delegate's fileno)</li>
 *   <li>Does NOT use reference counting (the delegate is never closed by us)</li>
 *   <li>Is much simpler — just a thin delegation layer with a close-guard</li>
 * </ul>
 *
 * @see DupIOHandle  for full dup semantics ({@code >&}) with reference counting
 * @see IOOperator#openFileHandleDup(String, String)  where this is created
 */
public class BorrowedIOHandle implements IOHandle {

    /** The underlying handle we're borrowing — never closed by us. */
    private final IOHandle delegate;
    /** Per-instance closed flag. Once true, all I/O operations on THIS wrapper fail. */
    private boolean closed = false;

    /**
     * Creates a BorrowedIOHandle wrapping the given delegate.
     *
     * @param delegate the underlying IOHandle to borrow (not owned — will not be closed)
     */
    public BorrowedIOHandle(IOHandle delegate) {
        this.delegate = delegate;
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

    // ---- Close: flush only, do NOT close the delegate ----

    /**
     * Closes this borrowed handle.
     *
     * <p>Only flushes the delegate — does NOT close the underlying resource.
     * This matches Perl's fdopen semantics where closing an fdopen'd FILE*
     * does not invalidate the original handle.</p>
     */
    @Override
    public RuntimeScalar close() {
        if (closed) {
            return handleIOException(
                    new java.io.IOException("Handle is already closed."),
                    "Handle is already closed.");
        }
        closed = true;
        // Only flush — never close the delegate. The original handle still owns it.
        delegate.flush();
        return scalarTrue;
    }

    private RuntimeScalar handleClosed(String operation) {
        return handleIOException(
                new java.io.IOException("Cannot " + operation + " on a closed handle."),
                operation + " on closed handle failed");
    }
}
