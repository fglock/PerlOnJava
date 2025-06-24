package org.perlonjava.io;

import org.perlonjava.runtime.RuntimeScalar;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public class LayeredIOHandle implements IOHandle {
    private final IOHandle delegate;
    private IOMode mode;
    private Charset encoding;

    public LayeredIOHandle(IOHandle delegate) {
        this.delegate = delegate;
        this.mode = IOMode.DEFAULT;
        this.encoding = StandardCharsets.UTF_8;
    }

    public IOHandle getDelegate() {
        return delegate;
    }

    public RuntimeScalar binmode(String modeStr) {
        try {
            parseAndSetMode(modeStr);
            return new RuntimeScalar(1);
        } catch (Exception e) {
            return new RuntimeScalar(0);
        }
    }

    private void parseAndSetMode(String modeStr) {
        if (modeStr == null || modeStr.isEmpty()) {
            this.mode = IOMode.BYTES;
            return;
        }

        switch (modeStr) {
            case ":bytes":
            case ":raw":
                this.mode = IOMode.BYTES;
                break;
            case ":crlf":
                this.mode = IOMode.CRLF;
                break;
            case ":utf8":
                this.mode = IOMode.UTF8;
                this.encoding = StandardCharsets.UTF_8;
                break;
            default:
                if (modeStr.startsWith(":encoding(") && modeStr.endsWith(")")) {
                    String encodingName = modeStr.substring(10, modeStr.length() - 1);
                    this.mode = IOMode.ENCODING;
                    this.encoding = Charset.forName(encodingName);
                } else {
                    throw new IllegalArgumentException("Unknown binmode: " + modeStr);
                }
        }
    }

    @Override
    public RuntimeScalar write(byte[] data) {
        byte[] processedData = processOutputData(data);
        return delegate.write(processedData);
    }

    @Override
    public RuntimeScalar read(byte[] buffer) {
        RuntimeScalar result = delegate.read(buffer);
        if (result.getInt() > 0) {
            processInputData(buffer, result.getInt());
        }
        return result;
    }

    private byte[] processOutputData(byte[] data) {
        switch (mode) {
            case CRLF:
                return convertLfToCrlf(data);
            case UTF8:
            case ENCODING:
                // Data is already in bytes, just pass through
                return data;
            case BYTES:
            case DEFAULT:
            default:
                return data;
        }
    }

    private void processInputData(byte[] buffer, int bytesRead) {
        switch (mode) {
            case CRLF:
                convertCrlfToLf(buffer, bytesRead);
                break;
            case UTF8:
            case ENCODING:
                // Data handling would be done at a higher level
                break;
            case BYTES:
            case DEFAULT:
            default:
                // No processing needed
                break;
        }
    }

    private byte[] convertLfToCrlf(byte[] data) {
        // Count LF characters
        int lfCount = 0;
        for (byte b : data) {
            if (b == '\n') lfCount++;
        }

        if (lfCount == 0) return data;

        // Create new array with space for additional CR characters
        byte[] result = new byte[data.length + lfCount];
        int j = 0;
        for (int i = 0; i < data.length; i++) {
            if (data[i] == '\n' && (i == 0 || data[i-1] != '\r')) {
                result[j++] = '\r';
            }
            result[j++] = data[i];
        }

        // Trim if we overallocated
        if (j < result.length) {
            byte[] trimmed = new byte[j];
            System.arraycopy(result, 0, trimmed, 0, j);
            return trimmed;
        }

        return result;
    }

    private void convertCrlfToLf(byte[] buffer, int length) {
        int writePos = 0;
        for (int readPos = 0; readPos < length; readPos++) {
            if (buffer[readPos] == '\r' && readPos + 1 < length && buffer[readPos + 1] == '\n') {
                // Skip the CR
                continue;
            }
            buffer[writePos++] = buffer[readPos];
        }

        // Clear the remaining bytes
        while (writePos < length) {
            buffer[writePos++] = 0;
        }
    }

    // Delegate all other methods
    @Override
    public RuntimeScalar close() {
        return delegate.close();
    }

    @Override
    public RuntimeScalar flush() {
        return delegate.flush();
    }

    @Override
    public RuntimeScalar getc() {
        return delegate.getc();
    }

    @Override
    public RuntimeScalar fileno() {
        return delegate.fileno();
    }

    @Override
    public RuntimeScalar eof() {
        return delegate.eof();
    }

    @Override
    public RuntimeScalar tell() {
        return delegate.tell();
    }

    @Override
    public RuntimeScalar seek(long pos) {
        return delegate.seek(pos);
    }

    @Override
    public RuntimeScalar truncate(long length) {
        return delegate.truncate(length);
    }

    // Enum for IO modes
    private enum IOMode {
        DEFAULT,    // Default mode
        BYTES,      // :bytes or :raw - no encoding
        CRLF,       // :crlf - convert line endings
        UTF8,       // :utf8 - UTF-8 encoding
        ENCODING    // :encoding(X) - specific encoding
    }
}