package org.perlonjava.io;

import org.perlonjava.runtime.RuntimeIO;
import org.perlonjava.runtime.RuntimeScalar;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public interface IOHandle {

    int SEEK_SET = 0;  // Seek from beginning of file
    int SEEK_CUR = 1;  // Seek from current position
    int SEEK_END = 2;  // Seek from end of file
    
    RuntimeScalar write(String string);

    RuntimeScalar close();

    RuntimeScalar flush();

    default RuntimeScalar fileno() {
        return RuntimeIO.handleIOError("fileno operation is not supported.");
    }

    default RuntimeScalar eof() {
        return RuntimeIO.handleIOError("eof operation is not supported.");
    }

    default RuntimeScalar read(int maxBytes) {
        return read(maxBytes, StandardCharsets.ISO_8859_1);
    }

    default RuntimeScalar read(int maxBytes, Charset charset) {
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

    default RuntimeScalar seek(long pos, int whence) {
        return RuntimeIO.handleIOError("Seek operation is not supported.");
    }

    default RuntimeScalar seek(long pos) {
        return seek(pos, IOHandle.SEEK_SET);
    }

    default RuntimeScalar truncate(long length) {
        return RuntimeIO.handleIOError("Truncate operation is not supported.");
    }
}
