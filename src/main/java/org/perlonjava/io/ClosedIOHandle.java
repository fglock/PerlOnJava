package org.perlonjava.io;

import org.perlonjava.runtime.RuntimeIO;
import org.perlonjava.runtime.RuntimeScalar;

public class ClosedIOHandle implements IOHandle {

    @Override
    public RuntimeScalar write(String string) {
        return RuntimeIO.handleIOError("Cannot write to a closed handle.");
    }

    @Override
    public RuntimeScalar close() {
        return RuntimeIO.handleIOError("Handle is already closed.");
    }

    @Override
    public RuntimeScalar flush() {
        return RuntimeIO.handleIOError("Cannot flush a closed handle.");
    }

    @Override
    public RuntimeScalar fileno() {
        return RuntimeIO.handleIOError("Cannot get file number from a closed handle.");
    }

    @Override
    public RuntimeScalar eof() {
        return RuntimeIO.handleIOError("Cannot check EOF on a closed handle.");
    }

    @Override
    public RuntimeScalar read(int maxBytes, java.nio.charset.Charset charset) {
        return RuntimeIO.handleIOError("Cannot read from a closed handle.");
    }

    @Override
    public RuntimeScalar tell() {
        return RuntimeIO.handleIOError("Cannot tell position on a closed handle.");
    }

    @Override
    public RuntimeScalar bind(String address, int port) {
        return RuntimeIO.handleIOError("Cannot bind a closed handle.");
    }

    @Override
    public RuntimeScalar connect(String address, int port) {
        return RuntimeIO.handleIOError("Cannot connect a closed handle.");
    }

    @Override
    public RuntimeScalar listen(int backlog) {
        return RuntimeIO.handleIOError("Cannot listen on a closed handle.");
    }

    @Override
    public RuntimeScalar accept() {
        return RuntimeIO.handleIOError("Cannot accept connections on a closed handle.");
    }

    @Override
    public RuntimeScalar seek(long pos) {
        return RuntimeIO.handleIOError("Cannot seek on a closed handle.");
    }

    @Override
    public RuntimeScalar truncate(long length) {
        return RuntimeIO.handleIOError("Cannot truncate a closed handle.");
    }

    @Override
    public RuntimeScalar sysread(int length) {
        return RuntimeIO.handleIOError("Cannot sysread from a closed handle.");
    }

    @Override
    public RuntimeScalar syswrite(String data) {
        return RuntimeIO.handleIOError("Cannot syswrite to a closed handle.");
    }
}
