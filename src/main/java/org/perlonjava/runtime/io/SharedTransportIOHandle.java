package org.perlonjava.runtime.io;

import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.perlonjava.runtime.runtimetypes.RuntimeIO.handleIOError;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarFalse;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarTrue;

/**
 * One independently closable lease over a transport inherited by Perl ithreads.
 * The transport is closed only after the final parent/child lease is released.
 */
public final class SharedTransportIOHandle implements IOHandle {
    private final IOHandle delegate;
    private final AtomicInteger leases;
    private boolean closed;

    private SharedTransportIOHandle(IOHandle delegate, AtomicInteger leases) {
        this.delegate = delegate;
        this.leases = leases;
    }

    /** Replace an owning handle with a parent lease and create its first child lease. */
    public static SharedTransportIOHandle[] createPair(IOHandle delegate) {
        AtomicInteger leases = new AtomicInteger(2);
        return new SharedTransportIOHandle[] {
                new SharedTransportIOHandle(delegate, leases),
                new SharedTransportIOHandle(delegate, leases)
        };
    }

    /** Add one child lease to an already inherited transport. */
    public SharedTransportIOHandle inheritedCopy() {
        if (closed) return null;
        leases.incrementAndGet();
        return new SharedTransportIOHandle(delegate, leases);
    }

    public IOHandle getDelegate() {
        return delegate;
    }

    @Override
    public ThreadInheritancePolicy threadInheritancePolicy() {
        return ThreadInheritancePolicy.IMPLEMENTATION_COPY;
    }

    @Override public boolean canRead() { return open() && delegate.canRead(); }
    @Override public boolean canWrite() { return open() && delegate.canWrite(); }

    @Override public RuntimeScalar write(String value) { return open() ? delegate.write(value) : closed("write"); }
    @Override public int writeSome(String value) { return open() ? delegate.writeSome(value) : -1; }
    @Override public RuntimeScalar flush() { return open() ? delegate.flush() : scalarFalse; }
    @Override public RuntimeScalar sync() { return open() ? delegate.sync() : scalarFalse; }
    @Override public RuntimeScalar doRead(int size, Charset charset) { return open() ? delegate.doRead(size, charset) : closed("read"); }
    @Override public RuntimeScalar fileno() { return open() ? delegate.fileno() : closed("fileno"); }
    @Override public boolean isReadReady() { return !open() || delegate.isReadReady(); }
    @Override public RuntimeScalar eof() { return open() ? delegate.eof() : scalarTrue; }
    @Override public RuntimeScalar tell() { return open() ? delegate.tell() : closed("tell"); }
    @Override public RuntimeScalar seek(long pos, int whence) { return open() ? delegate.seek(pos, whence) : closed("seek"); }
    @Override public RuntimeScalar truncate(long length) { return open() ? delegate.truncate(length) : closed("truncate"); }
    @Override public RuntimeScalar flock(int operation) { return open() ? delegate.flock(operation) : closed("flock"); }
    @Override public RuntimeScalar bind(String address, int port) { return open() ? delegate.bind(address, port) : closed("bind"); }
    @Override public RuntimeScalar connect(String address, int port) { return open() ? delegate.connect(address, port) : closed("connect"); }
    @Override public RuntimeScalar listen(int backlog) { return open() ? delegate.listen(backlog) : closed("listen"); }
    @Override public RuntimeScalar accept() { return open() ? delegate.accept() : closed("accept"); }
    @Override public RuntimeScalar sysread(int length) { return open() ? delegate.sysread(length) : closed("sysread"); }
    @Override public RuntimeScalar syswrite(String data) { return open() ? delegate.syswrite(data) : closed("syswrite"); }

    @Override
    public synchronized RuntimeScalar close() {
        if (closed) return handleIOError(9); // EBADF
        closed = true;
        if (leases.decrementAndGet() == 0) return delegate.close();
        delegate.flush();
        return scalarTrue;
    }

    private synchronized boolean open() {
        return !closed;
    }

    /**
     * A released lease behaves like any other closed descriptor: the operator
     * fails and {@code $!} reports EBADF. The operation name is kept in the
     * signature for readability at the call sites.
     */
    private RuntimeScalar closed(String operation) {
        return handleIOError(9); // EBADF
    }
}
