package org.perlonjava.io;

import java.nio.charset.*;

// Generic encoding layer
class EncodingLayer implements IOLayer {
    private final Charset charset;
    private final CharsetDecoder decoder;
    private final CharsetEncoder encoder;

    public EncodingLayer(Charset charset) {
        this.charset = charset;
        this.decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        this.encoder = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
    }

    @Override
    public byte[] processInput(byte[] input) {
        try {
            String decoded = new String(input, charset);
            return decoded.getBytes(StandardCharsets.ISO_8859_1);
        } catch (Exception e) {
            return input;
        }
    }

    @Override
    public byte[] processOutput(byte[] output) {
        try {
            String str = new String(output, StandardCharsets.ISO_8859_1);
            return str.getBytes(charset);
        } catch (Exception e) {
            return output;
        }
    }

    @Override
    public void reset() {
        decoder.reset();
        encoder.reset();
    }
}
