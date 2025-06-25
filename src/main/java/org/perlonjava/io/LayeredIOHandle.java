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

    // Add UTF-8 input buffer
    private ByteBuffer utf8InputBuffer;

    // Debug flag
    private static final boolean DEBUG = true;

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
            // Buffer for UTF-8 input handling
            utf8InputBuffer = ByteBuffer.allocate(4096);
            utf8InputBuffer.limit(0); // Start with empty buffer
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
            this.encoding = null;
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
        if (DEBUG) {
            System.err.println("LayeredIOHandle.write: mode=" + mode + ", data length=" + data.length());
            System.err.println("LayeredIOHandle.write: data='" + data + "'");
        }

        if (mode == IOMode.UTF8 || mode == IOMode.ENCODING) {
            // For UTF-8 and encoding modes, we need to convert the string to bytes
            // and then pass those bytes as a byte-string to the delegate
            byte[] encodedBytes = processOutputData(data);

            if (DEBUG) {
                System.err.println("LayeredIOHandle.write: encoded to " + encodedBytes.length + " bytes");
                StringBuilder hex = new StringBuilder();
                for (byte b : encodedBytes) {
                    hex.append(String.format("%02X ", b & 0xFF));
                }
                System.err.println("LayeredIOHandle.write: bytes=" + hex.toString());
            }

            // Convert bytes to a string where each character represents one byte
            StringBuilder byteString = new StringBuilder(encodedBytes.length);
            for (byte b : encodedBytes) {
                byteString.append((char)(b & 0xFF));
            }

            if (DEBUG) {
                System.err.println("LayeredIOHandle.write: byteString length=" + byteString.length());
            }

            return delegate.write(byteString.toString());
        } else if (mode == IOMode.CRLF) {
            // For CRLF mode, process the data and convert
            byte[] processedBytes = processOutputData(data);
            StringBuilder byteString = new StringBuilder(processedBytes.length);
            for (byte b : processedBytes) {
                byteString.append((char)(b & 0xFF));
            }
            return delegate.write(byteString.toString());
        } else {
            // For BYTES and DEFAULT modes, pass through directly
            return delegate.write(data);
        }
    }

    @Override
    public RuntimeScalar read(int maxChars) {
        RuntimeScalar result;

        switch (mode) {
            case UTF8:
                // Delegate UTF-8 decoding to the underlying handle
                result = delegate.read(maxChars, StandardCharsets.UTF_8);
                break;

            case ENCODING:
                // Delegate encoding-specific decoding to the underlying handle
                if (encoding != null) {
                    result = delegate.read(maxChars, encoding);
                } else {
                    result = delegate.read(maxChars);
                }
                break;

            case CRLF:
                // For CRLF mode, we still need to process after reading
                result = delegate.read(maxChars);
                String data = result.toString();
                if (!data.isEmpty()) {
                    return new RuntimeScalar(convertCrlfToLf(data));
                }
                return result;

            case BYTES:
            case DEFAULT:
            default:
                // Pass through raw bytes as string (ISO-8859-1)
                result = delegate.read(maxChars);
                break;
        }

        if (DEBUG) {
            System.err.println("LayeredIOHandle.read: mode=" + mode + ", result length=" + result.toString().length());
        }

        return result;
    }

    private String convertCrlfToLf(String data) {
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < data.length(); i++) {
            char c = data.charAt(i);
            if (c == '\r') {
                if (i + 1 < data.length() && data.charAt(i + 1) == '\n') {
                    // Skip CR in CRLF
                    continue;
                } else {
                    // Convert lone CR to LF
                    result.append('\n');
                }
            } else {
                result.append(c);
            }
        }

        return result.toString();
    }

    private byte[] processOutputData(String data) {
        switch (mode) {
            case CRLF:
                // Get bytes as ISO-8859-1 to preserve byte values
                byte[] bytes = new byte[data.length()];
                for (int i = 0; i < data.length(); i++) {
                    bytes[i] = (byte) data.charAt(i);
                }
                return convertLfToCrlf(bytes);
            case UTF8:
                byte[] utf8Bytes = data.getBytes(StandardCharsets.UTF_8);
                if (DEBUG) {
                    System.err.println("processOutputData: UTF8 encoding produced " + utf8Bytes.length + " bytes from " + data.length() + " chars");
                }
                return utf8Bytes;
            case ENCODING:
                return encodeText(data);
            case BYTES:
            case DEFAULT:
            default:
                // Convert string to bytes preserving character values
                byte[] rawBytes = new byte[data.length()];
                for (int i = 0; i < data.length(); i++) {
                    rawBytes[i] = (byte) data.charAt(i);
                }
                return rawBytes;
        }
    }

    private int processInputData(byte[] buffer, int bytesRead) {
        switch (mode) {
            case CRLF:
                return convertCrlfToLfWithState(buffer, bytesRead);
            case UTF8:
                // For UTF-8 mode, bytes are already UTF-8 encoded
                // No conversion needed, just pass through
                if (DEBUG) {
                    System.err.println("processInputData: UTF8 mode, passing through " + bytesRead + " bytes");
                }
                return bytesRead;
            case ENCODING:
                return decodeText(buffer, bytesRead);
            case BYTES:
            case DEFAULT:
            default:
                return bytesRead;
        }
    }

    private byte[] encodeText(String data) {
        if (encoder == null || encoding == null) {
            byte[] bytes = new byte[data.length()];
            for (int i = 0; i < data.length(); i++) {
                bytes[i] = (byte) data.charAt(i);
            }
            return bytes;
        }
        try {
            return data.getBytes(encoding);
        } catch (Exception e) {
            // Fallback
            byte[] bytes = new byte[data.length()];
            for (int i = 0; i < data.length(); i++) {
                bytes[i] = (byte) data.charAt(i);
            }
            return bytes;
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

            // Convert decoded characters back to the buffer
            charBuffer.flip();
            String decoded = charBuffer.toString();

            // Convert string to bytes where each character is one byte
            int actualLength = Math.min(decoded.length(), buffer.length);
            for (int i = 0; i < actualLength; i++) {
                buffer[i] = (byte) decoded.charAt(i);
            }

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

            StringBuilder byteString = new StringBuilder(pending.length);
            for (byte b : pending) {
                byteString.append((char)(b & 0xFF));
            }
            delegate.write(byteString.toString());
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
        if (utf8InputBuffer != null) {
            utf8InputBuffer.clear();
            utf8InputBuffer.limit(0);
        }
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
