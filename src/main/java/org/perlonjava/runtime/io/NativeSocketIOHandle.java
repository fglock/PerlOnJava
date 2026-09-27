package org.perlonjava.runtime.io;

import org.perlonjava.runtime.nativ.ffm.FFMPosix;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarCache;

/** A real POSIX socket descriptor exposed through the generic I/O layer. */
public final class NativeSocketIOHandle extends NativeFdIOHandle {
    private final int socketType;

    public NativeSocketIOHandle(int nativeFd, int socketType) {
        super(nativeFd);
        this.socketType = socketType;
    }

    public int socketType() { return socketType; }

    @Override
    public RuntimeScalar shutdown(int how) {
        return FFMPosix.get().shutdown(getNativeFd(), how) == 0
                ? RuntimeScalarCache.scalarTrue : RuntimeScalarCache.scalarFalse;
    }
}
