package org.perlonjava.operators.unpack;

import org.perlonjava.operators.UnpackState;
import org.perlonjava.runtime.RuntimeBase;
import org.perlonjava.runtime.RuntimeScalar;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Handler for native long formats (l!, L!) - 8 bytes on 64-bit systems.
 */
public class NativeLongFormatHandler implements FormatHandler {
    private final boolean signed;

    public NativeLongFormatHandler(boolean signed) {
        this.signed = signed;
    }

    @Override
    public void unpack(UnpackState state, List<RuntimeBase> output, int count, boolean isStarCount) {
        // Save current mode
        boolean wasCharacterMode = state.isCharacterMode();

        // Switch to byte mode for numeric reading
        if (wasCharacterMode) {
            state.switchToByteMode();
        }

        ByteBuffer buffer = state.getBuffer();

        for (int i = 0; i < count; i++) {
            if (buffer.remaining() < 8) {
                break;
            }
            // Read 8 bytes for native long
            long value = buffer.getLong();
            if (signed) {
                output.add(new RuntimeScalar(value));
            } else {
                // For unsigned, we need to handle the conversion properly
                if (value < 0) {
                    // Convert to unsigned representation
                    output.add(new RuntimeScalar(Long.toUnsignedString(value)));
                } else {
                    output.add(new RuntimeScalar(value));
                }
            }
        }

        // Restore character mode if it was active
        if (wasCharacterMode) {
            state.switchToCharacterMode();
        }
    }

    @Override
    public int getFormatSize() {
        return 8; // Native long is 8 bytes
    }
}
