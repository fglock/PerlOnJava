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
        return 8; // 64-bit pointer (to match pack)
    }

    @Override
    public void unpack(UnpackState state, List<RuntimeBase> result, int count, boolean isStarCount) {
        ByteBuffer buffer = state.getBuffer();

        for (int i = 0; i < count; i++) {
            if (buffer.remaining() < 8) {
                break;
            }

            // Read 8 bytes and convert to pointer value
            // Only the lower 32 bits matter for the hashCode
            byte[] bytes = new byte[8];
            buffer.get(bytes);
            
            long ptrLong;
            if (bigEndian) {
                // Big-endian: most significant byte first
                ptrLong = 0;
                for (int j = 0; j < 8; j++) {
                    ptrLong = (ptrLong << 8) | (bytes[j] & 0xFF);
                }
            } else {
                // Little-endian: least significant byte first
                ptrLong = 0;
                for (int j = 0; j < 8; j++) {
                    ptrLong |= ((long)(bytes[j] & 0xFF) << (j * 8));
                }
            }
            
            // Extract the int hashCode from the long pointer value
            int ptr = (int) ptrLong;

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