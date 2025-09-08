package org.perlonjava.operators.unpack;

import org.perlonjava.operators.UnpackState;
import org.perlonjava.runtime.RuntimeBase;

import java.util.List;

/**
 * Interface for handling specific unpack format characters.
 */
public interface FormatHandler {
    /**
     * Unpacks data according to the format character.
     *
     * @param state       The current unpack state
     * @param output      The list to add unpacked values to
     * @param count       The number of items to unpack
     * @param isStarCount Whether the count is '*' (unpack all remaining)
     */
    void unpack(UnpackState state, List<RuntimeBase> output, int count, boolean isStarCount);

    /**
     * Gets the size in bytes required for this format (for fixed-size formats).
     *
     * @return Size in bytes, or 0 for variable-size formats
     */
    default int getFormatSize() {
        return 0;
    }
}