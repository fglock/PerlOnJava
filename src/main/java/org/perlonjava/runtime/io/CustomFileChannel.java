package org.perlonjava.runtime.io;

import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarCache;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

import static org.perlonjava.runtime.runtimetypes.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.runtimetypes.RuntimeIO.handleIOException;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.getScalarInt;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarTrue;

/**
 * A custom file channel implementation that provides Perl-compatible I/O operations.
 *
 * <p>This class wraps Java's {@link FileChannel} to provide an implementation of
 * {@link IOHandle} that supports file-based I/O operations. It handles character
 * encoding/decoding, EOF detection, and provides Perl-style return values for
 * all operations.
 *
 * <p>Key features:
 * <ul>
 *   <li>Supports both file path and file descriptor based construction</li>
 *   <li>Handles multi-byte character sequences correctly across read boundaries</li>
 *   <li>Tracks EOF state for Perl-compatible EOF detection</li>
 *   <li>Provides atomic position-based operations (tell, seek)</li>
 *   <li>Supports file truncation</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>
 * // Open a file for reading
 * Set&lt;StandardOpenOption&gt; options = Set.of(StandardOpenOption.READ);
 * CustomFileChannel channel = new CustomFileChannel(Paths.get("file.txt"), options);
 *
 * // Read data
 * RuntimeScalar data = channel.read(1024, StandardCharsets.UTF_8);
 *
 * // Check EOF
 * if (channel.eof().getBoolean()) {
 *     // End of file reached
 * }
 * </pre>
 *
 * @see IOHandle
 * @see FileChannel
 */
public class CustomFileChannel implements IOHandle {

    /**
     * The underlying Java NIO FileChannel for actual I/O operations
     */
    private final FileChannel fileChannel;

    /**
     * Tracks whether end-of-file has been reached during reading
     */
    private boolean isEOF;

    // When true, writes should always occur at end-of-file (Perl's append semantics).
    private boolean appendMode;

    /**
     * Helper for handling multi-byte character decoding across read boundaries
     */
    private CharsetDecoderHelper decoderHelper;

    /**
     * Creates a new CustomFileChannel for the specified file path.
     *
     * @param path    the path to the file to open
     * @param options the options specifying how the file is opened (READ, WRITE, etc.)
     * @throws IOException if an I/O error occurs opening the file
     */
    public CustomFileChannel(Path path, Set<StandardOpenOption> options) throws IOException {
        this.fileChannel = FileChannel.open(path, options);
        this.isEOF = false;
        this.appendMode = false;
    }

    /**
     * Creates a new CustomFileChannel from an existing file descriptor.
     *
     * <p>This constructor is useful for wrapping standard I/O streams (stdin, stdout, stderr)
     * or file descriptors obtained from native code.
     *
     * @param fd      the file descriptor to wrap
     * @param options the options specifying the mode (must contain either READ or WRITE)
     * @throws IOException              if an I/O error occurs
     * @throws IllegalArgumentException if options don't contain READ or WRITE
     */
    public CustomFileChannel(FileDescriptor fd, Set<StandardOpenOption> options) throws IOException {
        if (options.contains(StandardOpenOption.READ)) {
            // Create a read channel from the file descriptor
            this.fileChannel = new FileInputStream(fd).getChannel();
        } else if (options.contains(StandardOpenOption.WRITE)) {
            // Create a write channel from the file descriptor
            this.fileChannel = new FileOutputStream(fd).getChannel();
        } else {
            throw new IllegalArgumentException("Invalid options for FileDescriptor");
        }
        this.isEOF = false;
        this.appendMode = false;
    }

    public void setAppendMode(boolean appendMode) {
        this.appendMode = appendMode;
    }

