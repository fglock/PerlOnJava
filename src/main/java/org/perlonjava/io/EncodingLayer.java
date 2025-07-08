package org.perlonjava.io;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;

/**
 * Simplified encoding layer that handles character set conversions.
 */
public class EncodingLayer implements IOLayer {
    private final Charset charset;
    private final CharsetDecoder decoder;
    private final CharsetEncoder encoder;
    private String pendingBytes = "";

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
    public String processInput(String input) {
        // Decode bytes (as chars) to Unicode string
        String combined = pendingBytes + input;

        if (combined.isEmpty()) {
            return "";
        }

        // Convert string to ByteBuffer
        ByteBuffer bb = ByteBuffer.allocate(combined.length());
        for (int i = 0; i < combined.length(); i++) {
            bb.put((byte)(combined.charAt(i) & 0xFF));
        }
        bb.flip();

        // For UTF-8, check if we have complete sequences
        if (charset.equals(StandardCharsets.UTF_8)) {
            int lastCompletePos = findLastCompleteUTF8(bb);
            if (lastCompletePos < bb.limit()) {
                // We have incomplete sequence at the end
                bb.limit(lastCompletePos);
            }
        }

        // Decode using the charset
        CharBuffer cb = CharBuffer.allocate(bb.remaining() * 2);
        CoderResult result = decoder.decode(bb, cb, false);

        // Save any remaining bytes (incomplete sequences)
        pendingBytes = "";
        if (bb.hasRemaining() || bb.limit() < combined.length()) {
            StringBuilder pending = new StringBuilder();
            // Get unprocessed bytes from original position
            int startPos = bb.limit();
            for (int i = startPos; i < combined.length(); i++) {
                pending.append(combined.charAt(i));
            }
            pendingBytes = pending.toString();
        }

        cb.flip();
        return cb.toString();
    }

    private int findLastCompleteUTF8(ByteBuffer bb) {
        int pos = bb.position();
        int limit = bb.limit();
        int lastComplete = pos;

        while (pos < limit) {
            int b = bb.get(pos) & 0xFF;
            int sequenceLength;

            if ((b & 0x80) == 0) {
                sequenceLength = 1;  // ASCII
            } else if ((b & 0xE0) == 0xC0) {
                sequenceLength = 2;  // 2-byte sequence
            } else if ((b & 0xF0) == 0xE0) {
                sequenceLength = 3;  // 3-byte sequence
            } else if ((b & 0xF8) == 0xF0) {
                sequenceLength = 4;  // 4-byte sequence
            } else {
                // Continuation byte or invalid - skip
                pos++;
                continue;
            }

            if (pos + sequenceLength <= limit) {
                // We have a complete sequence
                lastComplete = pos + sequenceLength;
                pos += sequenceLength;
            } else {
                // Incomplete sequence
                break;
            }
        }

        return lastComplete;
    }

    @Override
    public String processOutput(String output) {
        // Encode Unicode string to bytes
        byte[] bytes = output.getBytes(charset);

        // Convert bytes to string where each char holds a byte
        StringBuilder result = new StringBuilder(bytes.length);
        for (byte b : bytes) {
            result.append((char)(b & 0xFF));
        }

        return result.toString();
    }

    @Override
    public void reset() {
        decoder.reset();
        encoder.reset();
        pendingBytes = "";
    }
}
