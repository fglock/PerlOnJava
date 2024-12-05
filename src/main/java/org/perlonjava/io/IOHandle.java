package org.perlonjava.io;

import org.perlonjava.runtime.RuntimeScalar;

public interface IOHandle {

    RuntimeScalar write(byte[] data);

    RuntimeScalar close();

    RuntimeScalar flush();

    default RuntimeScalar getc() {
        throw new UnsupportedOperationException("getc operation is not supported.");
    }

    default RuntimeScalar fileno() {
        throw new UnsupportedOperationException("fileno operation is not supported.");
    }

    default RuntimeScalar eof() {
        throw new UnsupportedOperationException("eof operation is not supported.");
    }

    default RuntimeScalar read(byte[] buffer) {
        throw new UnsupportedOperationException("read operation is not supported.");
    }

    default RuntimeScalar tell() {
        throw new UnsupportedOperationException("tell operation is not supported.");
    }

    // Socket-specific methods
    default RuntimeScalar bind(String address, int port) {
        throw new UnsupportedOperationException("Bind operation is not supported.");
    }

    default RuntimeScalar connect(String address, int port) {
        throw new UnsupportedOperationException("Connect operation is not supported.");
    }

    default RuntimeScalar listen(int backlog) {
        throw new UnsupportedOperationException("Listen operation is not supported.");
    }

    default RuntimeScalar accept() {
        throw new UnsupportedOperationException("Accept operation is not supported.");
    }

    default RuntimeScalar seek(long pos) {
        throw new UnsupportedOperationException("Seek operation is not supported.");
    }

    default RuntimeScalar truncate(long length) {
        throw new UnsupportedOperationException("Truncate operation is not supported.");
    }
}
