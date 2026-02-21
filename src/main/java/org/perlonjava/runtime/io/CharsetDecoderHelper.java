package org.perlonjava.runtime.io;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;

/**
 * Helper class for decoding bytes to strings with proper handling of
 * multi-byte character boundaries.
 *
 * <p>This class solves a common problem when reading from streams: multi-byte
 * character encodings (like UTF-8) can be split across read boundaries. For
 * example, a 3-byte UTF-8 character might have 2 bytes at the end of one read
 * and 1 byte at the beginning of the next read.
 *
 * <p>The helper maintains an internal buffer for incomplete byte sequences and
 * ensures that characters are only decoded when complete. This prevents the
 * corruption or loss of characters that would occur if partial sequences were
 * decoded incorrectly.
 *
 * <p>Key features:
 * <ul>
 *   <li>Handles variable-length encodings (UTF-8, UTF-16, etc.)</li>
 *   <li>Buffers incomplete sequences between calls</li>
 *   <li>Detects when more data is needed to complete a character</li>
 *   <li>Replaces malformed sequences with replacement characters</li>
 *   <li>Supports charset switching with proper state reset</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>
 * CharsetDecoderHelper helper = new CharsetDecoderHelper(StandardCharsets.UTF_8);
 *
 * // First read gets partial UTF-8 sequence
 * byte[] data1 = {(byte)0xE2, (byte)0x82}; // First 2 bytes of € symbol
 * String result1 = helper.decode(data1, 2, StandardCharsets.UTF_8);
 * // result1 is empty, helper.needsMoreData() returns true
 *
 * // Second read completes the sequence
 * byte[] data2 = {(byte)0xAC}; // Last byte of € symbol
 * String result2 = helper.decode(data2, 1, StandardCharsets.UTF_8);
 * // result2 contains "€"
 * </pre>
 *
 * @see CharsetDecoder
 * @see Charset
 */
public class CharsetDecoderHelper {
    /**
     * The charset decoder configured with error handling policies
     */
    private CharsetDecoder decoder;

    /**
     * Buffer for storing incomplete byte sequences between decode calls
     */
    private ByteBuffer leftoverBuffer;

    /**
     * The current charset being used for decoding
     */
    private Charset currentCharset;

    /**
     * Flag indicating if more data is needed to decode at least one character
     */
    private boolean needMoreData = false;

    /**
     * Creates a new decoder helper with ISO-8859-1 as the default charset.
     *
     * <p>ISO-8859-1 is used as the default because it's a single-byte encoding
     * where each byte maps directly to a character, making it suitable for
     * binary data handling.
     */
    public CharsetDecoderHelper() {
        this(StandardCharsets.ISO_8859_1);
    }

    /**
     * Creates a new decoder helper with the specified initial charset.
     *
     * @param initialCharset the charset to use for decoding
     */
    public CharsetDecoderHelper(Charset initialCharset) {
        initDecoder(initialCharset);
    }

    /**
     * Initializes or reinitializes the decoder with a new charset.
     *
     * <p>This method sets up the decoder with appropriate error handling:
     * <ul>
     *   <li>Malformed input is replaced with the replacement character (�)</li>
     *   <li>Unmappable characters are also replaced</li>
     * </ul>
     *
     * @param charset the charset to use for decoding
     */
    private void initDecoder(Charset charset) {
        this.currentCharset = charset;
        this.decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        // Allocate buffer for maximum possible incomplete sequence
        // Max UTF-8 sequence is 4 bytes, but we use 4 for safety with other encodings
        this.leftoverBuffer = ByteBuffer.allocate(4);
        this.needMoreData = false;
    }

    /**
     * Checks if the helper needs more data to decode a character.
     *
     * <p>This method returns true when the helper has buffered bytes that
     * form an incomplete character sequence. The caller should read more
     * data and call decode() again.
     *
     * @return true if more data is needed to complete a character sequence
     */
    public boolean needsMoreData() {
        return needMoreData;
    }

    /**
     * Decodes bytes to string, handling multi-byte character boundaries.
     *
     * <p>This method accumulates bytes until at least one complete character
     * can be decoded. Incomplete sequences are buffered internally and combined
     * with bytes from subsequent calls.
     *
     * <p>Special cases:
     * <ul>
     *   <li>EOF (bytesRead == -1): Flushes any remaining buffered bytes</li>
     *   <li>Charset change: Reinitializes the decoder for the new charset</li>
     *   <li>Incomplete sequence: Buffers bytes and sets needMoreData flag</li>
     * </ul>
     *
     * @param buffer    the byte array containing the data
     * @param bytesRead the number of bytes read (-1 for EOF)
     * @param charset   the charset to use for decoding
     * @return the decoded string (may be empty if waiting for more data)
     */
    public String decode(byte[] buffer, int bytesRead, Charset charset) {
        // Check if we need to reinitialize for a different charset
        if (!charset.equals(currentCharset)) {
            initDecoder(charset);
        }

        needMoreData = false;

        // Handle EOF - flush any remaining bytes
        if (bytesRead == -1) {
            if (leftoverBuffer.position() > 0) {
                // Process any remaining bytes in the buffer
                leftoverBuffer.flip();
                CharBuffer charBuffer = CharBuffer.allocate(leftoverBuffer.remaining());
                // Decode with endOfInput=true to flush partial sequences
                decoder.decode(leftoverBuffer, charBuffer, true);
                decoder.flush(charBuffer);
                decoder.reset();
                leftoverBuffer.clear();
                charBuffer.flip();
                return charBuffer.toString();
            }
            return "";
        }

        // Calculate total bytes to process (leftover + new)
        int totalBytes = leftoverBuffer.position() + bytesRead;
        ByteBuffer inputBuffer = ByteBuffer.allocate(totalBytes);

        // Add leftover bytes from previous read
        if (leftoverBuffer.position() > 0) {
            leftoverBuffer.flip();
            inputBuffer.put(leftoverBuffer);
            leftoverBuffer.clear();
        }

        // Add new bytes from current read
        if (bytesRead > 0) {
            inputBuffer.put(buffer, 0, bytesRead);
        }

        inputBuffer.flip();

        // Decode what we can
        CharBuffer charBuffer = CharBuffer.allocate(totalBytes);
        // Decode with endOfInput=false since more data might come
        CoderResult result = decoder.decode(inputBuffer, charBuffer, false);
        charBuffer.flip();

        // Save any remaining bytes for next read
        if (inputBuffer.hasRemaining()) {
            // Store incomplete sequence for next call
            leftoverBuffer.put(inputBuffer);

            // If we couldn't decode any characters and have leftover bytes,
            // we need more data to complete the sequence
            if (charBuffer.length() == 0) {
                needMoreData = true;
            }
        }

        return charBuffer.toString();
    }

    /**
     * Resets the decoder state, discarding any leftover bytes.
     *
     * <p>This method should be called when the input stream position changes
     * (e.g., after a seek operation) to prevent corruption from partial
     * sequences that are no longer valid.
     */
    public void reset() {
        decoder.reset();
        leftoverBuffer.clear();
        needMoreData = false;
    }
}
