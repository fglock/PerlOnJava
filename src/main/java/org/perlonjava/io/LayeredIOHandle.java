package org.perlonjava.io;

import org.perlonjava.runtime.RuntimeScalar;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A layered I/O handle implementation that supports Perl-style I/O layers.
 *
 * <p>This class wraps an existing IOHandle and applies a stack of I/O layers
 * that can transform data during read and write operations. Layers are applied
 * in reverse order for writes (top-down) and forward order for reads (bottom-up).
 *
 * <p>Supported layers include:
 * <ul>
 *   <li>{@code :bytes} or {@code :raw} - Raw byte mode (no transformation)</li>
 *   <li>{@code :crlf} - CRLF line ending conversion</li>
 *   <li>{@code :utf8} - UTF-8 encoding/decoding</li>
 *   <li>{@code :encoding(name)} - Custom encoding (e.g., :encoding(UTF-16))</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>
 * LayeredIOHandle handle = new LayeredIOHandle(baseHandle);
 * handle.binmode(":utf8:crlf");  // Apply UTF-8 encoding and CRLF conversion
 * </pre>
 *
 * @see IOHandle
 * @see IOLayer
 */
public class LayeredIOHandle implements IOHandle {
    /** The underlying I/O handle that performs actual I/O operations */
    private final IOHandle delegate;

    /** Stack of I/O layers to apply to data */
    private List<IOLayer> layers = new ArrayList<>();

    /** Debug flag for development/troubleshooting */
    private static final boolean DEBUG = false;

    /**
     * Creates a new layered I/O handle wrapping the given delegate.
     *
     * @param delegate the underlying I/O handle to wrap
     */
    public LayeredIOHandle(IOHandle delegate) {
        this.delegate = delegate;
    }

    /**
     * Gets the underlying delegate I/O handle.
     *
     * @return the wrapped I/O handle
     */
    public IOHandle getDelegate() {
        return delegate;
    }

    /**
     * Sets the I/O layers for this handle based on a mode string.
     *
     * <p>The mode string can contain multiple layers separated by colons.
     * For example: ":utf8:crlf" applies UTF-8 encoding and CRLF conversion.
     *
     * <p>This method resets any pending input state when changing modes.
     *
     * @param modeStr the mode string specifying layers (e.g., ":utf8", ":crlf", ":encoding(UTF-16)")
     * @return RuntimeScalar with value 1 on success, 0 on failure
     */
    public RuntimeScalar binmode(String modeStr) {
        try {
            parseAndSetLayers(modeStr);
            // Reset state when changing modes to avoid corruption
            return new RuntimeScalar(1);
        } catch (Exception e) {
            return new RuntimeScalar(0);
        }
    }

    /**
     * Parses the mode string and sets up the appropriate I/O layers.
     *
     * <p>If no mode string is provided or it's empty, defaults to bytes mode.
     * The method handles special cases like :encoding(...) which contains parentheses.
     *
     * @param modeStr the mode string to parse
     * @throws IllegalArgumentException if an unknown layer is specified
     */
    private void parseAndSetLayers(String modeStr) {
        layers.clear();

        if (modeStr == null || modeStr.isEmpty()) {
            // Default to raw bytes mode
            layers.add(new BytesLayer());
            return;
        }

        // Split by ':' but handle :encoding(...) specially
        String[] parts = splitLayers(modeStr);

        for (String part : parts) {
            if (part.isEmpty()) continue;

            IOLayer layer = createLayer(part);
            layers.add(layer);
        }

        // If no layers were added, default to bytes
        if (layers.isEmpty()) {
            layers.add(new BytesLayer());
        }
    }

    /**
     * Splits a mode string into individual layer specifications.
     *
     * <p>This method handles the special case of :encoding(...) which contains
     * parentheses and should not be split at the colon inside the parentheses.
     *
     * @param modeStr the mode string to split
     * @return array of layer specifications
     */
    private String[] splitLayers(String modeStr) {
        List<String> result = new ArrayList<>();
        int start = 0;
        int i = 0;

        while (i < modeStr.length()) {
            if (modeStr.charAt(i) == ':') {
                // Found a colon separator
                if (i > start) {
                    result.add(modeStr.substring(start, i));
                }
                start = i + 1;
                i++;
            } else if (modeStr.startsWith("encoding(", i)) {
                // Special handling for encoding(...) to keep it as one unit
                int closeIdx = modeStr.indexOf(')', i);
                if (closeIdx != -1) {
                    // Add any content before encoding(...)
                    if (i > start) {
                        result.add(modeStr.substring(start, i));
                    }
                    // Add the complete encoding(...) specification
                    result.add(modeStr.substring(i, closeIdx + 1));
                    i = closeIdx + 1;
                    start = i;
                    // Skip any trailing colon
                    if (i < modeStr.length() && modeStr.charAt(i) == ':') {
                        start++;
                        i++;
                    }
                } else {
                    i++;
                }
            } else {
                i++;
            }
        }

        // Add any remaining content
        if (start < modeStr.length()) {
            result.add(modeStr.substring(start));
        }

        return result.toArray(new String[0]);
    }

