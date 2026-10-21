package org.perlonjava.runtime.io;

import org.perlonjava.runtime.nativ.ffm.FFMPosix;
import org.perlonjava.runtime.nativ.ffm.FFMUnixDatagram;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarCache;
import java.nio.charset.StandardCharsets;

/** A real POSIX socket descriptor exposed through the generic I/O layer. */
public final class NativeSocketIOHandle extends NativeFdIOHandle {
    private static final class UnixDatagramHolder {
        private static final FFMUnixDatagram FFM = new FFMUnixDatagram();
    }

    private final int socketType;
    private volatile String localUnixPath;
    private volatile String peerUnixPath;

    public NativeSocketIOHandle(int nativeFd, int socketType) {
        super(nativeFd);
        this.socketType = socketType;
    }

    public int socketType() { return socketType; }

    public static NativeSocketIOHandle openUnixDatagram() {
        int fd = UnixDatagramHolder.FFM.open(false);
        if (fd < 0) {
            throw new IllegalStateException("socket(AF_UNIX, SOCK_DGRAM) failed: "
                    + FFMPosix.get().strerror(UnixDatagramHolder.FFM.errno()));
        }
        return new NativeSocketIOHandle(fd, org.perlonjava.runtime.perlmodule.Socket.SOCK_DGRAM);
    }

    public boolean bindUnix(String path) {
        boolean bound = unixResult(UnixDatagramHolder.FFM.bind(getNativeFd(), path), "bind");
        if (bound) localUnixPath = path;
        return bound;
    }

    public boolean connectUnix(String path) {
        boolean connected = unixResult(UnixDatagramHolder.FFM.connect(getNativeFd(), path), "connect");
        if (connected) peerUnixPath = path;
        return connected;
    }

    public RuntimeScalar getsockname() { return packedUnixAddress(localUnixPath); }

    private static RuntimeScalar packedUnixAddress(String path) {
        if (path == null) return new RuntimeScalar(new String(new byte[] { 0, 1 }, StandardCharsets.ISO_8859_1));
        byte[] pathBytes = path.getBytes(StandardCharsets.ISO_8859_1);
        byte[] packed = new byte[pathBytes.length + 3];
        packed[1] = 1;
        System.arraycopy(pathBytes, 0, packed, 2, pathBytes.length);
        return new RuntimeScalar(new String(packed, StandardCharsets.ISO_8859_1));
    }

    public int sendUnix(byte[] payload, String path, int flags) {
        int sent = UnixDatagramHolder.FFM.sendTo(getNativeFd(), payload, path, flags);
        if (sent < 0) {
            org.perlonjava.runtime.runtimetypes.RuntimeIO.handleIOError(
                    FFMPosix.get().strerror(UnixDatagramHolder.FFM.errno()));
        }
        return sent;
    }

    public FFMUnixDatagram.Datagram receiveUnix(int length, int flags) {
        FFMUnixDatagram.Datagram datagram = UnixDatagramHolder.FFM.receive(getNativeFd(), length, flags);
        if (datagram == null) {
            org.perlonjava.runtime.runtimetypes.RuntimeIO.handleIOError(
                    FFMPosix.get().strerror(UnixDatagramHolder.FFM.errno()));
        }
        return datagram;
    }

    private boolean unixResult(int result, String operation) {
        if (result == 0) return true;
        org.perlonjava.runtime.runtimetypes.RuntimeIO.handleIOError(operation + " failed: "
                + FFMPosix.get().strerror(UnixDatagramHolder.FFM.errno()));
        return false;
    }

    /** Return the packed AF_UNIX address for the peer or unnamed socketpair peer. */
    public RuntimeScalar getpeername() {
        if (socketType == org.perlonjava.runtime.perlmodule.Socket.SOCK_DGRAM) {
            return packedUnixAddress(peerUnixPath);
        }
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
