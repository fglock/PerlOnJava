package org.perlonjava.runtime.io;

import org.perlonjava.runtime.runtimetypes.PerlSignalQueue;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarCache;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.perlonjava.runtime.runtimetypes.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.runtimetypes.RuntimeIO.handleIOException;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.*;

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
    private boolean blocking = true;

    // Shared between reader and writer ends so the reader can detect
    // that the writer has been closed (EOF).  PipedInputStream.available()
    // returns 0 for both "no data yet" and "writer closed", so we need
    // this extra signal to distinguish the two cases.
    private final AtomicBoolean writerClosed;

    private InternalPipeHandle(PipedInputStream inputStream, PipedOutputStream outputStream,
                               boolean isReader, AtomicBoolean writerClosed) {
        this.inputStream = inputStream;
        this.outputStream = outputStream;
        this.isReader = isReader;
        this.writerClosed = writerClosed;
    }

    /**
     * Creates a connected reader/writer pair of internal pipe handles.
     * Both ends share a writerClosed flag so the reader can detect EOF.
     */
    public static InternalPipeHandle[] createPair(PipedInputStream inputStream, PipedOutputStream outputStream) {
        AtomicBoolean shared = new AtomicBoolean(false);
        return new InternalPipeHandle[]{
                new InternalPipeHandle(inputStream, null, true, shared),
                new InternalPipeHandle(null, outputStream, false, shared),
        };
    }

    /**
     * Creates a reader end of an internal pipe (legacy, without shared flag).
     */
    public static InternalPipeHandle createReader(PipedInputStream inputStream) {
        return new InternalPipeHandle(inputStream, null, true, new AtomicBoolean(false));
    }

    /**
     * Creates a writer end of an internal pipe (legacy, without shared flag).
     */
    public static InternalPipeHandle createWriter(PipedOutputStream outputStream) {
        return new InternalPipeHandle(null, outputStream, false, new AtomicBoolean(false));
    }

    /**
     * Returns whether this pipe handle is in blocking mode.
     */
    public boolean isBlocking() {
        return blocking;
    }

    /**
     * Sets the blocking mode for this pipe handle.
     */
    public void setBlocking(boolean blocking) {
        this.blocking = blocking;
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
            // Always use polling for pipe reads to allow signal interruption
            // PipedInputStream.read() uses Object.wait() which doesn't respond well to Thread.interrupt()
            while (true) {
                // Check for interrupt/signal first
                if (Thread.interrupted()) {
                    PerlSignalQueue.checkPendingSignals();
                    return new RuntimeScalar("");
                }

                // Check if data is available
                int available = inputStream.available();
                if (available > 0) {
                    byte[] buffer = new byte[Math.min(maxBytes, available)];
                    int bytesRead = inputStream.read(buffer, 0, buffer.length);

                    if (bytesRead == -1) {
                        isEOF = true;
                        return new RuntimeScalar("");
                    }

                    String result = new String(buffer, 0, bytesRead, charset);
                    return new RuntimeScalar(result);
                }

                // No data available. Check if writer has closed (EOF).
                if (writerClosed.get()) {
                    isEOF = true;
                    return new RuntimeScalar("");
                }

                // No data available - short sleep to avoid busy-wait
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    // Interrupted by alarm - process the signal
                    PerlSignalQueue.checkPendingSignals();
                    return new RuntimeScalar("");
                }
            }
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
                writerClosed.set(true);  // Signal EOF to the reader end
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
    public RuntimeScalar syswrite(String data) {
        if (isReader) {
            getGlobalVariable("main::!").set("Cannot syswrite to read end of pipe");
            return new RuntimeScalar(); // undef
        }

        if (isClosed) {
            getGlobalVariable("main::!").set("Cannot syswrite to closed pipe");
            return new RuntimeScalar(); // undef
        }

        try {
            byte[] bytes = data.getBytes(StandardCharsets.ISO_8859_1);
            outputStream.write(bytes);
            outputStream.flush();
            return new RuntimeScalar(bytes.length);
        } catch (IOException e) {
            getGlobalVariable("main::!").set(e.getMessage());
            return new RuntimeScalar(); // undef
        }
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
            // Always use polling for pipe reads to allow signal interruption
            while (true) {
                if (Thread.interrupted()) {
                    PerlSignalQueue.checkPendingSignals();
                    return new RuntimeScalar("");
                }

                int available = inputStream.available();
                if (available > 0) {
                    byte[] buffer = new byte[Math.min(length, available)];
                    int bytesRead = inputStream.read(buffer);

                    if (bytesRead == -1) {
                        isEOF = true;
                        return new RuntimeScalar("");
                    }

                    StringBuilder result = new StringBuilder(bytesRead);
                    for (int i = 0; i < bytesRead; i++) {
                        result.append((char) (buffer[i] & 0xFF));
                    }
                    return new RuntimeScalar(result.toString());
                }

                // No data available. Check if writer has closed (EOF).
                if (writerClosed.get()) {
                    isEOF = true;
                    return new RuntimeScalar("");  // EOF = 0 bytes read
                }

                // Non-blocking mode: return undef with EAGAIN when no data available
                if (!blocking) {
                    getGlobalVariable("main::!").set(
                            org.perlonjava.runtime.runtimetypes.ErrnoVariable.EAGAIN());
                    return new RuntimeScalar(); // undef
                }

                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    PerlSignalQueue.checkPendingSignals();
                    return new RuntimeScalar("");
                }
            }
        } catch (IOException e) {
            isEOF = true;
            getGlobalVariable("main::!").set(e.getMessage());
            return new RuntimeScalar(); // undef
        }
    }

    /**
     * Checks if this pipe has data available for reading without blocking.
     * Used by {@link FileDescriptorTable#isReadReady(IOHandle)} to implement
     * select() readiness checking for pipe handles.
     *
     * @return true if data is available, the writer has closed (EOF pending),
     *         or the pipe is already at EOF
     */
    public boolean hasDataAvailable() {
        if (isClosed || !isReader) return false;
        if (isEOF) return false;
        try {
            if (inputStream.available() > 0) return true;
            // Writer closed with no data remaining = EOF is pending.
            // select() should report this as readable (read will return 0 = EOF).
            return writerClosed.get();
        } catch (IOException e) {
            return false;
        }
    }
}
