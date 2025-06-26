package org.perlonjava.io;

/**
 * Interface for I/O layers that can transform data during read and write operations.
 *
 * <p>IOLayer implementations provide bidirectional data transformation capabilities
 * for I/O operations. Each layer can process data differently depending on whether
 * it's being read (input) or written (output).
 *
 * <p>Common implementations include:
 * <ul>
 *   <li>Character encoding/decoding layers (UTF-8, UTF-16, etc.)</li>
 *   <li>Line ending conversion layers (CRLF, LF)</li>
 *   <li>Compression/decompression layers</li>
 *   <li>Encryption/decryption layers</li>
 * </ul>
 *
 * <p>Layers are typically stacked, with data flowing through multiple layers
 * in sequence. For reading, layers are applied bottom-up (from the file to the
 * application). For writing, layers are applied top-down (from the application
 * to the file).
 *
 * <p>Example layer stack for reading:
 * <pre>
 *   Application
 *       ↑
 *   UTF-8 Layer (decodes bytes to characters)
 *       ↑
 *   CRLF Layer (converts CRLF to LF)
 *       ↑
 *   File/Socket
 * </pre>
 *
 * <p>Implementations must be stateless or properly manage their state through
 * the {@link #reset()} method to handle seek operations correctly.
 *
 * @see LayeredIOHandle
 */
interface IOLayer {
    /**
     * Processes input data during read operations.
     *
     * <p>This method is called when data is being read from the underlying
     * I/O source. The layer should transform the input bytes according to
     * its purpose (e.g., decode UTF-8 bytes to characters, convert CRLF to LF).
     *
     * <p>Implementations should handle partial data gracefully, as input may
     * be provided in chunks. Any incomplete sequences should be buffered
     * internally and processed when more data arrives.
     *
     * @param input the raw bytes read from the underlying source
     * @return the processed bytes after applying the layer's transformation
     */
    byte[] processInput(byte[] input);

    /**
     * Processes output data during write operations.
     *
     * <p>This method is called when data is being written to the underlying
     * I/O destination. The layer should transform the output bytes according
     * to its purpose (e.g., encode characters to UTF-8 bytes, convert LF to CRLF).
     *
     * <p>Implementations should ensure that all data is properly transformed
     * and no partial sequences are written that could corrupt the output.
     *
     * @param output the bytes to be written to the underlying destination
     * @return the processed bytes after applying the layer's transformation
     */
    byte[] processOutput(byte[] output);

    /**
     * Resets the layer's internal state.
     *
     * <p>This method is called when the I/O position changes (e.g., after a seek
     * operation) or when the layer configuration changes. Implementations should
     * clear any buffered data, reset any state machines, and prepare for
     * processing data from a new position.
     *
     * <p>Proper implementation of this method is crucial for correct behavior
     * after seek operations, as any buffered partial data from the previous
     * position would be invalid at the new position.
     */
    void reset();
}
