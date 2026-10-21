package org.perlonjava.runtime.nativ.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

/** Native AF_UNIX datagram operations, kept separate from the general POSIX API. */
public final class FFMUnixDatagram {
    public record Datagram(byte[] payload, String senderPath) { }

    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");
    private static final int AF_UNIX = 1;
    private static final int SOCK_DGRAM = 2;
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIBC = LINKER.defaultLookup();
    private static final Linker.Option CAPTURE_ERRNO = Linker.Option.captureCallState("errno");
    private static final long ERRNO_OFFSET = Linker.Option.captureStateLayout()
            .byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("errno"));

    private final MethodHandle socket;
    private final MethodHandle bind;
    private final MethodHandle connect;
    private final MethodHandle sendto;
    private final MethodHandle recvfrom;
    private final MethodHandle close;
    private volatile int errno;

    public FFMUnixDatagram() {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            throw new UnsupportedOperationException("Unix-domain datagram sockets are unavailable on Windows");
        }
        socket = downcall("socket", FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        bind = downcall("bind", FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        connect = downcall("connect", FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        sendto = downcall("sendto", FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        recvfrom = downcall("recvfrom", FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        close = downcall("close", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    }

    private static MethodHandle downcall(String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(LIBC.find(name).orElseThrow(), descriptor, CAPTURE_ERRNO);
    }

    private void capture(MemorySegment state) {
        errno = state.get(ValueLayout.JAVA_INT, ERRNO_OFFSET);
    }

    public int errno() { return errno; }

    public int open(boolean nonBlocking) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(Linker.Option.captureStateLayout());
            // Keep the socket type portable. SOCK_CLOEXEC/SOCK_NONBLOCK are
            // not defined by all supported libc headers (notably Darwin).
            int fd = (int) socket.invokeExact(state, AF_UNIX, SOCK_DGRAM, 0);
            if (fd < 0) capture(state);
            return fd;
        } catch (Throwable e) {
            errno = 97; // EAFNOSUPPORT
            return -1;
        }
    }

    public int bind(int fd, String path) { return addressCall(bind, fd, path); }
    public int connect(int fd, String path) { return addressCall(connect, fd, path); }

    private int addressCall(MethodHandle handle, int fd, String path) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment address = sockaddr(arena, path);
            MemorySegment state = arena.allocate(Linker.Option.captureStateLayout());
            int result = (int) handle.invokeExact(state, fd, address, (int) address.byteSize());
            if (result != 0) capture(state);
            return result;
        } catch (Throwable e) {
            errno = 22; // EINVAL
            return -1;
        }
    }

    public int sendTo(int fd, byte[] payload, String path, int flags) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bytes = arena.allocateFrom(ValueLayout.JAVA_BYTE, payload);
            MemorySegment address = path == null ? MemorySegment.NULL : sockaddr(arena, path);
            int addressLength = path == null ? 0 : (int) address.byteSize();
            MemorySegment state = arena.allocate(Linker.Option.captureStateLayout());
            long sent = (long) sendto.invokeExact(state, fd, bytes, (long) payload.length,
                    flags, address, addressLength);
            if (sent < 0) capture(state);
            return (int) sent;
        } catch (Throwable e) {
            errno = 5; // EIO
            return -1;
        }
    }

    public Datagram receive(int fd, int maxLength, int flags) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bytes = arena.allocate(Math.max(1, maxLength));
            MemorySegment address = arena.allocate(110);
            MemorySegment addressLength = arena.allocate(ValueLayout.JAVA_INT);
            addressLength.set(ValueLayout.JAVA_INT, 0, 110);
            MemorySegment state = arena.allocate(Linker.Option.captureStateLayout());
            long received = (long) recvfrom.invokeExact(state, fd, bytes, (long) maxLength,
                    flags, address, addressLength);
            if (received < 0) {
                capture(state);
                return null;
            }
            int pathOffset = 2;
            int addressSize = addressLength.get(ValueLayout.JAVA_INT, 0);
            int pathLength = Math.max(0, Math.min(addressSize - pathOffset, 108));
            byte[] path = new byte[pathLength];
            MemorySegment.copy(address, ValueLayout.JAVA_BYTE, pathOffset, path, 0, pathLength);
            int nul = 0;
            while (nul < path.length && path[nul] != 0) nul++;
            byte[] data = new byte[(int) received];
            MemorySegment.copy(bytes, ValueLayout.JAVA_BYTE, 0, data, 0, data.length);
            return new Datagram(data, new String(path, 0, nul, StandardCharsets.UTF_8));
        } catch (Throwable e) {
            errno = 5;
            return null;
        }
    }

    public int close(int fd) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(Linker.Option.captureStateLayout());
            int result = (int) close.invokeExact(state, fd);
            if (result != 0) capture(state);
            return result;
        } catch (Throwable e) {
            errno = 9; // EBADF
            return -1;
        }
    }

    private static MemorySegment sockaddr(Arena arena, String path) {
        byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
        int maxPath = MAC ? 104 : 108;
        if (pathBytes.length == 0 || pathBytes.length >= maxPath || path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid Unix socket path");
        }
        int length = 2 + pathBytes.length + 1;
        MemorySegment address = arena.allocate(MAC ? 106 : 110);
        if (MAC) {
            address.set(ValueLayout.JAVA_BYTE, 0, (byte) length); // sun_len
            address.set(ValueLayout.JAVA_BYTE, 1, (byte) AF_UNIX); // sun_family
        } else {
            address.set(ValueLayout.JAVA_SHORT, 0, (short) AF_UNIX); // native-endian family
        }
        MemorySegment.copy(pathBytes, 0, address, ValueLayout.JAVA_BYTE, 2, pathBytes.length);
        address.set(ValueLayout.JAVA_BYTE, 2L + pathBytes.length, (byte) 0);
        return address.asSlice(0, length);
    }
}
