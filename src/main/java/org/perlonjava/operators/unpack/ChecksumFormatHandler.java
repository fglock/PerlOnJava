package org.perlonjava.operators.unpack;

import org.perlonjava.operators.UnpackState;
import org.perlonjava.runtime.RuntimeBase;
import org.perlonjava.runtime.RuntimeScalar;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Handles '%' format - checksum.
 */
public class ChecksumFormatHandler implements FormatHandler {
    @Override
    public void unpack(UnpackState state, List<RuntimeBase> output, int count, boolean isStarCount) {
        // Calculate checksum of the data processed so far
        ByteBuffer buffer = state.getBuffer();
        int currentPos = buffer.position();
        
        // Calculate checksum using the specified bit count (default 16 if not specified)
        int bits = (count > 0) ? count : 16;
        long checksum = 0;
        
        // Sum all bytes processed so far
        byte[] data = buffer.array();
        for (int i = 0; i < currentPos; i++) {
            checksum += (data[i] & 0xFF);
        }
        
        // Apply bit mask to get the specified number of bits
        long mask = (1L << bits) - 1;
        checksum &= mask;
        
        output.add(new RuntimeScalar(checksum));
    }

    @Override
    public int getFormatSize() {
        return 0; // Checksum doesn't consume bytes
    }
}
