package org.perlonjava.operators.unpack;

import org.perlonjava.operators.UnpackState;
import org.perlonjava.runtime.RuntimeBase;
import org.perlonjava.runtime.RuntimeScalar;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Handles 'C' format - unsigned char (byte).
 */
public class CFormatHandler implements FormatHandler {
    @Override
    public void unpack(UnpackState state, List<RuntimeBase> output, int count, boolean isStarCount) {
        for (int i = 0; i < count; i++) {
            if (state.isCharacterMode()) {
                if (state.hasMoreCodePoints()) {
                    output.add(new RuntimeScalar(state.nextCodePoint()));
                } else {
                    break; // Just stop unpacking
                }
            } else {
                ByteBuffer buffer = state.getBuffer();
                if (buffer.hasRemaining()) {
                    output.add(new RuntimeScalar(buffer.get() & 0xFF));
                } else {
                    break; // Just stop unpacking
                }
            }
        }
    }

    @Override
    public int getFormatSize() {
        return 1;
    }
}