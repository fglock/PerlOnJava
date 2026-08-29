package org.perlonjava.runtime.io;

import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarCache;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

import static org.perlonjava.runtime.runtimetypes.GlobalVariable.getGlobalVariable;

/**
 * IOHandle implementation for reading from a process's InputStream.
 * Used by IPC::Open3 and IPC::Open2 to read from child process stdout/stderr.
 */
public class ProcessInputHandle implements IOHandle {
    @Override
    public ThreadInheritancePolicy threadInheritancePolicy() {
        return ThreadInheritancePolicy.SHARED_TRANSPORT;
    }

    private final InputStream inputStream;
    private final Process process; // may be null; used for EOF detection
    private volatile boolean processExited;
    private final Object readLock = new Object();
    private final ArrayDeque<Byte> buffered = new ArrayDeque<>();
    private boolean isEOF = false;
    private boolean isClosed = false;

    public ProcessInputHandle(InputStream in) {
        this(in, null);
    }

    public ProcessInputHandle(InputStream in, Process process) {
        this.inputStream = in;
        this.process = process;
        if (process != null) {
            process.onExit().thenRun(() -> processExited = true);
        }
        Thread reader = new Thread(this::drainInput, "perlonjava-process-pipe-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void drainInput() {
        byte[] chunk = new byte[8192];
        try {
            for (int count; (count = inputStream.read(chunk)) != -1;) {
                synchronized (readLock) {
                    for (int i = 0; i < count; i++) buffered.addLast(chunk[i]);
                    readLock.notifyAll();
                }
            }
        } catch (IOException ignored) {
        } finally {
            synchronized (readLock) { isEOF = true; readLock.notifyAll(); }
        }
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
                synchronized (readLock) { readLock.notifyAll(); }
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
        RuntimeScalar bytes = sysread(maxBytes);
        return new RuntimeScalar(bytes.toString());
    }

    @Override
    public RuntimeScalar read(int maxBytes) {
        return read(maxBytes, StandardCharsets.ISO_8859_1);
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
        synchronized (readLock) {
            if (isClosed || isEOF || !buffered.isEmpty()) return true;
            // Process lifecycle state is not a pipe EOF signal.  The reader
            // thread alone establishes EOF after it drains the stream.
            return false;
        }
    }

    @Override
    public RuntimeScalar sysread(int length) {
        synchronized (readLock) {
            while (!isClosed && buffered.isEmpty() && !isEOF) {
                try { readLock.wait(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return new RuntimeScalar(); }
            }
            StringBuilder result = new StringBuilder(Math.min(length, buffered.size()));
            while (result.length() < length && !buffered.isEmpty()) {
                result.append((char) (buffered.removeFirst() & 0xFF));
            }
            return new RuntimeScalar(result.toString());
        }
    }
}

