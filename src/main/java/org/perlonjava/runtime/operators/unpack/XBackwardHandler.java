package org.perlonjava.runtime.operators.unpack;

import org.perlonjava.runtime.operators.UnpackState;
import org.perlonjava.runtime.runtimetypes.RuntimeBase;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Handles 'X' format - backup (move backward).
 */
public class XBackwardHandler implements FormatHandler {
    @Override
    public void unpack(UnpackState state, List<RuntimeBase> output, int count, boolean isStarCount) {
        // X format moves backward but doesn't add any values to the result
        if (state.isCharacterMode()) {
            // In character mode, move backward by count code points
            int currentPos = state.getCurrentCodePointIndex();
            int newPos = Math.max(0, currentPos - count);
            state.setCodePointIndex(newPos);
        } else {
            // In byte mode, move backward in the buffer
            ByteBuffer buffer = state.getBuffer();
            int currentPos = buffer.position();
            int newPos = Math.max(0, currentPos - count);
            buffer.position(newPos);
        }
        // Don't add anything to output - X just moves backward
    }

    @Override
    public int getFormatSize() {
        return 1;
    }
}