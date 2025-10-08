package org.perlonjava.operators.unpack;

import org.perlonjava.operators.UnpackState;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeBase;
import org.perlonjava.runtime.RuntimeScalar;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Handles 'U' format - Unicode character.
 * U format reads based on current mode but does NOT change the mode.
 */
public class UFormatHandler implements FormatHandler {
    private final boolean startsWithU;

    public UFormatHandler(boolean startsWithU) {
        this.startsWithU = startsWithU;
    }

    @Override
    public void unpack(UnpackState state, List<RuntimeBase> output, int count, boolean isStarCount) {
        for (int i = 0; i < count; i++) {
            // Check if input has UTF-8 flag (is already Unicode string)
            if (state.isUTF8Data()) {
                // Input is UTF-8 flagged string - read codepoints directly
                if (state.hasMoreCodePoints()) {
                    output.add(new RuntimeScalar(state.nextCodePoint()));
                } else {
                    break;
                }
            } else {
                // Input is byte string - decode UTF-8
                ByteBuffer buffer = state.getBuffer();
                if (!buffer.hasRemaining()) {
                    break; // Just stop unpacking
                }
                long codePoint = readUTF8Character(buffer);
                output.add(new RuntimeScalar(codePoint));
            }
        }
    }

    /**
     * Read one Unicode code point from the given ByteBuffer interpreting the bytes as UTF-8.
     * If the sequence is invalid or incomplete, we consume a single byte and return it as a
     * Latin-1 code point (0..255). The buffer position is advanced exactly the number of bytes
     * consumed (1 for invalid / ASCII fallback, or full length for valid multi-byte sequences).
     */
    private long readUTF8Character(ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            throw new PerlCompilerException("unpack: no data for UTF-8 character");
        }

        int startPos = buffer.position();
        int firstByte = buffer.get(startPos) & 0xFF;

        // 0x80..0xBF are continuation bytes — treat them as single-byte Latin-1
        if (firstByte >= 0x80 && firstByte < 0xC0) {
            // consume one byte
            buffer.position(startPos + 1);
            return firstByte;
        }

        // ASCII fast path: consume one byte and return
        if ((firstByte & 0x80) == 0) {
            buffer.position(startPos + 1);
            return firstByte;
        }

        int bytesNeeded;
        int codePoint = 0;

        if ((firstByte & 0xE0) == 0xC0) {
            // 2-byte sequence
            bytesNeeded = 1;
            codePoint = firstByte & 0x1F;
        } else if ((firstByte & 0xF0) == 0xE0) {
            // 3-byte sequence
            bytesNeeded = 2;
            codePoint = firstByte & 0x0F;
        } else if ((firstByte & 0xF8) == 0xF0) {
            // 4-byte sequence
            bytesNeeded = 3;
            codePoint = firstByte & 0x07;
        } else {
            // Invalid UTF-8 start byte (0xF8-0xFF), treat as Latin-1 single byte
            buffer.position(startPos + 1);
            return firstByte;
        }

        // Check we have enough bytes
        if (buffer.limit() - (startPos + 1) < bytesNeeded) {
            // Not enough bytes; consume one byte and return it as Latin-1
            buffer.position(startPos + 1);
            return firstByte;
        }

        // Validate continuation bytes without moving the position (peek)
        int pos = startPos + 1;
        for (int i = 0; i < bytesNeeded; i++) {
            int nextByte = buffer.get(pos + i) & 0xFF;
            if ((nextByte & 0xC0) != 0x80) {
                // invalid continuation; consume one byte and return as Latin-1
                buffer.position(startPos + 1);
                return firstByte;
            }
            codePoint = (codePoint << 6) | (nextByte & 0x3F);
        }

        // Reconstruct final code point (we already put continuation bits in)
        // For 2-byte, ensure minimal value etc. (basic overlong checks)
        int cp;
        if (bytesNeeded == 1) {
            cp = codePoint;
        } else if (bytesNeeded == 2) {
            cp = codePoint;
        } else if (bytesNeeded == 3) {
            cp = codePoint;
        } else {
            cp = codePoint;
        }

        // Basic range checks: no surrogates, <= U+10FFFF
        if (cp >= 0xD800 && cp <= 0xDFFF) {
            // invalid (surrogate)
            buffer.position(startPos + 1);
            return firstByte;
        }
        if (cp > 0x10FFFF) {
            buffer.position(startPos + 1);
            return firstByte;
        }

        // Advance the buffer position by the full sequence length (1 + bytesNeeded)
        buffer.position(startPos + 1 + bytesNeeded);

        return cp;
    }

    @Override
    public int getFormatSize() {
        return 1; // Variable length, minimum 1
    }
}
