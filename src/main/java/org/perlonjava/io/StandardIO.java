package org.perlonjava.io;

import org.perlonjava.runtime.RuntimeScalar;
import org.perlonjava.runtime.RuntimeScalarCache;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import static org.perlonjava.runtime.RuntimeIO.handleIOException;

public class StandardIO implements IOHandle {
    public static final int STDIN_FILENO = 0;
    public static final int STDOUT_FILENO = 1;
    public static final int STDERR_FILENO = 2;
    private InputStream inputStream;
    private OutputStream outputStream;
    private boolean isEOF;
    private final int fileno;

    public StandardIO(InputStream inputStream) {
        this.inputStream = inputStream;
        this.fileno = STDIN_FILENO;
    }

    public StandardIO(OutputStream outputStream, boolean isStdout) {
        this.outputStream = outputStream;
        this.fileno = isStdout ? STDOUT_FILENO : STDERR_FILENO;
    }

    @Override
    public RuntimeScalar read(byte[] buffer) {
        try {
            if (inputStream != null) {
                int bytesRead = inputStream.read(buffer);
                if (bytesRead == -1) {
                    isEOF = true;
                }
                return new RuntimeScalar(bytesRead);
            }
        } catch (IOException e) {
            return handleIOException(e, "Read operation failed");
        }
        return RuntimeScalarCache.scalarUndef;
    }

    @Override
    public RuntimeScalar write(byte[] data) {
        try {
            if (outputStream != null) {
                outputStream.write(data);
                return RuntimeScalarCache.scalarTrue;
            }
        } catch (IOException e) {
            handleIOException(e, "Write operation failed");
        }
        return RuntimeScalarCache.scalarFalse;
    }

    @Override
    public RuntimeScalar close() {
        // Standard streams should not be closed
        return RuntimeScalarCache.scalarTrue;
    }

    @Override
    public RuntimeScalar eof() {
        return new RuntimeScalar(isEOF);
    }

    @Override
    public long tell() {
        throw new UnsupportedOperationException("Tell operation is not supported for standard streams");
    }

    @Override
    public RuntimeScalar seek(long pos) {
        throw new UnsupportedOperationException("Seek operation is not supported for standard streams");
    }

    @Override
    public RuntimeScalar flush() {
        try {
            if (outputStream != null) {
                outputStream.flush();
                return RuntimeScalarCache.scalarTrue;
            }
        } catch (IOException e) {
            handleIOException(e, "Flush operation failed");
        }
        return RuntimeScalarCache.scalarFalse;
    }

    @Override
    public RuntimeScalar fileno() {
        return new RuntimeScalar(fileno);
    }

    @Override
    public RuntimeScalar bind(String address, int port) {
        throw new UnsupportedOperationException("Bind operation is not supported for standard streams");
    }

    @Override
    public RuntimeScalar connect(String address, int port) {
        throw new UnsupportedOperationException("Connect operation is not supported for standard streams");
    }

    @Override
    public RuntimeScalar listen(int backlog) {
        throw new UnsupportedOperationException("Listen operation is not supported for standard streams");
    }

    @Override
    public RuntimeScalar accept() {
        throw new UnsupportedOperationException("Accept operation is not supported for standard streams");
    }

    public RuntimeScalar getc() {
        try {
            if (inputStream != null) {
                int byteRead = inputStream.read();
                if (byteRead == -1) {
                    isEOF = true;
                    return RuntimeScalarCache.scalarUndef; // Return undefined if EOF is reached
                }
                return new RuntimeScalar(byteRead);
            }
        } catch (IOException e) {
            handleIOException(e, "getc operation failed");
        }
        return RuntimeScalarCache.scalarUndef;
    }

    public RuntimeScalar truncate(long length) {
        throw new UnsupportedOperationException("Truncate operation is not supported.");
    }

}
