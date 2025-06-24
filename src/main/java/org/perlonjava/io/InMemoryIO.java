package org.perlonjava.io;

import org.perlonjava.runtime.RuntimeScalar;
import org.perlonjava.runtime.RuntimeScalarCache;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.perlonjava.runtime.RuntimeIO.handleIOException;

public class InMemoryIO implements IOHandle {
    private final ByteArrayOutputStream byteArrayOutputStream;
    private ByteArrayInputStream byteArrayInputStream;
    private boolean isEOF;

    public InMemoryIO() {
        this.byteArrayOutputStream = new ByteArrayOutputStream();
    }

    public static RuntimeScalar truncate(RuntimeScalar scalar, long length) {
        if (scalar == null || length < 0) {
            throw new IllegalArgumentException("Invalid arguments for truncate operation.");
        }

        // Convert the string representation of the scalar to a byte array
        byte[] data = scalar.toString().getBytes(); // Use the default charset

        if (length > data.length) {
            throw new IllegalArgumentException("Truncate length exceeds data length.");
        }

        byte[] truncatedData = new byte[(int) length];
        System.arraycopy(data, 0, truncatedData, 0, (int) length);

        // Assuming RuntimeScalar can be constructed from a byte array
        return new RuntimeScalar(new String(truncatedData)); // Convert back to string if needed
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
    public RuntimeScalar write(String string) {
        var data = string.getBytes();
        try {
            byteArrayOutputStream.write(data);
            return RuntimeScalarCache.scalarTrue;
        } catch (IOException e) {
            return handleIOException(e, "Write operation failed");
        }
    }

    @Override
    public RuntimeScalar close() {
        try {
            byteArrayOutputStream.flush();
        } catch (IOException e) {
            return handleIOException(e, "Flush operation failed");
        }
        return RuntimeScalarCache.scalarTrue;
    }

    @Override
    public RuntimeScalar eof() {
        return new RuntimeScalar(isEOF);
    }


    @Override
    public RuntimeScalar flush() {
        try {
            byteArrayOutputStream.flush();
            return RuntimeScalarCache.scalarTrue;
        } catch (IOException e) {
            return handleIOException(e, "Flush operation failed");
        }
    }

    public byte[] getOutput() {
        return byteArrayOutputStream.toByteArray();
    }

    @Override
    public RuntimeScalar fileno() {
        // Return a placeholder value as in-memory streams do not have a file descriptor
        return new RuntimeScalar(-1);
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
