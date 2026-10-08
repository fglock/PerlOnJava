package org.perlonjava.runtime.io;

import org.perlonjava.runtime.nativ.ffm.FFMPosix;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarCache;
import java.nio.charset.StandardCharsets;

/** A real POSIX socket descriptor exposed through the generic I/O layer. */
public final class NativeSocketIOHandle extends NativeFdIOHandle {
    private final int socketType;

    public NativeSocketIOHandle(int nativeFd, int socketType) {
        super(nativeFd);
        this.socketType = socketType;
    }

    public int socketType() { return socketType; }

    /** Return the packed AF_UNIX address for an unnamed POSIX socketpair peer. */
    public RuntimeScalar getpeername() {
        // POSIX socketpair() creates connected, unnamed AF_UNIX sockets.  The
        // sockaddr family is sufficient here; there is no pathname to append.
        return new RuntimeScalar(new String(new byte[] { 0, 1 }, StandardCharsets.ISO_8859_1));
    }

    @Override
    public RuntimeScalar shutdown(int how) {
        return FFMPosix.get().shutdown(getNativeFd(), how) == 0
                ? RuntimeScalarCache.scalarTrue : RuntimeScalarCache.scalarFalse;
    }
}
