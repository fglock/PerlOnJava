package org.perlonjava.io;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;

/**
 * Generic encoding layer that handles character set conversions.
 * Uses streaming mode to properly handle multi-byte character encodings.
 */
class EncodingLayer implements IOLayer {
    private final Charset charset;
    private final CharsetDecoder decoder;
    private final CharsetEncoder encoder;

    // Buffer for accumulating incomplete multi-byte sequences
    private final ByteBuffer pendingInput = ByteBuffer.allocate(16);

    public EncodingLayer(Charset charset) {
        this.charset = charset;
        this.decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        this.encoder = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        pendingInput.limit(0); // Start with empty pending buffer
    }

    @Override
    public void processInput(StreamingContext context) {
        ByteBuffer input = context.getInput();
        ByteBuffer output = context.getOutput();

        // First, prepend any pending bytes from last time
        if (pendingInput.hasRemaining()) {
            ByteBuffer combined = ByteBuffer.allocate(pendingInput.remaining() + input.remaining());
            combined.put(pendingInput);
            combined.put(input);
            combined.flip();
            input = combined;
        }

        // Decode characters from input
        CharBuffer chars = CharBuffer.allocate(input.remaining() * 2);
        CoderResult result = decoder.decode(input, chars, false);

        // Convert decoded characters to output bytes (ISO-8859-1)
        chars.flip();
        while (chars.hasRemaining() && output.hasRemaining()) {
            output.put((byte)(chars.get() & 0xFF));
        }

        // Save any undecoded bytes for next time
        pendingInput.clear();
        if (input.hasRemaining()) {
            pendingInput.put(input);
        }
        pendingInput.flip();
    }

    @Override
    public void processOutput(StreamingContext context) {
        ByteBuffer input = context.getInput();
        ByteBuffer output = context.getOutput();

        // Convert from ISO-8859-1 bytes to characters
        CharBuffer chars = CharBuffer.allocate(input.remaining());
        while (input.hasRemaining() && chars.hasRemaining()) {
            chars.put((char)(input.get() & 0xFF));
        }
        chars.flip();

        // Encode characters to target charset
        encoder.encode(chars, output, false);
    }

    @Override
    public void reset() {
        decoder.reset();
        encoder.reset();
        pendingInput.clear();
        pendingInput.limit(0);
    }
}
