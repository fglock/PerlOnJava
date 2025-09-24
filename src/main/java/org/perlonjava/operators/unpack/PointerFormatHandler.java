package org.perlonjava.operators.unpack;

import org.perlonjava.operators.Pack;
import org.perlonjava.operators.UnpackState;
import org.perlonjava.runtime.RuntimeBase;
import org.perlonjava.runtime.RuntimeScalar;

import java.nio.ByteBuffer;
import java.util.List;

public class PointerFormatHandler implements FormatHandler {
    private final boolean bigEndian;

    public PointerFormatHandler() {
        this(false); // Default to little-endian
    }

    public PointerFormatHandler(boolean bigEndian) {
        this.bigEndian = bigEndian;
    }

    @Override
    public int getFormatSize() {
        return 4; // 32-bit pointer (hashCode)
    }

    @Override
    public void unpack(UnpackState state, List<RuntimeBase> result, int count, boolean isStarCount) {
        ByteBuffer buffer = state.getBuffer();

        for (int i = 0; i < count; i++) {
            if (buffer.remaining() < 4) {
                break;
            }

            // Read 4 bytes and convert to canonical hash code
            // The endianness affects byte storage, but we need the same logical hash code
            byte[] bytes = new byte[4];
            buffer.get(bytes);
            
            int ptr;
            if (bigEndian) {
                // Big-endian: most significant byte first
                ptr = 0;
                for (int j = 0; j < 4; j++) {
                    ptr = (ptr << 8) | (bytes[j] & 0xFF);
                }
            } else {
                // Little-endian: least significant byte first
                ptr = 0;
                for (int j = 0; j < 4; j++) {
                    ptr |= ((bytes[j] & 0xFF) << (j * 8));
                }
            }

            // DEBUG: unpack 'p' hashCode=" + ptr + " (bigEndian=" + bigEndian + ")
    
            if (ptr == 0) {
                result.add(new RuntimeScalar()); // undef
            } else {
                String str = Pack.getPointerString(ptr);
                if (str != null) {
                    result.add(new RuntimeScalar(str));
                } else {
                    result.add(new RuntimeScalar(""));
                }
            }
        }
    }
}