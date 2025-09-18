package org.perlonjava.operators.unpack;

import org.perlonjava.operators.Pack;
import org.perlonjava.operators.UnpackState;
import org.perlonjava.runtime.RuntimeBase;
import org.perlonjava.runtime.RuntimeScalar;

import java.nio.ByteBuffer;
import java.util.List;

public class PointerFormatHandler implements FormatHandler {

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

            // Read 4 bytes as pointer (hashCode) in little-endian order
            int ptr = 0;
            for (int j = 0; j < 4; j++) {
                int b = buffer.get() & 0xFF;
                ptr |= (b << (j * 8));
            }

            // DEBUG: unpack 'p' hashCode=" + ptr

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