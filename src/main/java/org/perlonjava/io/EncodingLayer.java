package org.perlonjava.io;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;

/**
 * Implementation of Perl's :encoding() IO layer for character set conversions.
 *
 * <p>This layer provides character encoding and decoding functionality similar to
 * Perl's :encoding() layer. It transforms byte streams to/from character streams
 * using the specified character encoding.</p>
 *
 * <p>In Perl, the :encoding() layer is used like:</p>
 * <pre>
 * open(my $fh, '<:encoding(UTF-8)', 'file.txt');
 * </pre>
 *
 * <p>This implementation handles:</p>
 * <ul>
 *   <li>Decoding input bytes to characters according to the specified charset</li>
 *   <li>Encoding output characters to bytes according to the specified charset</li>
 *   <li>Buffering of incomplete multi-byte sequences</li>
 *   <li>Handling of malformed and unmappable characters (replaced with substitution character)</li>
 * </ul>
 *
 * <p>The layer maintains internal state for handling multi-byte character sequences
 * that may span multiple read operations, similar to Perl's behavior.</p>
 *
 * @see IOLayer
 */
public class EncodingLayer implements IOLayer {

    private final String layerName;

    public String getLayerName() {
        return layerName;
    }

    /**
     * Default buffer size for input processing.
     * This size is chosen to balance memory usage with performance.
     */
    private static final int BUFFER_SIZE = 1024;

    /**
     * The character set used for encoding/decoding operations.
     */
    private final Charset charset;

    /**
     * Decoder for converting bytes to characters.
     * Configured to replace malformed input and unmappable characters
     * with the replacement character (U+FFFD).
     */
    private final CharsetDecoder decoder;

    /**
     * Encoder for converting characters to bytes.
     * Configured to replace malformed input and unmappable characters
     * with the charset's default replacement byte sequence.
     */
    private final CharsetEncoder encoder;

    /**
     * Buffer for accumulating input bytes.
     * This buffer holds incomplete multi-byte sequences between read operations,
     * ensuring proper handling of character boundaries.
     */
    private ByteBuffer inputBuffer;

    /**
     * Constructs a new encoding layer with the specified character set.
     *
     * <p>The layer is configured to handle encoding errors by replacing
     * problematic characters rather than throwing exceptions, matching
     * Perl's default behavior for the :encoding() layer.</p>
     *
     * @param charset the character set to use for encoding/decoding operations
     * @throws NullPointerException if charset is null
     */
    public EncodingLayer(Charset charset, String layerName) {
        this.charset = charset;
        this.decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        this.encoder = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        this.inputBuffer = ByteBuffer.allocate(BUFFER_SIZE);
        this.layerName = layerName;
    }

    /**
     * Processes input by decoding bytes to characters according to the layer's charset.
     *
     * <p>This method accumulates input bytes and decodes them to characters.
     * Incomplete multi-byte sequences are buffered and combined with subsequent
     * input, ensuring proper handling of character boundaries across multiple
     * read operations.</p>
     *
     * <p>The input string is treated as raw bytes where each character represents
     * a single byte value (0-255). This matches Perl's internal representation
     * when dealing with binary data.</p>
     *
     * @param input a string where each character represents a byte (0-255)
     * @return the decoded character string
     */
    @Override
    public String processInput(String input) {
        // Add new bytes to buffer
        for (int i = 0; i < input.length(); i++) {
            if (!inputBuffer.hasRemaining()) {
                // Grow buffer if needed - double the capacity to avoid frequent reallocations
                ByteBuffer newBuffer = ByteBuffer.allocate(inputBuffer.capacity() * 2);
                inputBuffer.flip();
                newBuffer.put(inputBuffer);
                inputBuffer = newBuffer;
            }
            // Extract byte value from character (0-255 range)
            inputBuffer.put((byte) (input.charAt(i) & 0xFF));
        }

        // Prepare buffer for reading
        inputBuffer.flip();

        // Decode available bytes
        // Allocate output buffer with room for worst-case expansion (1 byte -> 1 char)
        CharBuffer output = CharBuffer.allocate(inputBuffer.remaining() * 2);

        // Decode with false for endOfInput to handle incomplete sequences
        CoderResult result = decoder.decode(inputBuffer, output, false);

        // Compact buffer to keep undecoded bytes for next call
        // This preserves incomplete multi-byte sequences
        inputBuffer.compact();

        // Prepare output for reading and convert to string
        output.flip();
        return output.toString();
    }

    /**
     * Processes output by encoding characters to bytes according to the layer's charset.
     *
     * <p>This method encodes the character string to bytes using the specified charset,
     * then converts each byte back to a character in the 0-255 range for transmission
     * through the IO layer stack.</p>
     *
     * <p>This approach maintains compatibility with Perl's internal handling of
     * binary data in strings.</p>
     *
     * @param output the character string to encode
     * @return a string where each character represents a byte (0-255)
     */
    @Override
    public String processOutput(String output) {
        // Encode string to bytes using the charset
        byte[] bytes = output.getBytes(charset);

        // Convert bytes back to string representation
        // Each byte becomes a character in the 0-255 range
        StringBuilder result = new StringBuilder(bytes.length);
        for (byte b : bytes) {
            result.append((char) (b & 0xFF));
        }
        return result.toString();
    }

    /**
     * Resets the encoding layer to its initial state.
     *
     * <p>This method clears all internal buffers and resets the encoder/decoder
     * state. It should be called when switching between files or when explicitly
     * resetting the IO stream.</p>
     *
     * <p>In Perl, this would typically happen when closing and reopening a filehandle
     * or when explicitly calling binmode() to change the encoding.</p>
     */
    @Override
    public void reset() {
        decoder.reset();
        encoder.reset();
        inputBuffer.clear();
    }
}