package org.perlonjava.operators.unpack;

import org.perlonjava.runtime.RuntimeBase;
import org.perlonjava.operators.UnpackState;
import java.util.List;

/**
 * Handler for the '@' format character in unpack operations.
 * This format sets the absolute position in the string.
 */
public class AtFormatHandler implements FormatHandler {
    @Override
    public void unpack(UnpackState state, List<RuntimeBase> values, int count, boolean isStarCount) {
        // @ doesn't produce a value, it just moves the position
        System.err.println("DEBUG: AtFormatHandler called with count=" + count + ", isStarCount=" + isStarCount);
        if (isStarCount) {
            // @* means go to end of string
            state.setPosition(state.getTotalLength());
        } else {
            // @N means go to position N (0-based)
            state.setPosition(count);
        }
        System.err.println("DEBUG: After setPosition, codePointIndex should be " + count);
    }

    @Override
    public int getFormatSize() {
        return 0; // @ doesn't consume data
    }
}