package org.perlonjava.operators.unpack;

import org.perlonjava.operators.UnpackState;
import org.perlonjava.runtime.RuntimeBase;
import org.perlonjava.runtime.RuntimeScalar;

import java.io.ByteArrayOutputStream;
import java.util.List;

public class UuencodeFormatHandler implements FormatHandler {

    @Override
    public int getFormatSize() {
        return -1; // Variable size
    }

    @Override
    public void unpack(UnpackState state, List<RuntimeBase> result, int count, boolean isStarCount) {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();

        while (state.hasMoreCodePoints()) {
            // Read line length
            int lengthChar = state.nextCodePoint();
            if (lengthChar == '\n' || lengthChar == '\r') {
                continue; // Skip newlines
            }

            int lineLength = (lengthChar - 32) & 0x3F;
            if (lineLength == 0) {
                break; // End of uuencoded data
            }

            // Decode groups of 4 characters to 3 bytes
            int bytesDecoded = 0;
            while (bytesDecoded < lineLength && state.hasMoreCodePoints()) {
                int c1 = state.hasMoreCodePoints() ? state.nextCodePoint() : 0;
                int c2 = state.hasMoreCodePoints() ? state.nextCodePoint() : 0;
                int c3 = state.hasMoreCodePoints() ? state.nextCodePoint() : 0;
                int c4 = state.hasMoreCodePoints() ? state.nextCodePoint() : 0;

                // Convert from printable (backtick becomes 0)
                c1 = (c1 == 96) ? 0 : (c1 - 32) & 0x3F;
                c2 = (c2 == 96) ? 0 : (c2 - 32) & 0x3F;
                c3 = (c3 == 96) ? 0 : (c3 - 32) & 0x3F;
                c4 = (c4 == 96) ? 0 : (c4 - 32) & 0x3F;

                // Decode to bytes
                if (bytesDecoded < lineLength) {
                    decoded.write((c1 << 2) | (c2 >> 4));
                    bytesDecoded++;
                }
                if (bytesDecoded < lineLength) {
                    decoded.write((c2 << 4) | (c3 >> 2));
                    bytesDecoded++;
                }
                if (bytesDecoded < lineLength) {
                    decoded.write((c3 << 6) | c4);
                    bytesDecoded++;
                }
            }

            // Skip to next line
            while (state.hasMoreCodePoints()) {
                int ch = state.nextCodePoint();
                if (ch == '\n' || ch == '\r') {
                    break;
                }
            }
        }

        result.add(new RuntimeScalar(decoded.toByteArray()));
    }
}