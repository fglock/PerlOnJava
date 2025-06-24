package org.perlonjava.io;

import org.perlonjava.runtime.RuntimeIO;
import org.perlonjava.runtime.RuntimeScalar;

public interface IOHandle {

    RuntimeScalar write(String string);

    RuntimeScalar close();

    RuntimeScalar flush();

    default RuntimeScalar getc() {
        return RuntimeIO.handleIOError("getc operation is not supported.");
    }

    default RuntimeScalar fileno() {
        return RuntimeIO.handleIOError("fileno operation is not supported.");
    }

    default RuntimeScalar eof() {
        return RuntimeIO.handleIOError("eof operation is not supported.");
    }

    default RuntimeScalar read(byte[] buffer) {
        return RuntimeIO.handleIOError("read operation is not supported.");
    }

    default RuntimeScalar tell() {
        return RuntimeIO.handleIOError("tell operation is not supported.");
    }

    // Socket-specific methods
    default RuntimeScalar bind(String address, int port) {
        return RuntimeIO.handleIOError("Bind operation is not supported.");
    }

    default RuntimeScalar connect(String address, int port) {
        return RuntimeIO.handleIOError("Connect operation is not supported.");
    }

    default RuntimeScalar listen(int backlog) {
        return RuntimeIO.handleIOError("Listen operation is not supported.");
    }

    default RuntimeScalar accept() {
        return RuntimeIO.handleIOError("Accept operation is not supported.");
    }

    default RuntimeScalar seek(long pos) {
        return RuntimeIO.handleIOError("Seek operation is not supported.");
    }

    default RuntimeScalar truncate(long length) {
        return RuntimeIO.handleIOError("Truncate operation is not supported.");
    }
}