    /**
     * Reads data from the file with proper character encoding support.
     *
     * <p>This method handles multi-byte character sequences correctly, buffering
     * incomplete sequences until enough data is available to decode them properly.
     * This is crucial for UTF-8 and other variable-length encodings.
     *
     * @param maxBytes the maximum number of bytes to read
     * @param charset  the character encoding to use for decoding
     * @return RuntimeScalar containing the decoded string data
     */
    @Override
    public RuntimeScalar doRead(int maxBytes, Charset charset) {
        try {
            byte[] buffer = new byte[maxBytes];
            ByteBuffer byteBuffer = ByteBuffer.wrap(buffer);
            int bytesRead = fileChannel.read(byteBuffer);

            if (bytesRead == -1) {
                isEOF = true;
                return new RuntimeScalar("");
            }

            // Check if we've reached EOF (read less than requested)
            if (bytesRead < maxBytes) {
                isEOF = true;
            }

            // Also treat "at end of file" as EOF for Perl semantics (eof true after last successful read)
            try {
                if (fileChannel.position() >= fileChannel.size()) {
                    isEOF = true;
                }
            } catch (IOException e) {
                // ignore
            }

            // Convert bytes to string where each char represents a byte
            StringBuilder result = new StringBuilder(bytesRead);
            for (int i = 0; i < bytesRead; i++) {
                result.append((char) (buffer[i] & 0xFF));
            }
            return new RuntimeScalar(result.toString());
        } catch (IOException e) {
            return handleIOException(e, "Read operation failed");
        }
    }

    /**
     * Writes a string to the file.
     *
     * <p>The string is converted to bytes using ISO-8859-1 encoding, which
     * preserves byte values for binary data. This allows the method to handle
     * both text and binary data correctly.
     *
     * @param string the string data to write
     * @return RuntimeScalar containing the number of bytes written
     */
    @Override
    public RuntimeScalar write(String string) {
        try {
            if (appendMode) {
                fileChannel.position(fileChannel.size());
            }
            byte[] data = new byte[string.length()];
            for (int i = 0; i < string.length(); i++) {
                data[i] = (byte) string.charAt(i);
            }
            ByteBuffer byteBuffer = ByteBuffer.wrap(data);
            fileChannel.write(byteBuffer);
            return scalarTrue;
        } catch (IOException e) {
            return handleIOException(e, "write failed");
        }
    }

    /**
     * Closes the file channel and releases any system resources.
     *
     * @return RuntimeScalar with true value on success
     */
    @Override
    public RuntimeScalar close() {
        try {
            // Ensure all data is flushed before closing
            fileChannel.force(true); // Force both content and metadata
            fileChannel.close();
            return scalarTrue;
        } catch (IOException e) {
            return handleIOException(e, "close failed");
        }
    }

    /**
     * Checks if end-of-file has been reached.
     *
     * <p>The EOF flag is set when a read operation returns -1 (no more data).
     *
     * @return RuntimeScalar with true if EOF reached, false otherwise
     */
    @Override
    public RuntimeScalar eof() {
        return new RuntimeScalar(isEOF);
    }

    /**
     * Gets the current position in the file.
     *
     * @return RuntimeScalar containing the current byte position, or -1 on error
     */
    @Override
    public RuntimeScalar tell() {
        try {
            return getScalarInt(fileChannel.position());
        } catch (IOException e) {
            handleIOException(e, "tell failed");
            return getScalarInt(-1);
        }
    }

