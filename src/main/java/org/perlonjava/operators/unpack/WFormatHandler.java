package org.perlonjava.operators.unpack;

import org.perlonjava.operators.UnpackState;
import org.perlonjava.runtime.*;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Handles 'W' format - Converts each byte to its unsigned value.
 */
public class WFormatHandler implements FormatHandler {

    @Override
    public void unpack(UnpackState state, List<RuntimeBase> output, int count, boolean isStarCount) {
        // W always operates in byte mode
        if (state.isCharacterMode()) {
            state.switchToByteMode();
        }

        ByteBuffer buffer = state.getBuffer();

        if (isStarCount) {
            // Read all remaining bytes
            while (buffer.hasRemaining()) {
                int value = buffer.get() & 0xFF;
                output.add(new RuntimeScalar(value));
            }
        } else {
            // Read exactly 'count' bytes
            for (int i = 0; i < count; i++) {
                if (!buffer.hasRemaining()) {
                    throw new PerlCompilerException("unpack: not enough data");
                }
                int value = buffer.get() & 0xFF;
                output.add(new RuntimeScalar(value));
            }
        }
    }

    @Override
    public int getFormatSize() {
        return 1; // Each W reads exactly 1 byte
    }
}
