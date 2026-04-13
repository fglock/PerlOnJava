package org.perlonjava.runtime.io;

import org.perlonjava.runtime.nativ.ffm.FFMPosix;
import org.perlonjava.runtime.nativ.ffm.FFMPosixInterface;
import org.perlonjava.runtime.runtimetypes.RuntimeIO;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarCache;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * IOHandle implementation backed by a raw POSIX file descriptor.
 * Uses FFM read()/write()/close() to perform I/O directly on native fds.
 *
 * <p>This is used by IO::Pty to wrap pty master/slave file descriptors as
 * Perl filehandles. When {@code pty_allocate()} returns raw POSIX fds
 * (e.g., master=5, slave=6), this class makes them usable from Perl via
 * {@code IO::Handle->new_from_fd($fd, "r+")}.</p>
 *
 * <p>I/O is unbuffered — reads and writes go directly through the native
 * file descriptor via FFM system calls.</p>
 */
public class NativeFdIOHandle implements IOHandle {

    private final int nativeFd;
    private boolean closed = false;
    private boolean eofReached = false;

    /**
     * Create a new native fd handle.
     *
     * @param nativeFd the POSIX file descriptor number
     */
    public NativeFdIOHandle(int nativeFd) {
        this.nativeFd = nativeFd;
    }

    /**
     * Get the native POSIX file descriptor.
     */
    public int getNativeFd() {
        return nativeFd;
    }

    @Override
    public RuntimeScalar write(String string) {
        if (closed) {
            return RuntimeIO.handleIOError("write to closed filehandle");
        }
        try {
            byte[] bytes = string.getBytes(StandardCharsets.ISO_8859_1);
            int written = FFMPosix.get().nativeWrite(nativeFd, bytes, bytes.length);
            if (written == -1) {
                return RuntimeIO.handleIOError("write failed: " + FFMPosix.get().strerror(FFMPosix.get().errno()));
            }
            return RuntimeScalarCache.scalarTrue;
        } catch (Exception e) {
            return RuntimeIO.handleIOError("write failed: " + e.getMessage());
        }
    }

    @Override
    public RuntimeScalar close() {
        if (closed) {
            return RuntimeScalarCache.scalarTrue;
        }
        closed = true;
        FileDescriptorTable.unregister(nativeFd);
        int result = FFMPosix.get().nativeClose(nativeFd);
        if (result == -1) {
            return RuntimeIO.handleIOError("close failed: " + FFMPosix.get().strerror(FFMPosix.get().errno()));
        }
        return RuntimeScalarCache.scalarTrue;
    }

    @Override
    public RuntimeScalar flush() {
        // Native fds are unbuffered — no-op
        return RuntimeScalarCache.scalarTrue;
    }

    @Override
    public RuntimeScalar fileno() {
        return new RuntimeScalar(nativeFd);
    }

    @Override
    public RuntimeScalar doRead(int maxBytes, Charset charset) {
        if (closed) {
            return new RuntimeScalar();  // undef
        }
        try {
            byte[] buf = new byte[maxBytes];
            int bytesRead = FFMPosix.get().nativeRead(nativeFd, buf, maxBytes);
            if (bytesRead == -1) {
                return new RuntimeScalar();  // undef on error
            }
            if (bytesRead == 0) {
                eofReached = true;
                return new RuntimeScalar("");  // EOF
            }
            eofReached = false;
            return new RuntimeScalar(new String(buf, 0, bytesRead, StandardCharsets.ISO_8859_1));
        } catch (Exception e) {
            return new RuntimeScalar();  // undef on error
        }
    }

    @Override
    public RuntimeScalar sysread(int length) {
        return doRead(length, StandardCharsets.ISO_8859_1);
    }

    @Override
    public RuntimeScalar syswrite(String data) {
        return write(data);
    }

    @Override
    public RuntimeScalar eof() {
        if (closed) {
            return RuntimeScalarCache.scalarTrue;
        }
        return eofReached ? RuntimeScalarCache.scalarTrue : RuntimeScalarCache.scalarFalse;
    }

    @Override
    public boolean isReadReady() {
        return !closed;
    }

    /**
     * Register this native fd handle in the PerlOnJava I/O system.
     * After registration, {@code open(FH, "+<&=", $fd)} will find this handle.
     *
     * @return a RuntimeIO wrapping this handle, registered at the native fd
     */
    public RuntimeIO registerInIOSystem() {
        // Register in FileDescriptorTable so DupIOHandle/select can find it
        FileDescriptorTable.registerAt(nativeFd, this);

        // Create a RuntimeIO wrapping this handle
        RuntimeIO rio = new RuntimeIO();
        rio.ioHandle = this;
        rio.registerExternalFd(nativeFd);

        return rio;
    }
}
