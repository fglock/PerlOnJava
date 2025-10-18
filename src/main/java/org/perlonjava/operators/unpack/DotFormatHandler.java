package org.perlonjava.operators.unpack;

import org.perlonjava.operators.UnpackState;
import org.perlonjava.runtime.RuntimeBase;
import org.perlonjava.runtime.RuntimeScalar;

import java.util.List;

/**
 * Handler for the '.' format in unpack - returns the current offset in the string
 */
public class DotFormatHandler implements FormatHandler {
    private static final boolean TRACE_UNPACK = false;
    
    @Override
    public void unpack(UnpackState state, List<RuntimeBase> values, int count, boolean isStarCount) {
        // Get the current position (absolute and relative to current group)
        int currentPos = state.getPosition();
        int relativePos = state.getRelativePosition();

        if (TRACE_UNPACK) {
            System.err.println("TRACE DotFormatHandler.unpack:");
            System.err.println("  currentPos: " + currentPos);
            System.err.println("  relativePos: " + relativePos);
            System.err.println("  hasGroupBase: " + state.hasGroupBase());
            System.err.println("  count: " + count + ", isStarCount: " + isStarCount);
            System.err.flush();
        }

        // Parse optional positioning argument
        if (count == 0) {
            // .0 returns 0 (relative to current position)
            values.add(new RuntimeScalar(0));
        } else if (isStarCount) {
            // .* means relative to start of string
            values.add(new RuntimeScalar(currentPos));
        } else {
            // .N (without *) inside a group should be relative to current group baseline
            if (state.hasGroupBase()) {
                values.add(new RuntimeScalar(relativePos));
            } else {
                values.add(new RuntimeScalar(currentPos));
            }
        }
    }

    @Override
    public int getFormatSize() {
        return 0;  // Doesn't consume any bytes
    }
}