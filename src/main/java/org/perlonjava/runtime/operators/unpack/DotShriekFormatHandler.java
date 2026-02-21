package org.perlonjava.runtime.operators.unpack;

import org.perlonjava.runtime.operators.UnpackState;
import org.perlonjava.runtime.runtimetypes.RuntimeBase;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.util.List;

/**
 * Handler for the '.!' format in unpack - returns the current byte offset in the string.
 * 
 * <p>The dot-shriek format returns the current BYTE position with different behaviors based on count:
 * <ul>
 *   <li>.!0 - Returns 0 (position relative to current position = 0)</li>
 *   <li>.! or .!1 - Returns byte position relative to current (innermost) group</li>
 *   <li>.!2 - Returns byte position relative to parent group (2nd level up)</li>
 *   <li>.!N - Returns byte position relative to Nth group level up</li>
 *   <li>.!* - Returns absolute byte position (relative to start of string)</li>
 * </ul>
 * 
 * <p>Similar to DotFormatHandler but always works in byte domain, not character domain.
 * 
 * @see DotFormatHandler
 * @see UnpackState#getRelativeBytePosition(int)
 */
public class DotShriekFormatHandler implements FormatHandler {
    @Override
    public void unpack(UnpackState state, List<RuntimeBase> values, int count, boolean isStarCount) {
        // Get the current byte position
        int currentBytePos = state.getBytePosition();

        // Parse optional positioning argument
        if (count == 0) {
            // .!0 returns 0 (relative to current position)
            values.add(new RuntimeScalar(0));
        } else if (isStarCount) {
            // .!* means relative to start of string (absolute byte position)
            values.add(new RuntimeScalar(currentBytePos));
        } else if (count > state.getGroupDepth()) {
            // .!N where N > depth: byte position relative to outer context (absolute position)
            // This happens when asking for more levels than exist
            values.add(new RuntimeScalar(currentBytePos));
        } else {
            // .!N where 1 <= N <= depth: relative to Nth group level up (in byte domain)
            int relativeBytePos = state.getRelativeBytePosition(count);
            values.add(new RuntimeScalar(relativeBytePos));
        }
    }

    @Override
    public int getFormatSize() {
        return 0;  // Doesn't consume any bytes
    }
}