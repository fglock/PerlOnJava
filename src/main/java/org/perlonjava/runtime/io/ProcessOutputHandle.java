package org.perlonjava.runtime.io;

import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarCache;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.perlonjava.runtime.runtimetypes.GlobalVariable.getGlobalVariable;

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
    public RuntimeScalar fileno() {
        // Return undef to let RuntimeIO.fileno() lazily assign a registry fileno
        return RuntimeScalarCache.scalarUndef;
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

    @Override
    public RuntimeScalar syswrite(String data) {
        if (isClosed) {
            return RuntimeScalarCache.scalarFalse;
        }
        try {
            byte[] bytes = data.getBytes(charset);
            outputStream.write(bytes);
            outputStream.flush();
            return RuntimeScalarCache.getScalarInt(bytes.length);
        } catch (IOException e) {
            return RuntimeScalarCache.scalarFalse;
        }
    }

    /**
     * Set the character encoding for writing.
     */
    public void setCharset(Charset charset) {
        this.charset = charset;
    }

    @Override
    public RuntimeScalar fileno() {
        return RuntimeScalarCache.scalarUndef;
    }

    @Override
    public RuntimeScalar syswrite(String data) {
        if (isClosed) {
            getGlobalVariable("main::!").set("Bad file descriptor");
            return new RuntimeScalar(); // undef
        }
        try {
            byte[] bytes = new byte[data.length()];
            for (int i = 0; i < data.length(); i++) {
                bytes[i] = (byte) (data.charAt(i) & 0xFF);
            }
            outputStream.write(bytes);
            outputStream.flush();
            return new RuntimeScalar(bytes.length);
        } catch (IOException e) {
            getGlobalVariable("main::!").set(e.getMessage());
            return new RuntimeScalar(); // undef
        }
    }
}
