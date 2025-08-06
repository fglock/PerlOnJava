package org.perlonjava.operators.unpack;

import org.perlonjava.operators.UnpackState;
import org.perlonjava.runtime.*;
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
                // In character mode, read Unicode codepoints
                if (state.hasMoreCodePoints()) {
                    output.add(new RuntimeScalar(state.nextCodePoint()));
                } else if (!isStarCount) {
                    throw new PerlCompilerException("unpack: not enough data");
                } else {
                    break; // Star count, stop when no more data
                }
            } else {
                // In byte mode, read single bytes
                ByteBuffer buffer = state.getBuffer();
                if (buffer == null || buffer.remaining() < 1) {
                    if (isStarCount) break;
                    throw new PerlCompilerException("unpack: not enough data");
                }
                output.add(new RuntimeScalar(buffer.get() & 0xFF));
            }
        }
    }

    @Override
    public int getFormatSize() {
        return 1;
    }
}