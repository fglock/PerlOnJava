package org.perlonjava.operators.unpack;

import org.perlonjava.operators.UnpackState;
import org.perlonjava.runtime.RuntimeBase;
import org.perlonjava.runtime.RuntimeScalar;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Handles 'x' format - byte zero.
 */
public class XFormatHandler implements FormatHandler {
    @Override
    public void unpack(UnpackState state, List<RuntimeBase> output, int count, boolean isStarCount) {
        for (int i = 0; i < count; i++) {
            if (state.isCharacterMode()) {
                output.add(new RuntimeScalar("\0"));
            } else {
                output.add(new RuntimeScalar("\0"));
            }
        }
    }

    @Override
    public int getFormatSize() {
        return 1;
    }
}