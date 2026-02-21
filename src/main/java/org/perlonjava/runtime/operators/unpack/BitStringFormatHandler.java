package org.perlonjava.runtime.operators.unpack;

import org.perlonjava.runtime.operators.UnpackState;
import org.perlonjava.runtime.runtimetypes.RuntimeBase;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Handles bit string formats 'b' and 'B'.
 */
public class BitStringFormatHandler implements FormatHandler {
    private final char format;

    public BitStringFormatHandler(char format) {
        this.format = format;
    }

    @Override
    public void unpack(UnpackState state, List<RuntimeBase> output, int count, boolean isStarCount) {
        StringBuilder bitString = new StringBuilder();

        if (state.isCharacterMode()) {
            // In character mode, read bits from character values
            int bitsToRead = isStarCount ? state.remainingCodePoints() * 8 : count;

            while (bitString.length() < bitsToRead && state.hasMoreCodePoints()) {
                int charValue = state.nextCodePoint();
                int byteValue = charValue & 0xFF; // Get low byte

                for (int bit = 0; bit < 8 && bitString.length() < bitsToRead; bit++) {
                    if (format == 'b') {
                        // Low bit first
                        bitString.append((byteValue & (1 << bit)) != 0 ? '1' : '0');
                    } else {
                        // High bit first
                        bitString.append((byteValue & (1 << (7 - bit))) != 0 ? '1' : '0');
                    }
                }
            }
        } else {
            // In byte mode, read from buffer
            ByteBuffer buffer = state.getBuffer();
            int bitsToRead = isStarCount ? buffer.remaining() * 8 : count;
            int bytesToRead = (bitsToRead + 7) / 8;
            int actualBytesToRead = Math.min(bytesToRead, buffer.remaining());

            byte[] bytes = new byte[actualBytesToRead];
            buffer.get(bytes);

            int bitsToProcess = Math.min(bitsToRead, actualBytesToRead * 8);
            for (int i = 0; i < bitsToProcess; i++) {
                int byteIndex = i / 8;
                int bitIndex = i % 8;
                boolean bit = (bytes[byteIndex] & (1 << (format == 'b' ? bitIndex : 7 - bitIndex))) != 0;
                bitString.append(bit ? '1' : '0');
            }
        }

        output.add(new RuntimeScalar(bitString.toString()));
    }
}