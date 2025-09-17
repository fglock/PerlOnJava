package org.perlonjava.operators.unpack;

import org.perlonjava.operators.unpack.FormatHandler;
import org.perlonjava.operators.UnpackState;
import org.perlonjava.runtime.RuntimeBase;
import org.perlonjava.runtime.RuntimeScalar;

import java.util.List;

/**
 * Handler for the '.' format in unpack - returns the current offset in the string
 */
public class DotFormatHandler implements FormatHandler {
    @Override
    public void unpack(UnpackState state, List<RuntimeBase> values, int count, boolean isStarCount) {
        // Get the current position
        int currentPos = state.getPosition();

        // Parse optional positioning argument
        if (count == 0) {
            // .0 means relative to current position (which is 0)
            values.add(new RuntimeScalar(0));
        } else if (isStarCount) {
            // .* means relative to start of string
            values.add(new RuntimeScalar(currentPos));
        } else {
            // .N means relative to start of Nth innermost group
            // For now, just return the current position
            // TODO: Implement proper group tracking
            values.add(new RuntimeScalar(currentPos));
        }
    }

    @Override
    public int getFormatSize() {
        return 0;  // Doesn't consume any bytes
    }
}