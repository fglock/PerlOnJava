package org.perlonjava.runtime.io;

import org.perlonjava.runtime.runtimetypes.ErrnoVariable;
import org.perlonjava.runtime.runtimetypes.PerlSignalQueue;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarCache;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

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
    private final int fd;  // Simulated file descriptor number
    private boolean blocking = true;  // Default: blocking mode
    // Reference to the connected input stream (for writer to check buffer capacity)
    private PipedInputStream connectedInput;
    // Shared state between reader and writer of the same pipe pair
    private volatile boolean[] writerClosedFlag;  // shared array[0] = writer closed
    // Pipe buffer size used during creation
    public static final int PIPE_BUFFER_SIZE = 65536;  // 64KB, similar to typical OS pipe buffers

    /**
     * Returns the file descriptor number assigned by FileDescriptorTable.
     */
    public int getFd() {
        return fd;
    }

    private InternalPipeHandle(PipedInputStream inputStream, PipedOutputStream outputStream, boolean isReader) {
        this.inputStream = inputStream;
        this.outputStream = outputStream;
        this.isReader = isReader;
        this.fd = FileDescriptorTable.register(this);
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

    /**
     * Creates a connected reader/writer pair with shared state.
     * The writer gets a reference to the connected input stream for non-blocking writes,
     * and both ends share a writerClosed flag so the reader can detect EOF without blocking.
     */
    public static InternalPipeHandle[] createPair(PipedInputStream pipeIn, PipedOutputStream pipeOut) {
        InternalPipeHandle reader = new InternalPipeHandle(pipeIn, null, true);
        InternalPipeHandle writer = new InternalPipeHandle(null, pipeOut, false);
        writer.connectedInput = pipeIn;
        // Share a mutable boolean flag between reader and writer
        boolean[] sharedFlag = new boolean[]{false};
        reader.writerClosedFlag = sharedFlag;
        writer.writerClosedFlag = sharedFlag;
        return new InternalPipeHandle[]{reader, writer};
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
            // Blocking mode: use read() directly which handles EOF properly
            byte[] buffer = new byte[maxBytes];
            int bytesRead = inputStream.read(buffer, 0, buffer.length);

            if (bytesRead == -1) {
                isEOF = true;
                return new RuntimeScalar("");
            }

            String result = new String(buffer, 0, bytesRead, charset);
            return new RuntimeScalar(result);
        } catch (IOException e) {
            // "Pipe broken" or "Pipe closed" means writer closed - treat as EOF
            String msg = e.getMessage();
            if (msg != null && (msg.contains("Pipe broken") || msg.contains("Pipe closed"))) {
                isEOF = true;
                return new RuntimeScalar("");
            }
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
                // Signal the reader that the writer has closed
                if (writerClosedFlag != null) {
                    writerClosedFlag[0] = true;
                }
            }
            isClosed = true;
            isEOF = true;
            FileDescriptorTable.unregister(fd);
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
        return new RuntimeScalar(fd);
    }

    /**
     * Check if this pipe handle has data available for reading without blocking.
     * Used by the 4-arg select() implementation.
     *
     * @return true if data is available, the pipe is at EOF, or this is a write-end pipe
     */
    public boolean hasDataAvailable() {
        if (!isReader) {
            return false; // Write end is not "read-ready"
        }
        if (isClosed || isEOF) {
            return true; // EOF/closed counts as "ready" (read returns 0/empty)
        }
        try {
            return inputStream.available() > 0;
        } catch (IOException e) {
            return true; // Error counts as ready (will be detected on actual read)
        }
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

            // Non-blocking mode: check if the pipe has room before writing
            if (!blocking && connectedInput != null) {
                int available = connectedInput.available();
                int freeSpace = PIPE_BUFFER_SIZE - available;
                if (freeSpace <= 0) {
                    // Buffer is full — return EAGAIN
                    getGlobalVariable("main::!").set(new RuntimeScalar(ErrnoVariable.EAGAIN()));
                    return new RuntimeScalar(); // undef
                }
                // Only write what fits in the buffer
                int toWrite = Math.min(bytes.length, freeSpace);
                outputStream.write(bytes, 0, toWrite);
                outputStream.flush();
                return new RuntimeScalar(toWrite);
            }

            outputStream.write(bytes);
            outputStream.flush();
            return new RuntimeScalar(bytes.length);
        } catch (IOException e) {
            getGlobalVariable("main::!").set(e.getMessage());
            return new RuntimeScalar(); // undef
        }
    }

    @Override
    public boolean isBlocking() {
        return blocking;
    }

    @Override
    public boolean setBlocking(boolean blocking) {
        this.blocking = blocking;
        return true;
    }

    @Override
    public RuntimeScalar sysread(int length) {
        if (!isReader) {
            getGlobalVariable("main::!").set(new RuntimeScalar(ErrnoVariable.EBADF()));
            return new RuntimeScalar(); // undef
        }

        if (isClosed || isEOF) {
            return new RuntimeScalar("");
        }

        try {
            // Non-blocking mode: return immediately if no data available
            if (!blocking) {
                int available = inputStream.available();
                if (available <= 0) {
                    // Check if writer has closed — if so, it's EOF, not EAGAIN
                    if (writerClosedFlag != null && writerClosedFlag[0]) {
                        // Writer closed and no data left — try read to confirm EOF
                        byte[] buf = new byte[1];
                        int n = inputStream.read(buf);
                        if (n == -1) {
                            isEOF = true;
                            return new RuntimeScalar("");
                        }
                        // Got a byte (race condition: data arrived just before check)
                        return new RuntimeScalar(String.valueOf((char) (buf[0] & 0xFF)));
                    }
                    // Writer still open, no data — return EAGAIN
                    getGlobalVariable("main::!").set(new RuntimeScalar(ErrnoVariable.EAGAIN()));
                    return new RuntimeScalar(); // undef
                }
                // Data available - read it
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

            // Blocking mode: use read() directly which will block until data or EOF
            byte[] buffer = new byte[length];
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
        } catch (IOException e) {
            // "Pipe broken" or "Pipe closed" means writer closed - treat as EOF
            String msg = e.getMessage();
            if (msg != null && (msg.contains("Pipe broken") || msg.contains("Pipe closed"))) {
                isEOF = true;
                return new RuntimeScalar("");
            }
            isEOF = true;
            getGlobalVariable("main::!").set(e.getMessage());
            return new RuntimeScalar(); // undef
        }
    }
}
