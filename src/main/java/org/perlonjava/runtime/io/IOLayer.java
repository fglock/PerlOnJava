package org.perlonjava.runtime.io;

/**
 * Base interface for implementing Perl-style IO layers in Java.
 *
 * <p>This interface provides a simplified implementation of Perl's PerlIO layer
 * system. In Perl, IO layers are stackable transformations that can be applied
 * to filehandles to modify how data is read and written.</p>
 *
 * <p>Common Perl IO layers include:</p>
 * <ul>
 *   <li><b>:raw</b> - Binary mode, no transformations</li>
 *   <li><b>:crlf</b> - Convert between CRLF and LF line endings</li>
 *   <li><b>:encoding(name)</b> - Character encoding/decoding</li>
 *   <li><b>:utf8</b> - UTF-8 encoding layer</li>
 *   <li><b>:bytes</b> - Treat data as bytes, not characters</li>
 * </ul>
 *
 * <p>In Perl, layers are specified when opening files or applied later:</p>
 * <pre>
 * # Apply layers when opening
 * open(my $fh, '<:encoding(UTF-8):crlf', 'file.txt');
 *
 * # Apply layers to existing handle
 * binmode($fh, ':raw');
 * binmode(STDOUT, ':encoding(UTF-8)');
 * </pre>
 *
 * <p>This Java implementation uses a simplified string-based approach where:</p>
 * <ul>
 *   <li>Each character in a string represents a single byte (0-255)</li>
 *   <li>Layers transform these strings during read/write operations</li>
 *   <li>Multiple layers can be stacked to create a processing pipeline</li>
 * </ul>
 *
 * <p>The string representation allows layers to be easily composed while
 * maintaining compatibility with Java's string-based IO APIs.</p>
 *
 * @see CrlfLayer
 * @see EncodingLayer
 */
public interface IOLayer {
    /**
     * The layer name as known by Perl
     *
     * @return Layer name
     */
    String getLayerName();

    /**
     * Processes input data during read operations.
     *
     * <p>This method is called when reading data from an underlying source
     * (file, socket, etc.). It transforms the raw input according to the
     * layer's purpose.</p>
     *
     * <p>For example:</p>
     * <ul>
     *   <li>A :crlf layer converts CRLF sequences to LF</li>
     *   <li>An :encoding layer decodes bytes to characters</li>
     *   <li>A :gzip layer might decompress data</li>
     * </ul>
     *
     * <p>The input string uses a byte-oriented representation where each
     * character holds a value from 0-255, representing a single byte.
     * This approach maintains compatibility with binary data while using
     * Java's string type for convenience.</p>
     *
     * <p>Implementations should handle stateful transformations appropriately,
     * maintaining any necessary context between calls (e.g., partial
     * multi-byte sequences in encoding layers).</p>
     *
     * @param input the input string where each char represents a byte (0-255)
     * @return the transformed string after applying the layer's input processing
     */
    String processInput(String input);

    /**
     * Processes output data during write operations.
     *
     * <p>This method is called when writing data to an underlying destination
     * (file, socket, etc.). It transforms the data according to the layer's
     * purpose before it's written.</p>
     *
     * <p>For example:</p>
     * <ul>
     *   <li>A :crlf layer converts LF to CRLF sequences</li>
     *   <li>An :encoding layer encodes characters to bytes</li>
     *   <li>A :gzip layer might compress data</li>
     * </ul>
     *
     * <p>The output transformation is typically the inverse of the input
     * transformation, ensuring round-trip compatibility when both reading
     * and writing through the same layer.</p>
     *
     * <p>The returned string uses the same byte-oriented representation as
     * processInput(), where each character represents a single byte (0-255).</p>
     *
     * @param output the output string to process
     * @return the transformed string where each char represents a byte (0-255)
     */
    String processOutput(String output);

    /**
     * Resets any internal state maintained by the layer.
     *
     * <p>This method is called to clear any stateful information that the
     * layer might be maintaining between operations. Common scenarios include:</p>
     * <ul>
     *   <li>Clearing partial character sequences in encoding layers</li>
     *   <li>Resetting line-ending detection state in CRLF layers</li>
     *   <li>Flushing compression buffers in compression layers</li>
     * </ul>
     *
     * <p>In Perl, this typically happens when:</p>
     * <ul>
     *   <li>A filehandle is closed and reopened</li>
     *   <li>binmode() is called to change layers</li>
     *   <li>Explicitly seeking to the beginning of a file</li>
     * </ul>
     *
     * <p>The default implementation is a no-op, suitable for stateless layers.
     * Stateful layers should override this method to properly clear their state.</p>
     */
    default void reset() {
        // Default no-op implementation
        // Stateless layers don't need to override this
    }
}
