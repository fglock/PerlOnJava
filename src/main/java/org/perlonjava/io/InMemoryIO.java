package org.perlonjava.io;

import org.perlonjava.runtime.RuntimeScalar;
import org.perlonjava.runtime.RuntimeScalarCache;

import java.io.*;

import static org.perlonjava.runtime.RuntimeIO.handleIOException;

public class InMemoryIO implements IOHandle {
    private ByteArrayOutputStream byteArrayOutputStream;
    private ByteArrayInputStream byteArrayInputStream;
    private boolean isEOF;

    public InMemoryIO() {
        this.byteArrayOutputStream = new ByteArrayOutputStream();
    }

    public void setInput(byte[] input) {
        this.byteArrayInputStream = new ByteArrayInputStream(input);
        this.isEOF = false;
    }

    @Override
    public RuntimeScalar read(byte[] buffer) {
        try {
            if (byteArrayInputStream != null) {
                int bytesRead = byteArrayInputStream.read(buffer);
                if (bytesRead == -1) {
                    isEOF = true;
                }
                return new RuntimeScalar(bytesRead);
            }
        } catch (IOException e) {
            handleIOException(e, "Read operation failed");
        }
        return RuntimeScalarCache.scalarUndef;
    }

    @Override
    public RuntimeScalar write(byte[] data) {
        try {
            if (byteArrayOutputStream != null) {
                byteArrayOutputStream.write(data);
                return RuntimeScalarCache.scalarTrue;
            }
        } catch (IOException e) {
            handleIOException(e, "Write operation failed");
        }
        return RuntimeScalarCache.scalarFalse;
    }

    @Override
    public RuntimeScalar close() {
        try {
            if (byteArrayOutputStream != null) {
                byteArrayOutputStream.flush();
            }
        } catch (IOException e) {
            handleIOException(e, "Flush operation failed");
        }
        return RuntimeScalarCache.scalarTrue;
    }

    @Override
    public RuntimeScalar eof() {
        return new RuntimeScalar(isEOF);
    }

    @Override
    public long tell() {
        throw new UnsupportedOperationException("Tell operation is not supported for in-memory streams");
    }

    @Override
    public void seek(long pos) {
        throw new UnsupportedOperationException("Seek operation is not supported for in-memory streams");
    }

    @Override
    public RuntimeScalar flush() {
        try {
            if (byteArrayOutputStream != null) {
                byteArrayOutputStream.flush();
                return RuntimeScalarCache.scalarTrue;
            }
        } catch (IOException e) {
            handleIOException(e, "Flush operation failed");
        }
        return RuntimeScalarCache.scalarFalse;
    }

    public byte[] getOutput() {
        return byteArrayOutputStream.toByteArray();
    }

    @Override
    public RuntimeScalar fileno() {
        // Return a placeholder value as in-memory streams do not have a file descriptor
        return new RuntimeScalar(-1);
    }

    @Override
    public RuntimeScalar bind(String address, int port) {
        throw new UnsupportedOperationException("Bind operation is not supported for in-memory streams");
    }

    @Override
    public RuntimeScalar connect(String address, int port) {
        throw new UnsupportedOperationException("Connect operation is not supported for in-memory streams");
    }

    @Override
    public RuntimeScalar listen(int backlog) {
        throw new UnsupportedOperationException("Listen operation is not supported for in-memory streams");
    }

    @Override
    public RuntimeScalar accept() {
        throw new UnsupportedOperationException("Accept operation is not supported for in-memory streams");
    }

    public RuntimeScalar getc() {
        if (byteArrayInputStream != null) {
            int byteRead = byteArrayInputStream.read();
            if (byteRead == -1) {
                isEOF = true;
                return RuntimeScalarCache.scalarUndef; // or any representation of EOF
            }
            return new RuntimeScalar(byteRead);
        }
        return RuntimeScalarCache.scalarUndef;
    }
}