    /**
     * Creates an IOLayer instance based on the layer specification.
     *
     * @param layerSpec the layer specification (e.g., "utf8", "crlf", "encoding(UTF-16)")
     * @return the created IOLayer instance
     * @throws IllegalArgumentException if the layer specification is unknown
     */
    private IOLayer createLayer(String layerSpec) {
        return switch (layerSpec) {
            case "bytes", "raw", "unix" -> new BytesLayer();
            case "crlf" -> new CrlfLayer(this);
            case "utf8" -> new Utf8Layer();
            default -> {
                // Handle encoding(...) specifications
                if (layerSpec.startsWith("encoding(") && layerSpec.endsWith(")")) {
                    String encodingName = layerSpec.substring(9, layerSpec.length() - 1);
                    try {
                        Charset charset = Charset.forName(encodingName);
                        yield new EncodingLayer(charset);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("Unknown encoding: " + encodingName);
                    }
                }
                throw new IllegalArgumentException("Unknown layer: " + layerSpec);
            }
        };
    }

    /**
     * Writes data to the handle, applying all layers in reverse order.
     *
     * <p>Layers are applied top-down (reverse order) for writing. For example,
     * with layers [:utf8, :crlf], data is first encoded to UTF-8, then CRLF
     * conversion is applied.
     *
     * @param data the string data to write
     * @return RuntimeScalar indicating success/failure or bytes written
     */
    @Override
    public RuntimeScalar write(String data) {
        if (DEBUG) {
            System.err.println("LayeredIOHandle.write: layers=" + layers.size() + ", data length=" + data.length());
        }

        // Convert string to bytes using ISO-8859-1 to preserve byte values
        byte[] bytes = data.getBytes(StandardCharsets.ISO_8859_1);

        // Apply layers in reverse order for writing (top-down)
        for (int i = layers.size() - 1; i >= 0; i--) {
            bytes = layers.get(i).processOutput(bytes);
        }

        // Convert bytes back to a string where each character represents one byte
        // This preserves the byte values when passing to the delegate
        StringBuilder byteString = new StringBuilder(bytes.length);
        for (byte b : bytes) {
            byteString.append((char)(b & 0xFF));
        }

        return delegate.write(byteString.toString());
    }

    /**
     * Reads data from the handle, applying all layers in forward order.
     *
     * <p>Layers are applied bottom-up (forward order) for reading. For example,
     * with layers [:utf8, :crlf], CRLF conversion is applied first, then UTF-8
     * decoding.
     *
     * @param maxChars maximum number of characters to read
     * @return RuntimeScalar containing the read data
     */
    @Override
    public RuntimeScalar read(int maxChars) {
        // Read raw data from the delegate
        RuntimeScalar result = delegate.read(maxChars);
        String data = result.toString();

        if (data.isEmpty()) {
            return result;
        }

        // Convert string to bytes, treating each character as a byte value
        byte[] bytes = new byte[data.length()];
        for (int i = 0; i < data.length(); i++) {
            bytes[i] = (byte) data.charAt(i);
        }

        // Apply layers in forward order for reading (bottom-up)
        for (IOLayer layer : layers) {
            bytes = layer.processInput(bytes);
        }

        // Convert processed bytes back to string
        StringBuilder sb = new StringBuilder(bytes.length);
        for (byte b : bytes) {
            sb.append((char)(b & 0xFF));
        }

        return new RuntimeScalar(sb.toString());
    }

    /**
     * Flushes any buffered data to the underlying handle.
     *
     * @return RuntimeScalar indicating success/failure
     */
    @Override
    public RuntimeScalar flush() {
        return delegate.flush();
    }

    /**
     * Closes the handle after flushing any buffered data.
     *
     * @return RuntimeScalar indicating success/failure
     */
    @Override
    public RuntimeScalar close() {
        flush();
        return delegate.close();
    }

    /**
     * Gets the file descriptor number for this handle.
     *
     * @return RuntimeScalar containing the file descriptor number
     */
    @Override
    public RuntimeScalar fileno() {
        return delegate.fileno();
    }

    /**
     * Checks if the handle has reached end-of-file.
     *
     * @return RuntimeScalar with true if at EOF, false otherwise
     */
    @Override
    public RuntimeScalar eof() {
        return delegate.eof();
    }

    /**
     * Gets the current position in the file.
     *
     * @return RuntimeScalar containing the current position
     */
    @Override
    public RuntimeScalar tell() {
        return delegate.tell();
    }

    /**
     * Seeks to a new position in the file based on the whence parameter.
     *
     * <p>This method resets all layer state to ensure clean reads from
     * the new position.
     *
     * @param pos the offset in bytes
     * @param whence the reference point for the offset (SEEK_SET, SEEK_CUR, or SEEK_END)
     * @return RuntimeScalar indicating success/failure
     */
    @Override
    public RuntimeScalar seek(long pos, int whence) {
        // Reset state on seek to avoid corruption from partial data
        // Reset all layers
        for (IOLayer layer : layers) {
            layer.reset();
        }
        return delegate.seek(pos, whence);
    }

    /**
     * Truncates the file to the specified length.
     *
     * @param length the new length of the file
     * @return RuntimeScalar indicating success/failure
     */
    @Override
    public RuntimeScalar truncate(long length) {
        return delegate.truncate(length);
    }
}
