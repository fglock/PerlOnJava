package org.perlonjava.io;

import org.perlonjava.runtime.RuntimeScalar;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static org.perlonjava.runtime.RuntimeScalarCache.scalarFalse;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarTrue;

/**
 * An IOHandle implementation that wraps a Java OutputStream for Perl-compatible output operations.
 *
 * <p>This class provides a bridge between Java's {@link OutputStream} and PerlOnJava's
 * {@link IOHandle} interface, enabling Perl-style I/O operations on any Java output stream.
 * It's particularly useful for wrapping standard output streams (stdout, stderr) or
 * custom output streams like network sockets or compressed streams.
 *
 * <p>Key features:
 * <ul>
 *   <li>Write-only operations (no read support)</li>
 *   <li>Binary-safe string writing using ISO-8859-1 encoding</li>
 *   <li>Perl-compatible return values (true/false for success/failure)</li>
 *   <li>Automatic exception handling with appropriate return values</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>
 * // Wrap System.out for Perl-style output
 * CustomOutputStreamHandle stdout = new CustomOutputStreamHandle(System.out);
 * stdout.write("Hello, World!\n");
 * stdout.flush();
 *
 * // Wrap a file output stream
 * FileOutputStream fos = new FileOutputStream("output.txt");
 * CustomOutputStreamHandle fileHandle = new CustomOutputStreamHandle(fos);
 * fileHandle.write("File content");
 * fileHandle.close();
 * </pre>
 *
 * <p>Note: This class only implements the output-related methods of IOHandle.
 * Methods like read(), getc(), tell(), seek(), and eof() are not supported and
 * will return appropriate error values or throw exceptions if called.
 *
 * @see IOHandle
 * @see OutputStream
 */
public class CustomOutputStreamHandle implements IOHandle {
    /**
     * The underlying Java OutputStream that performs actual output operations
     */
    private final OutputStream outputStream;

    /**
     * Tracks the number of bytes written for tell() functionality
     */
    private long bytesWritten = 0;

    /**
     * Creates a new output handle wrapping the given OutputStream.
     *
     * @param outputStream the OutputStream to wrap (must not be null)
     */
    public CustomOutputStreamHandle(OutputStream outputStream) {
        this.outputStream = outputStream;
    }

    /**
     * Writes a string to the output stream.
     *
     * <p>The string is converted to bytes using ISO-8859-1 encoding, which
     * ensures that each character in the string is treated as a single byte
     * value (0-255). This makes the method binary-safe and suitable for
     * writing both text and binary data.
     *
     * <p>This encoding choice is important for Perl compatibility, as Perl
     * strings can contain arbitrary byte values when used for binary I/O.
     *
     * @param string the string to write (each character represents one byte)
     * @return RuntimeScalar with true (1) on success, false (0) on failure
     */
    @Override
    public RuntimeScalar write(String string) {
        // Convert string to bytes, treating each character as a byte value
        var data = string.getBytes(StandardCharsets.ISO_8859_1);
        try {
            outputStream.write(data);
            bytesWritten += data.length;
            return scalarTrue; // Indicate success
        } catch (IOException e) {
            // Return false on I/O error, matching Perl's behavior
            return scalarFalse; // Indicate failure
        }
    }

    /**
     * Flushes any buffered data to the underlying output destination.
     *
     * <p>This method forces any data buffered by the OutputStream or the
     * underlying system to be written immediately. This is important for:
     * <ul>
     *   <li>Ensuring data is visible to other processes</li>
     *   <li>Interactive applications where immediate output is needed</li>
     *   <li>Before closing to ensure all data is written</li>
     * </ul>
     *
     * @return RuntimeScalar with 1 on success, 0 on failure
     */
    @Override
    public RuntimeScalar flush() {
        try {
            outputStream.flush();
            return new RuntimeScalar(1); // Indicate success
        } catch (IOException e) {
            return new RuntimeScalar(0); // Indicate failure
        }
    }

    /**
     * Closes the output stream and releases any associated resources.
     *
     * <p>After calling this method, any further write operations will fail.
     * This method attempts to flush any remaining buffered data before closing.
     *
     * <p>Note: Some OutputStreams (like System.out) should not be closed.
     * It's the caller's responsibility to ensure appropriate streams are closed.
     *
     * @return RuntimeScalar with 1 on success, 0 on failure
     */
    @Override
    public RuntimeScalar close() {
        try {
            outputStream.close();
            return new RuntimeScalar(1); // Indicate success
        } catch (IOException e) {
            return new RuntimeScalar(0); // Indicate failure
        }
    }

    /**
     * Returns the current position in the output stream.
     *
     * <p>For output streams, this returns the total number of bytes written
     * since the stream was opened. This value is maintained internally as
     * OutputStream doesn't provide position information.
     *
     * <p>Note: This position may not correspond to the actual file position
     * if the underlying stream performs buffering or transformation.
     *
     * @return RuntimeScalar containing the number of bytes written, or -1 on error
     */
    @Override
    public RuntimeScalar tell() {
        return new RuntimeScalar(bytesWritten);
    }

    /**
     * Attempts to seek to a position in the output stream.
     *
     * <p>Since OutputStream doesn't support seeking, this method always fails
     * and returns -1. This matches Perl's behavior when seeking on unseekable
     * streams like pipes or sockets.
     *
     * <p>For seekable output, use a RandomAccessFile-based handle instead.
     *
     * @param position the byte position to seek to (ignored)
     * @return RuntimeScalar with -1 indicating seek is not supported
     */
    @Override
    public RuntimeScalar seek(long position) {
        // OutputStream doesn't support seeking
        // Return -1 to indicate failure, matching Perl's behavior
        return new RuntimeScalar(-1);
    }
}
