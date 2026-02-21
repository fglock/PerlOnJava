package org.perlonjava.runtime.operators.unpack;

import org.perlonjava.runtime.operators.UnpackState;
import org.perlonjava.runtime.runtimetypes.RuntimeBase;

import java.util.List;

/**
 * Handler for the '@' format character in unpack operations.
 * This format sets the absolute position in the string.
 */
public class AtFormatHandler implements FormatHandler {
    @Override
    public void unpack(UnpackState state, List<RuntimeBase> output, int count, boolean isStarCount) {
        // DEBUG: AtFormatHandler.unpack called with count=" + count
        // DEBUG: Current position before setPosition: " +
        // (state.isCharacterMode() ? state.getCurrentCodePointIndex() : state.getBuffer().position())

        // Set absolute position
        state.setPosition(count);

        // DEBUG: Current position after setPosition: " +
        // (state.isCharacterMode() ? state.getCurrentCodePointIndex() : state.getBuffer().position())

        // @ doesn't produce any output values
    }

    @Override
    public int getFormatSize() {
        return 0; // @ doesn't consume data
    }
}