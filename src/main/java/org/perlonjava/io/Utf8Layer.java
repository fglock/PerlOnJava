package org.perlonjava.io;

import java.nio.ByteBuffer;
import java.nio.charset.*;

// UTF-8 layer
class Utf8Layer implements IOLayer {
    private final Charset charset = StandardCharsets.UTF_8;
    private final CharsetDecoder decoder;
    private final CharsetEncoder encoder;
    private ByteBuffer pendingBytes = ByteBuffer.allocate(4);

    public Utf8Layer() {
        decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        encoder = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        pendingBytes.limit(0);
    }

    @Override
    public byte[] processInput(byte[] input) {
        // For UTF-8 input, we need to decode UTF-8 bytes to characters
        // then encode back to bytes as ISO-8859-1 for internal string representation
        try {
            String decoded = new String(input, charset);
            return decoded.getBytes(StandardCharsets.ISO_8859_1);
        } catch (Exception e) {
            return input; // Fallback
        }
    }

    @Override
    public byte[] processOutput(byte[] output) {
        // For UTF-8 output, convert from internal representation to UTF-8
        try {
            String str = new String(output, StandardCharsets.ISO_8859_1);
            return str.getBytes(charset);
        } catch (Exception e) {
            return output; // Fallback
        }
    }

    @Override
    public void reset() {
        decoder.reset();
        encoder.reset();
        pendingBytes.clear();
        pendingBytes.limit(0);
    }
}
