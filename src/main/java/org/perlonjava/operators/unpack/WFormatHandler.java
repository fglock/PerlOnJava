package org.perlonjava.operators.unpack;

import org.perlonjava.operators.UnpackState;
import org.perlonjava.runtime.*;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Handles 'W' format - BER-compressed integer (variable-length UTF-8).
 */
public class WFormatHandler implements FormatHandler {

    @Override
    public void unpack(UnpackState state, List<RuntimeBase> output, int count, boolean isStarCount) {
        // W always operates in byte mode
        if (state.isCharacterMode()) {
            state.switchToByteMode();
        }

        ByteBuffer buffer = state.getBuffer();

        for (int i = 0; i < count; i++) {
            if (!buffer.hasRemaining()) {
                if (isStarCount) break;
                throw new PerlCompilerException("unpack: not enough data");
            }

            long value = readBERInteger(buffer);
            output.add(new RuntimeScalar(value));
        }
    }

    private long readBERInteger(ByteBuffer buffer) {
        long result = 0;
        int shift = 0;

        while (buffer.hasRemaining()) {
            int b = buffer.get() & 0xFF;

            if ((b & 0x80) == 0) {
                // Last byte
                result |= ((long) b) << shift;
                return result;
            } else {
                // More bytes follow
                result |= ((long) (b & 0x7F)) << shift;
                shift += 7;

                if (shift > 63) {
                    throw new PerlCompilerException("unpack: W format integer overflow");
                }
            }
        }

        throw new PerlCompilerException("unpack: incomplete W format integer");
    }
}