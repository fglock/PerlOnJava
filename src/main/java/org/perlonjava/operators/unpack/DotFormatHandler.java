package org.perlonjava.operators.unpack;

import org.perlonjava.operators.UnpackState;
import org.perlonjava.runtime.RuntimeBase;
import org.perlonjava.runtime.RuntimeScalar;

import java.util.List;

/**
 * Handler for the '.' format in unpack - returns the current offset in the string.
 * 
 * <p>The dot format returns the current position with different behaviors based on count:
 * <ul>
 *   <li>.0 - Returns 0 (position relative to current position = 0)</li>
 *   <li>. or .1 - Returns position relative to current (innermost) group</li>
 *   <li>.2 - Returns position relative to parent group (2nd level up)</li>
 *   <li>.N - Returns position relative to Nth group level up</li>
 *   <li>.* - Returns absolute position (relative to start of string)</li>
 * </ul>
 * 
 * <p>Example: unpack("x3(X2.2)", $data)
 * - x3: position = 3
 * - (: start group at position 3 (group base = 3)
 * - X2: back up 2 (position = 1)
 * - .2: return position relative to 2nd group up (outer context)
 * 
 * @see UnpackState#getRelativePosition(int)
 */
public class DotFormatHandler implements FormatHandler {
    private static final boolean TRACE_UNPACK = false;
    
    @Override
    public void unpack(UnpackState state, List<RuntimeBase> values, int count, boolean isStarCount) {
        // Get the current position (absolute and relative to current group)
        int currentPos = state.getPosition();

        if (TRACE_UNPACK) {
            System.err.println("TRACE DotFormatHandler.unpack:");
            System.err.println("  currentPos: " + currentPos);
            System.err.println("  count: " + count + ", isStarCount: " + isStarCount);
            System.err.println("  groupDepth: " + state.getGroupDepth());
            System.err.flush();
        }

        // Parse optional positioning argument
        if (count == 0) {
            // .0 returns 0 (relative to current position)
            values.add(new RuntimeScalar(0));
        } else if (isStarCount) {
            // .* means relative to start of string (absolute position)
            values.add(new RuntimeScalar(currentPos));
        } else if (count > state.getGroupDepth()) {
            // .N where N > depth: position relative to outer context (absolute position)
            // This happens when asking for more levels than exist
            values.add(new RuntimeScalar(currentPos));
        } else {
            // .N where 1 <= N <= depth: relative to Nth group level up
            int relativePos = state.getRelativePosition(count);
            values.add(new RuntimeScalar(relativePos));
        }
    }

    @Override
    public int getFormatSize() {
        return 0;  // Doesn't consume any bytes
    }
}