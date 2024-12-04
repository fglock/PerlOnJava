package org.perlonjava.io;

import org.perlonjava.runtime.RuntimeScalar;

import java.io.IOException;
import java.io.OutputStream;

import static org.perlonjava.runtime.RuntimeScalarCache.scalarFalse;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarTrue;

public class CustomOutputStreamHandle implements IOHandle {
    private final OutputStream outputStream;

    public CustomOutputStreamHandle(OutputStream outputStream) {
        this.outputStream = outputStream;
    }

    @Override
    public RuntimeScalar write(byte[] data) {
        try {
            outputStream.write(data);
            return scalarTrue; // Indicate success
        } catch (IOException e) {
            return scalarFalse; // Indicate failure
        }
    }

    @Override
    public RuntimeScalar flush() {
        try {
            outputStream.flush();
            return new RuntimeScalar(1); // Indicate success
        } catch (IOException e) {
            return new RuntimeScalar(0); // Indicate failure
        }
    }

    @Override
    public RuntimeScalar close() {
        try {
            outputStream.close();
            return new RuntimeScalar(1); // Indicate success
        } catch (IOException e) {
            return new RuntimeScalar(0); // Indicate failure
        }
    }

    // The following methods are not applicable for an output stream and will throw exceptions
    @Override
    public RuntimeScalar read(byte[] buffer) {
        throw new UnsupportedOperationException("Read operation is not supported");
    }

    @Override
    public RuntimeScalar eof() {
        throw new UnsupportedOperationException("EOF operation is not supported");
    }

    @Override
    public RuntimeScalar tell() {
        throw new UnsupportedOperationException("Tell operation is not supported");
    }

    @Override
    public RuntimeScalar fileno() {
        throw new UnsupportedOperationException("Fileno operation is not supported");
    }

    @Override
    public RuntimeScalar bind(String address, int port) {
        throw new UnsupportedOperationException("Bind operation is not supported");
    }

    @Override
    public RuntimeScalar connect(String address, int port) {
        throw new UnsupportedOperationException("Connect operation is not supported");
    }

    @Override
    public RuntimeScalar listen(int backlog) {
        throw new UnsupportedOperationException("Listen operation is not supported");
    }

    @Override
    public RuntimeScalar accept() {
        throw new UnsupportedOperationException("Accept operation is not supported");
    }

    @Override
    public RuntimeScalar getc() {
        throw new UnsupportedOperationException("Getc operation is not supported");
    }

    @Override
    public RuntimeScalar seek(long pos) {
        throw new UnsupportedOperationException("Seek operation is not supported");
    }

    @Override
    public RuntimeScalar truncate(long length) {
        throw new UnsupportedOperationException("Truncate operation is not supported");
    }
}
