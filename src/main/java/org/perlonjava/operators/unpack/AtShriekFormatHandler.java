package org.perlonjava.operators.unpack;

import org.perlonjava.operators.UnpackState;
import org.perlonjava.runtime.RuntimeBase;

import java.util.List;

/**
 * Handler for the '@!' format character in unpack operations.
 * This format sets the absolute position in the string using byte offsets.
 * 
 * <p>Unlike '@' which uses character positions for UTF-8 strings,
 * '@!' always uses byte positions even for UTF-8 strings.
 */
public class AtShriekFormatHandler implements FormatHandler {
    @Override
    public void unpack(UnpackState state, List<RuntimeBase> output, int count, boolean isStarCount) {
        // Set absolute byte position by switching to byte mode temporarily
        boolean wasCharMode = state.isCharacterMode();
        if (wasCharMode) {
            state.switchToByteMode();
        }
        state.setPosition(count);
        if (wasCharMode) {
            state.switchToCharacterMode();
        }
        
        // @! doesn't produce any output values
    }

    @Override
    public int getFormatSize() {
        return 0; // @! doesn't consume data
    }
}
