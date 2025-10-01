package org.perlonjava.operators.unpack;

import org.perlonjava.operators.UnpackState;
import org.perlonjava.runtime.RuntimeBase;
import org.perlonjava.runtime.RuntimeScalar;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Handles 'W' format - Wide character (Unicode character without range validation).
 * W format is like U format but doesn't validate Unicode code points.
 * It reads Unicode characters from the string in character mode,
 * or decodes UTF-8 bytes in byte mode.
 */
public class WFormatHandler implements FormatHandler {

    @Override
    public void unpack(UnpackState state, List<RuntimeBase> output, int count, boolean isStarCount) {
        for (int i = 0; i < count; i++) {
            if (!state.isCharacterMode()) {
                // In character mode, read Unicode code points directly
                if (state.hasMoreCodePoints()) {
                    output.add(new RuntimeScalar(state.nextCodePoint()));
                } else {
                    break; // Just stop unpacking
                }
            } else {
                // In byte mode, check if we have a Unicode string or byte string
                if (state.isUTF8Data()) {
                    // Input contains high Unicode characters, read directly as Unicode
                    if (state.hasMoreCodePoints()) {
                        output.add(new RuntimeScalar(state.nextCodePoint()));
                    } else {
                        break;
                    }
                } else {
                    // Input is byte string, decode UTF-8
                    ByteBuffer buffer = state.getBuffer();
                    if (!buffer.hasRemaining()) {
                        break; // Just stop unpacking
                    }
                    long codePoint = readUTF8Character(buffer);
                    output.add(new RuntimeScalar(codePoint));
                }
            }
        }
    }

    /**
     * Read one Unicode code point from the given ByteBuffer interpreting the bytes as UTF-8.
     * Unlike U format, W format doesn't validate the code point range.
     * If the sequence is invalid or incomplete, we consume a single byte and return it as a
     * Latin-1 code point (0..255).
     */
    private long readUTF8Character(ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            return 0; // Return 0 instead of throwing exception
        }

        int startPos = buffer.position();
        int firstByte = buffer.get(startPos) & 0xFF;

        // 0x80..0xBF are continuation bytes — treat them as single-byte Latin-1
        if (firstByte >= 0x80 && firstByte < 0xC0) {
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
            // Invalid UTF-8 start byte, treat as Latin-1 single byte
            buffer.position(startPos + 1);
            return firstByte;
        }

        // Check we have enough bytes
        if (buffer.limit() - (startPos + 1) < bytesNeeded) {
            buffer.position(startPos + 1);
            return firstByte;
        }

        // Validate continuation bytes
        int pos = startPos + 1;
        for (int i = 0; i < bytesNeeded; i++) {
            int nextByte = buffer.get(pos + i) & 0xFF;
            if ((nextByte & 0xC0) != 0x80) {
                buffer.position(startPos + 1);
                return firstByte;
            }
            codePoint = (codePoint << 6) | (nextByte & 0x3F);
        }

        // Advance the buffer position by the full sequence length
        buffer.position(startPos + 1 + bytesNeeded);

        // W format doesn't validate the code point range like U format does
        // Just return whatever we decoded
        return codePoint;
    }

    @Override
    public int getFormatSize() {
        return 1; // Variable length, minimum 1
    }
}
