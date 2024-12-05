package org.perlonjava.io;

import org.perlonjava.runtime.RuntimeScalar;

import java.io.IOException;
import java.io.OutputStream;

import static org.perlonjava.runtime.RuntimeScalarCache.scalarFalse;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarTrue;

public class CustomOutputStreamHandle implements IOHandle {
    private final OutputStream outputStream;

    public CustomOutputStreamHandle(OutputStream outputStream) {
        this.outputStream = outputStream;
    }

    @Override
    public RuntimeScalar write(byte[] data) {
        try {
            outputStream.write(data);
            return scalarTrue; // Indicate success
        } catch (IOException e) {
            return scalarFalse; // Indicate failure
        }
    }

    @Override
    public RuntimeScalar flush() {
        try {
            outputStream.flush();
            return new RuntimeScalar(1); // Indicate success
        } catch (IOException e) {
            return new RuntimeScalar(0); // Indicate failure
        }
    }

    @Override
    public RuntimeScalar close() {
        try {
            outputStream.close();
            return new RuntimeScalar(1); // Indicate success
        } catch (IOException e) {
            return new RuntimeScalar(0); // Indicate failure
        }
    }
}
