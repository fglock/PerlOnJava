package org.perlonjava.runtime.operators.unpack;

import org.perlonjava.runtime.operators.UnpackState;
import org.perlonjava.runtime.runtimetypes.RuntimeBase;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Handles 'x' format - skip bytes, don't add any values to output.
 */
public class XFormatHandler implements FormatHandler {
    private static final boolean TRACE_X = false;
    @Override
    public void unpack(UnpackState state, List<RuntimeBase> output, int count, boolean isStarCount) {
        // x format should skip bytes but not add any values to the result
        if (state.isCharacterMode()) {
            // In character mode, skip code points
            for (int i = 0; i < count && state.hasMoreCodePoints(); i++) {
                state.nextCodePoint();
            }
        } else {
            // In byte mode, skip bytes
            ByteBuffer buffer = state.getBuffer();
            int toSkip = Math.min(count, buffer.remaining());
            buffer.position(buffer.position() + toSkip);
        }
        // Don't add anything to output - x just skips
    }

    @Override
    public int getFormatSize() {
        return 1;
    }
}