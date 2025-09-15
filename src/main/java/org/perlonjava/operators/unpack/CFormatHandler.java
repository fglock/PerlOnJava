package org.perlonjava.operators.unpack;

import org.perlonjava.operators.UnpackState;
import org.perlonjava.runtime.RuntimeBase;
import org.perlonjava.runtime.RuntimeScalar;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Handles 'c' and 'C' formats - signed/unsigned char (byte).
 */
public class CFormatHandler implements FormatHandler {
    private final boolean signed;

    public CFormatHandler(boolean signed) {
        this.signed = signed;
    }

    @Override
    public void unpack(UnpackState state, List<RuntimeBase> output, int count, boolean isStarCount) {
        for (int i = 0; i < count; i++) {
            if (state.isCharacterMode()) {
                if (state.hasMoreCodePoints()) {
                    int value = state.nextCodePoint();
                    if (signed && value > 127) {
                        // Convert to signed byte value
                        value = value - 256;
                    }
                    output.add(new RuntimeScalar(value));
                } else {
                    break; // Just stop unpacking
                }
            } else {
                ByteBuffer buffer = state.getBuffer();
                if (buffer.hasRemaining()) {
                    if (signed) {
                        // Get as signed byte
                        output.add(new RuntimeScalar(buffer.get()));
                    } else {
                        // Get as unsigned byte
                        output.add(new RuntimeScalar(buffer.get() & 0xFF));
                    }
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