    /**
     * Seeks to a new position in the file based on the whence parameter.
     *
     * <p>The whence parameter determines how the position is calculated:
     * <ul>
     *   <li>SEEK_SET (0): Set position to pos bytes from the beginning of the file</li>
     *   <li>SEEK_CUR (1): Set position to current position + pos bytes</li>
     *   <li>SEEK_END (2): Set position to end of file + pos bytes</li>
     * </ul>
     *
     * <p>Seeking clears the EOF flag since we may no longer be at the end of file.
     *
     * @param pos    the offset in bytes
     * @param whence the reference point for the offset (SEEK_SET, SEEK_CUR, or SEEK_END)
     * @return RuntimeScalar with true on success, false on failure
     */
    @Override
    public RuntimeScalar seek(long pos, int whence) {
        try {
            long newPosition;

            switch (whence) {
                case SEEK_SET: // from beginning
                    newPosition = pos;
                    break;
                case SEEK_CUR: // from current position
                    newPosition = fileChannel.position() + pos;
                    break;
                case SEEK_END: // from end of file
                    newPosition = fileChannel.size() + pos;
                    break;
                default:
                    return handleIOException(new IOException("Invalid whence value: " + whence), "seek failed");
            }

            // Ensure the new position is not negative
            if (newPosition < 0) {
                return handleIOException(new IOException("Negative seek position"), "seek failed");
            }

            fileChannel.position(newPosition);
            // Perl semantics: seeking to EOF sets eof flag, seeking elsewhere clears it.
            try {
                isEOF = (fileChannel.position() >= fileChannel.size());
            } catch (IOException e) {
                isEOF = false;
            }
            return scalarTrue;
        } catch (IOException e) {
            return handleIOException(e, "seek failed");
        }
    }

    /**
     * Flushes any buffered data to the underlying storage device.
     *
     * <p>This method forces any buffered data to be written to the storage device,
     * including file metadata for reliability.
     *
     * @return RuntimeScalar with true on success
     */
    @Override
    public RuntimeScalar flush() {
        try {
            // Force both content and metadata to be written for reliability
            fileChannel.force(true);
            return scalarTrue;
        } catch (IOException e) {
            return handleIOException(e, "flush failed");
        }
    }

    /**
     * Gets the file descriptor number for this channel.
     *
     * <p>Note: FileChannel does not expose the underlying file descriptor in Java,
     * so this method returns undef. This is a limitation of the Java API.
     *
     * @return RuntimeScalar with undef value
     */
    @Override
    public RuntimeScalar fileno() {
        return RuntimeScalarCache.scalarUndef; // FileChannel does not expose a file descriptor
    }

    /**
     * Truncates the file to the specified length.
     *
     * <p>If the file is currently larger than the specified length, the extra data
     * is discarded. If the file is smaller, it is extended with null bytes.
     *
     * @param length the desired length of the file in bytes
     * @return RuntimeScalar with true on success
     * @throws IllegalArgumentException if length is negative
     */
    public RuntimeScalar truncate(long length) {
        try {
            if (length < 0) {
                throw new IllegalArgumentException("Invalid arguments for truncate operation.");
            }
            fileChannel.truncate(length);
            return scalarTrue;
        } catch (IOException e) {
            return handleIOException(e, "truncate failed");
        }
    }

    @Override
    public RuntimeScalar sysread(int length) {
        try {
            ByteBuffer buffer = ByteBuffer.allocate(length);
            int bytesRead = fileChannel.read(buffer);  // Changed from 'channel' to 'fileChannel'

            if (bytesRead == -1) {
                // EOF - return empty string
                return new RuntimeScalar("");
            }

            buffer.flip();
            byte[] readBytes = new byte[buffer.remaining()];
            buffer.get(readBytes);
            return new RuntimeScalar(readBytes);
        } catch (IOException e) {
            getGlobalVariable("main::!").set(e.getMessage());
            return new RuntimeScalar(); // undef
        }
    }

    @Override
    public RuntimeScalar syswrite(String data) {
        try {
            // Convert string to bytes (each char is a byte 0-255)
            ByteBuffer buffer = ByteBuffer.allocate(data.length());
            for (int i = 0; i < data.length(); i++) {
                buffer.put((byte) (data.charAt(i) & 0xFF));
            }
            buffer.flip();

            int bytesWritten = fileChannel.write(buffer);  // Changed from 'channel' to 'fileChannel'
            return new RuntimeScalar(bytesWritten);
        } catch (IOException e) {
            getGlobalVariable("main::!").set(e.getMessage());
            return new RuntimeScalar(); // undef
        }
    }
}
