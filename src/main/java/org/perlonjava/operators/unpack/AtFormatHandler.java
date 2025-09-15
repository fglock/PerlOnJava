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
    public void unpack(UnpackState state, List<RuntimeBase> output, int count, boolean isStarCount) {
        System.err.println("DEBUG: AtFormatHandler.unpack called with count=" + count);
        System.err.println("DEBUG: Current position before setPosition: " +
            (state.isCharacterMode() ? state.getCurrentCodePointIndex() : state.getBuffer().position()));

        // Set absolute position
        state.setPosition(count);

        System.err.println("DEBUG: Current position after setPosition: " +
            (state.isCharacterMode() ? state.getCurrentCodePointIndex() : state.getBuffer().position()));

        // @ doesn't produce any output values
    }

    @Override
    public int getFormatSize() {
        return 0; // @ doesn't consume data
    }
}