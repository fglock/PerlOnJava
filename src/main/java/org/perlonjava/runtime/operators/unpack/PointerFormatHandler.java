package org.perlonjava.runtime.operators.unpack;

import org.perlonjava.runtime.operators.Pack;
import org.perlonjava.runtime.operators.UnpackState;
import org.perlonjava.runtime.runtimetypes.RuntimeBase;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.nio.ByteBuffer;
import java.util.List;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.BYTE_STRING;

public class PointerFormatHandler implements FormatHandler {
    private final boolean bigEndian;
    private final boolean lengthLimited;

    public PointerFormatHandler(boolean lengthLimited) {
        this(false, lengthLimited);
    }

    public PointerFormatHandler(boolean bigEndian, boolean lengthLimited) {
        this.bigEndian = bigEndian;
        this.lengthLimited = lengthLimited;
    }

    @Override
    public int getFormatSize() {
        return 8; // 64-bit pointer (to match pack)
    }

    @Override
    public void unpack(UnpackState state, List<RuntimeBase> result, int count, boolean isStarCount) {
        ByteBuffer buffer = state.getBuffer();

        // p's count repeats pointers.  P's count is the number of bytes to
        // copy from one pointer, not a pointer repeat count.
        int pointerCount = lengthLimited ? 1 : count;
        for (int i = 0; i < pointerCount; i++) {
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
                    ptrLong |= ((long) (bytes[j] & 0xFF) << (j * 8));
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
                    int end = str.length();
                    if (lengthLimited) {
                        end = Math.min(end, count);
                    } else {
                        int nul = str.indexOf('\0');
                        end = nul >= 0 ? nul : end;
                    }
                    result.add(new RuntimeScalar(str.substring(0, end).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)));
                } else {
                    RuntimeScalar empty = new RuntimeScalar("");
                    empty.type = BYTE_STRING;
                    result.add(empty);
                }
            }
        }
    }
}
