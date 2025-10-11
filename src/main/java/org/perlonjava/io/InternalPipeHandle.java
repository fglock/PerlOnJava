package org.perlonjava.io;

import org.perlonjava.runtime.RuntimeScalar;
import org.perlonjava.runtime.RuntimeScalarCache;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.RuntimeIO.handleIOException;
import static org.perlonjava.runtime.RuntimeScalarCache.*;

/**
 * Internal pipe handle for implementing the pipe() operator.
 * This creates a pair of connected pipes for inter-thread communication.
 */
public class InternalPipeHandle implements IOHandle {

    private final PipedInputStream inputStream;
    private final PipedOutputStream outputStream;
    private final boolean isReader;
    private boolean isClosed = false;
    private boolean isEOF = false;

    private InternalPipeHandle(PipedInputStream inputStream, PipedOutputStream outputStream, boolean isReader) {
        this.inputStream = inputStream;
        this.outputStream = outputStream;
        this.isReader = isReader;
    }

    /**
     * Creates a reader end of an internal pipe.
     */
    public static InternalPipeHandle createReader(PipedInputStream inputStream) {
        return new InternalPipeHandle(inputStream, null, true);
    }

    /**
     * Creates a writer end of an internal pipe.
     */
    public static InternalPipeHandle createWriter(PipedOutputStream outputStream) {
        return new InternalPipeHandle(null, outputStream, false);
    }

    @Override
    public RuntimeScalar doRead(int maxBytes, Charset charset) {
        if (!isReader) {
            return handleIOException(new IOException("Cannot read from write end of pipe"), "read from pipe writer failed");
        }

        if (isClosed || isEOF) {
            return new RuntimeScalar("");
        }

        try {
            byte[] buffer = new byte[maxBytes];
            int bytesRead = inputStream.read(buffer, 0, maxBytes);

            if (bytesRead == -1) {
                isEOF = true;
                return new RuntimeScalar("");
            }

            // Convert bytes to string using the specified charset
            String result = new String(buffer, 0, bytesRead, charset);
            return new RuntimeScalar(result);
        } catch (IOException e) {
            isEOF = true;
            return handleIOException(e, "Read from pipe failed");
        }
    }

    @Override
    public RuntimeScalar write(String string) {
        if (isReader) {
            return handleIOException(new IOException("Cannot write to read end of pipe"), "write to pipe reader failed");
        }

        if (isClosed) {
            return handleIOException(new IOException("Cannot write to closed pipe"), "write to closed pipe failed");
        }

        try {
            byte[] bytes = string.getBytes(StandardCharsets.UTF_8);
            outputStream.write(bytes);
            outputStream.flush();
            return scalarTrue;
        } catch (IOException e) {
            return handleIOException(e, "Write to pipe failed");
        }
    }

    @Override
    public RuntimeScalar close() {
        if (isClosed) {
            return scalarTrue;
        }

        try {
            if (isReader && inputStream != null) {
                inputStream.close();
            } else if (!isReader && outputStream != null) {
                outputStream.close();
            }
            isClosed = true;
            isEOF = true;
            return scalarTrue;
        } catch (IOException e) {
            return handleIOException(e, "Close pipe failed");
        }
    }

    @Override
    public RuntimeScalar eof() {
        return new RuntimeScalar(isEOF);
    }

    @Override
    public RuntimeScalar tell() {
        return getScalarInt(-1); // Pipes don't support tell
    }

    @Override
    public RuntimeScalar seek(long pos, int whence) {
        return handleIOException(new IOException("Cannot seek on pipe"), "seek on pipe failed");
    }

    @Override
    public RuntimeScalar flush() {
        if (isReader) {
            return scalarTrue; // Flush is no-op for readers
        }

        if (isClosed) {
            return scalarFalse;
        }

        try {
            if (outputStream != null) {
                outputStream.flush();
            }
            return scalarTrue;
        } catch (IOException e) {
            return handleIOException(e, "Flush pipe failed");
        }
    }

    @Override
    public RuntimeScalar fileno() {
        return RuntimeScalarCache.scalarUndef; // Internal pipes don't have file descriptors
    }

    @Override
    public RuntimeScalar truncate(long length) {
        return handleIOException(new IOException("Cannot truncate pipe"), "truncate pipe failed");
    }

    @Override
    public RuntimeScalar sysread(int length) {
        if (!isReader) {
            getGlobalVariable("main::!").set("Cannot sysread from write end of pipe");
            return new RuntimeScalar(); // undef
        }

        if (isClosed || isEOF) {
            return new RuntimeScalar("");
        }

        try {
            byte[] buffer = new byte[length];
            int bytesRead = inputStream.read(buffer);

            if (bytesRead == -1) {
                isEOF = true;
                return new RuntimeScalar("");
            }

            // Convert bytes to string representation
            StringBuilder result = new StringBuilder(bytesRead);
            for (int i = 0; i < bytesRead; i++) {
                result.append((char) (buffer[i] & 0xFF));
            }

            return new RuntimeScalar(result.toString());
        } catch (IOException e) {
            isEOF = true;
            getGlobalVariable("main::!").set(e.getMessage());
            return new RuntimeScalar(); // undef
        }
    }
}
