package org.perlonjava.operators.unpack;

import org.perlonjava.operators.UnpackState;
import org.perlonjava.runtime.*;
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
            if (state.isCharacterMode() || startsWithU) {
                if (state.hasMoreCodePoints()) {
                    output.add(new RuntimeScalar(state.nextCodePoint()));
                } else {
                    break; // Just stop unpacking
                }
            } else {
                ByteBuffer buffer = state.getBuffer();
                if (!buffer.hasRemaining()) {
                    break; // Just stop unpacking
                }
                long codePoint = readUTF8Character(buffer);
                output.add(new RuntimeScalar(codePoint));
            }
        }
    }

    private long readUTF8Character(ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            throw new PerlCompilerException("unpack: no data for UTF-8 character");
        }

        int firstByte = buffer.get() & 0xFF;

        // For bytes 0x80-0xFF that aren't valid UTF-8 start bytes,
        // treat them as Latin-1 characters
        if (firstByte >= 0x80 && firstByte < 0xC0) {
            return firstByte;
        }

        // Standard UTF-8 decoding
        int bytesNeeded;
        int codePoint;

        if ((firstByte & 0x80) == 0) {
            // 1-byte ASCII
            return firstByte;
        } else if ((firstByte & 0xE0) == 0xC0) {
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
            // Invalid UTF-8 start byte (0xF8-0xFF), treat as Latin-1
            return firstByte;
        }

        // Check if we have enough bytes for the sequence
        if (buffer.remaining() < bytesNeeded) {
            // Not enough bytes for valid UTF-8, rewind and treat as Latin-1
            buffer.position(buffer.position() - 1);
            return firstByte;
        }

        // Read continuation bytes
        int savedPosition = buffer.position();
        for (int i = 0; i < bytesNeeded; i++) {
            int nextByte = buffer.get() & 0xFF;
            if ((nextByte & 0xC0) != 0x80) {
                // Invalid continuation byte, rewind and treat original as Latin-1
                buffer.position(savedPosition - 1);
                return firstByte;
            }
            codePoint = (codePoint << 6) | (nextByte & 0x3F);
        }

        return codePoint;
    }

    @Override
    public int getFormatSize() {
        return 1; // Variable length, minimum 1
    }
}
