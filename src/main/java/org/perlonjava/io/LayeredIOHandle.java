package org.perlonjava.io;

import org.perlonjava.runtime.RuntimeScalar;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

public class LayeredIOHandle implements IOHandle {
    private final IOHandle delegate;
    private IOMode mode;
    private Charset encoding;

    // State for handling buffer boundaries
    private byte[] pendingInputBytes = new byte[0];
    private CharsetDecoder decoder;
    private CharsetEncoder encoder;
    private ByteBuffer encodeBuffer;
    private boolean lastWasCR = false; // For CRLF handling across boundaries

    public LayeredIOHandle(IOHandle delegate) {
        this.delegate = delegate;
        this.mode = IOMode.DEFAULT;
        this.encoding = StandardCharsets.UTF_8;
        initializeCodecs();
    }

    private void initializeCodecs() {
        if (encoding != null) {
            decoder = encoding.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
            encoder = encoding.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
            // Buffer for encoding operations
            encodeBuffer = ByteBuffer.allocate(8192);
        }
    }

    public IOHandle getDelegate() {
        return delegate;
    }

    public RuntimeScalar binmode(String modeStr) {
        try {
            parseAndSetMode(modeStr);
            initializeCodecs();
            // Reset state when changing modes
            pendingInputBytes = new byte[0];
            lastWasCR = false;
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
                this.encoding = null;
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
    public RuntimeScalar write(String data) {
        byte[] processedData = processOutputData(data);
        // Write a string made of characters in the 0-255 range
        return delegate.write(new String(processedData, StandardCharsets.ISO_8859_1));
    }

    @Override
    public RuntimeScalar read(byte[] buffer) {
        RuntimeScalar result = delegate.read(buffer);
        if (result.getInt() > 0) {
            int actualBytes = processInputData(buffer, result.getInt());
            result = new RuntimeScalar(actualBytes);
        }
        return result;
    }

    private byte[] processOutputData(String data) {
        switch (mode) {
            case CRLF:
                return convertLfToCrlf(data.getBytes());
            case UTF8:
            case ENCODING:
                return encodeText(data);
            case BYTES:
            case DEFAULT:
            default:
                return data.getBytes();
        }
    }

    private int processInputData(byte[] buffer, int bytesRead) {
        switch (mode) {
            case CRLF:
                return convertCrlfToLfWithState(buffer, bytesRead);
            case UTF8:
            case ENCODING:
                return decodeText(buffer, bytesRead);
            case BYTES:
            case DEFAULT:
            default:
                return bytesRead;
        }
    }

    private byte[] encodeText(String data) {
        if (encoder == null) return data.getBytes();
        try {
            return data.getBytes(encoding);
        } catch (Exception e) {
            // Fallback to original data
            return data.getBytes();
        }
    }

    private int decodeText(byte[] buffer, int bytesRead) {
        if (decoder == null) return bytesRead;

        try {
            // Combine pending bytes with new data
            byte[] allBytes = new byte[pendingInputBytes.length + bytesRead];
            System.arraycopy(pendingInputBytes, 0, allBytes, 0, pendingInputBytes.length);
            System.arraycopy(buffer, 0, allBytes, pendingInputBytes.length, bytesRead);

            ByteBuffer byteBuffer = ByteBuffer.wrap(allBytes);
            CharBuffer charBuffer = CharBuffer.allocate(allBytes.length * 2);

            CoderResult result = decoder.decode(byteBuffer, charBuffer, false);

            // Handle incomplete sequences at end of buffer
            int remainingBytes = byteBuffer.remaining();
            if (remainingBytes > 0) {
                pendingInputBytes = new byte[remainingBytes];
                byteBuffer.get(pendingInputBytes);
            } else {
                pendingInputBytes = new byte[0];
            }

            // Convert back to bytes (simplified)
            charBuffer.flip();
            String decoded = charBuffer.toString();
            byte[] decodedBytes = decoded.getBytes(StandardCharsets.UTF_8);

            int actualLength = Math.min(decodedBytes.length, buffer.length);
            System.arraycopy(decodedBytes, 0, buffer, 0, actualLength);

            // Clear remaining buffer
            for (int i = actualLength; i < buffer.length; i++) {
                buffer[i] = 0;
            }

            return actualLength;
        } catch (Exception e) {
            // Fallback - return original data
            return bytesRead;
        }
    }

    private byte[] convertLfToCrlf(byte[] data) {
        // Count LF characters that need CR added
        int lfCount = 0;
        for (int i = 0; i < data.length; i++) {
            if (data[i] == '\n' && (i == 0 || data[i-1] != '\r')) {
                lfCount++;
            }
        }

        if (lfCount == 0) return data;

        byte[] result = new byte[data.length + lfCount];
        int j = 0;
        for (int i = 0; i < data.length; i++) {
            if (data[i] == '\n' && (i == 0 || data[i-1] != '\r')) {
                result[j++] = '\r';
            }
            result[j++] = data[i];
        }

        return result;
    }

    private int convertCrlfToLfWithState(byte[] buffer, int length) {
        int writePos = 0;
        int readPos = 0;

        // Handle case where previous buffer ended with CR
        if (lastWasCR && length > 0 && buffer[0] == '\n') {
            // Skip the LF since we already processed the CR->LF conversion
            readPos = 1;
            lastWasCR = false;
        } else {
            lastWasCR = false;
        }

        for (; readPos < length; readPos++) {
            if (buffer[readPos] == '\r') {
                if (readPos + 1 < length && buffer[readPos + 1] == '\n') {
                    // CRLF sequence within buffer - skip CR, keep LF
                    continue;
                } else {
                    // CR at end of buffer - remember for next read
                    lastWasCR = true;
                    buffer[writePos++] = '\n'; // Convert CR to LF
                }
            } else {
                buffer[writePos++] = buffer[readPos];
            }
        }

        // Clear the remaining bytes
        while (writePos < length) {
            buffer[writePos++] = 0;
        }

        return writePos;
    }

    @Override
    public RuntimeScalar flush() {
        // Handle any pending encoded data
        if (encoder != null && encodeBuffer != null && encodeBuffer.position() > 0) {
            encodeBuffer.flip();
            byte[] pending = new byte[encodeBuffer.remaining()];
            encodeBuffer.get(pending);
            delegate.write(new String(pending));
            encodeBuffer.clear();
        }

        // Flush any incomplete character sequences
        if (decoder != null && pendingInputBytes.length > 0) {
            // In a real implementation, we might need to handle
            // incomplete sequences on flush
        }

        return delegate.flush();
    }

    @Override
    public RuntimeScalar close() {
        // Flush any pending data before closing
        flush();
        return delegate.close();
    }

    // Delegate remaining methods unchanged
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
        // Reset state on seek
        pendingInputBytes = new byte[0];
        lastWasCR = false;
        if (decoder != null) decoder.reset();
        if (encoder != null) encoder.reset();
        return delegate.seek(pos);
    }

    @Override
    public RuntimeScalar truncate(long length) {
        return delegate.truncate(length);
    }

    private enum IOMode {
        DEFAULT,
        BYTES,
        CRLF,
        UTF8,
        ENCODING
    }
}