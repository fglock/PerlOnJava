package org.perlonjava.runtime.io;

import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarCache;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * IOHandle implementation for writing to a process's OutputStream.
 * Used by IPC::Open3 and IPC::Open2 to write to child process stdin.
 */
public class ProcessOutputHandle implements IOHandle {

    private final OutputStream outputStream;
    private boolean isClosed = false;
    private Charset charset = StandardCharsets.ISO_8859_1;

    public ProcessOutputHandle(OutputStream out) {
        this.outputStream = out;
    }

    @Override
    public RuntimeScalar write(String string) {
        if (isClosed) {
            return RuntimeScalarCache.scalarFalse;
        }

        try {
            byte[] bytes = string.getBytes(charset);
            outputStream.write(bytes);
            return RuntimeScalarCache.getScalarInt(bytes.length);
        } catch (IOException e) {
            return RuntimeScalarCache.scalarFalse;
        }
    }

    @Override
    public RuntimeScalar close() {
        if (!isClosed) {
            try {
                outputStream.close();
                isClosed = true;
            } catch (IOException e) {
                // Ignore close errors
            }
        }
        return RuntimeScalarCache.scalarTrue;
    }

    @Override
    public RuntimeScalar flush() {
        if (isClosed) {
            return RuntimeScalarCache.scalarTrue;
        }

        try {
            outputStream.flush();
            return RuntimeScalarCache.scalarTrue;
        } catch (IOException e) {
            return RuntimeScalarCache.scalarFalse;
        }
    }

    @Override
    public RuntimeScalar eof() {
        // Output handles don't have EOF in the same sense
        return isClosed ? RuntimeScalarCache.scalarTrue : RuntimeScalarCache.scalarFalse;
    }

    @Override
    public RuntimeScalar doRead(int maxBytes, Charset charset) {
        // Output-only handle
        return new RuntimeScalar();
    }

    /**
     * Set the character encoding for writing.
     */
    public void setCharset(Charset charset) {
        this.charset = charset;
    }
}
