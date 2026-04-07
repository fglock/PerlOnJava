package org.perlonjava.runtime.io;

import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarCache;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.perlonjava.runtime.runtimetypes.GlobalVariable.getGlobalVariable;

/**
 * IOHandle implementation for reading from a process's InputStream.
 * Used by IPC::Open3 and IPC::Open2 to read from child process stdout/stderr.
 */
public class ProcessInputHandle implements IOHandle {

    private final InputStream inputStream;
    private final Process process; // may be null; used for EOF detection
    private boolean isEOF = false;
    private boolean isClosed = false;

    public ProcessInputHandle(InputStream in) {
        this(in, null);
    }

    public ProcessInputHandle(InputStream in, Process process) {
        this.inputStream = in;
        this.process = process;
    }

    @Override
    public RuntimeScalar write(String string) {
        // Input-only handle
        return RuntimeScalarCache.scalarFalse;
    }

    @Override
    public RuntimeScalar close() {
        if (!isClosed) {
            try {
                inputStream.close();
                isClosed = true;
            } catch (IOException e) {
                // Ignore close errors
            }
        }
        return RuntimeScalarCache.scalarTrue;
    }

    @Override
    public RuntimeScalar flush() {
        return RuntimeScalarCache.scalarTrue;
    }

    /**
     * Returns the underlying InputStream for readiness checking by FileDescriptorTable.
     */
    public InputStream getInputStream() {
        return inputStream;
    }

    @Override
    public RuntimeScalar fileno() {
        // Return undef to let RuntimeIO.fileno() lazily assign a registry fileno
        return RuntimeScalarCache.scalarUndef;
    }

    @Override
    public RuntimeScalar eof() {
        if (isClosed) return RuntimeScalarCache.scalarTrue;
        try {
            // Check if stream has data available or is at EOF
            if (isEOF) return RuntimeScalarCache.scalarTrue;
            int available = inputStream.available();
            if (available > 0) return RuntimeScalarCache.scalarFalse;
            
            // Try to peek - if we get -1, it's EOF
            inputStream.mark(1);
            int ch = inputStream.read();
            if (ch == -1) {
                isEOF = true;
                return RuntimeScalarCache.scalarTrue;
            }
            inputStream.reset();
            return RuntimeScalarCache.scalarFalse;
        } catch (IOException e) {
            isEOF = true;
            return RuntimeScalarCache.scalarTrue;
        }
    }

    @Override
    public RuntimeScalar doRead(int maxBytes, Charset charset) {
        if (isClosed || isEOF) {
            return new RuntimeScalar();
        }

        try {
            byte[] buffer = new byte[maxBytes];
            int bytesRead = inputStream.read(buffer, 0, maxBytes);

            if (bytesRead == -1) {
                isEOF = true;
                return new RuntimeScalar();
            }

            // Convert bytes to string using specified charset
            String result = new String(buffer, 0, bytesRead, charset);
            return new RuntimeScalar(result);

        } catch (IOException e) {
            isEOF = true;
            return new RuntimeScalar();
        }
    }

    @Override
    public RuntimeScalar sysread(int length) {
        if (isClosed || isEOF) {
            return new RuntimeScalar();
        }
        try {
            byte[] buffer = new byte[length];
            int bytesRead = inputStream.read(buffer, 0, length);
            if (bytesRead == -1) {
                isEOF = true;
                return new RuntimeScalar();
            }
            return new RuntimeScalar(new String(buffer, 0, bytesRead,
                    StandardCharsets.ISO_8859_1));
        } catch (IOException e) {
            isEOF = true;
            return new RuntimeScalar();
        }
    }

    @Override
    public RuntimeScalar read(int maxBytes) {
        return read(maxBytes, StandardCharsets.ISO_8859_1);
    }

    @Override
    public RuntimeScalar fileno() {
        return RuntimeScalarCache.scalarUndef;
    }

    /**
     * Checks if data is available on this process pipe without blocking.
     * Returns true if bytes are available, the stream is at EOF, or closed.
     * Returns false only if reading would block (no data available yet).
     * <p>
     * This is critical for the 4-arg select() implementation. Without this,
     * select() treats all non-socket handles as "always ready", which causes
     * TAP::Harness parallel mode to hang: the Multiplexer thinks data is
     * available, calls sysread, and blocks because the subprocess hasn't
     * produced output yet.
     */
    @Override
    public boolean isReadReady() {
        if (isClosed || isEOF) return true;
        try {
            if (inputStream.available() > 0) return true;
            // If the process has exited and no buffered data remains,
            // the pipe is at EOF — report ready so the reader gets EOF
            // instead of blocking forever in the select() polling loop.
            if (process != null && !process.isAlive()) {
                isEOF = true;
                return true;
            }
            return false;
        } catch (IOException e) {
            return true; // Error = treat as ready (will get EOF on read)
        }
    }

    @Override
    public RuntimeScalar sysread(int length) {
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

            StringBuilder result = new StringBuilder(bytesRead);
            for (int i = 0; i < bytesRead; i++) {
                result.append((char) (buffer[i] & 0xFF));
            }
            return new RuntimeScalar(result.toString());
        } catch (IOException e) {
            getGlobalVariable("main::!").set(e.getMessage());
            return new RuntimeScalar(); // undef
        }
    }
}
