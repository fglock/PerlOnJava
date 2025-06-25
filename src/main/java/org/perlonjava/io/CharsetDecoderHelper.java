package org.perlonjava.io;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Helper class for decoding bytes to strings with proper handling of
 * multi-byte character boundaries.
 */
public class CharsetDecoderHelper {
    private CharsetDecoder decoder;
    private ByteBuffer leftoverBuffer;
    private Charset currentCharset;
    private boolean needMoreData = false;

    public CharsetDecoderHelper() {
        this(StandardCharsets.ISO_8859_1);
    }

    public CharsetDecoderHelper(Charset initialCharset) {
        initDecoder(initialCharset);
    }

    private void initDecoder(Charset charset) {
        this.currentCharset = charset;
        this.decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        // Allocate buffer for maximum possible incomplete sequence
        this.leftoverBuffer = ByteBuffer.allocate(4); // Max UTF-8 sequence is 4 bytes
        this.needMoreData = false;
    }

    /**
     * Check if the helper needs more data to decode a character.
     * @return true if more data is needed
     */
    public boolean needsMoreData() {
        return needMoreData;
    }

    /**
     * Decodes bytes to string, handling multi-byte character boundaries.
     * Will accumulate bytes until at least one character can be decoded.
     *
     * @param buffer the byte array containing the data
     * @param bytesRead the number of bytes read (-1 for EOF)
     * @param charset the charset to use for decoding
     * @return the decoded string
     */
    public String decode(byte[] buffer, int bytesRead, Charset charset) {
        // Check if we need to reinitialize for a different charset
        if (!charset.equals(currentCharset)) {
            initDecoder(charset);
        }

        needMoreData = false;

        // Handle EOF
        if (bytesRead == -1) {
            if (leftoverBuffer.position() > 0) {
                // Process any remaining bytes
                leftoverBuffer.flip();
                CharBuffer charBuffer = CharBuffer.allocate(leftoverBuffer.remaining());
                decoder.decode(leftoverBuffer, charBuffer, true);
                decoder.flush(charBuffer);
                decoder.reset();
                leftoverBuffer.clear();
                charBuffer.flip();
                return charBuffer.toString();
            }
            return "";
        }

        // Calculate total bytes to process
        int totalBytes = leftoverBuffer.position() + bytesRead;
        ByteBuffer inputBuffer = ByteBuffer.allocate(totalBytes);

        // Add leftover bytes from previous read
        if (leftoverBuffer.position() > 0) {
            leftoverBuffer.flip();
            inputBuffer.put(leftoverBuffer);
            leftoverBuffer.clear();
        }

        // Add new bytes
        if (bytesRead > 0) {
            inputBuffer.put(buffer, 0, bytesRead);
        }

        inputBuffer.flip();

        // Decode what we can
        CharBuffer charBuffer = CharBuffer.allocate(totalBytes);
        CoderResult result = decoder.decode(inputBuffer, charBuffer, false);
        charBuffer.flip();

        // Save any remaining bytes for next read
        if (inputBuffer.hasRemaining()) {
            leftoverBuffer.put(inputBuffer);

            // If we couldn't decode any characters and have leftover bytes,
            // we need more data
            if (charBuffer.length() == 0) {
                needMoreData = true;
            }
        }

        return charBuffer.toString();
    }

    /**
     * Resets the decoder state, discarding any leftover bytes.
     */
    public void reset() {
        decoder.reset();
        leftoverBuffer.clear();
        needMoreData = false;
    }
}
