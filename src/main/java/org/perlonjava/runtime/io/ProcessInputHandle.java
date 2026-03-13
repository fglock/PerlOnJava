package org.perlonjava.runtime.io;

import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarCache;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * IOHandle implementation for reading from a process's InputStream.
 * Used by IPC::Open3 and IPC::Open2 to read from child process stdout/stderr.
 */
public class ProcessInputHandle implements IOHandle {

    private final InputStream inputStream;
    private boolean isEOF = false;
    private boolean isClosed = false;

    public ProcessInputHandle(InputStream in) {
        this.inputStream = in;
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
    public RuntimeScalar read(int maxBytes) {
        return read(maxBytes, StandardCharsets.ISO_8859_1);
    }
}
