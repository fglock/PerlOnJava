package org.perlonjava.runtime.operators.unpack;

import org.perlonjava.runtime.operators.UnpackState;
import org.perlonjava.runtime.runtimetypes.RuntimeBase;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

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

            // Trailing spaces may have been stripped from a uuencoded line.
            // Missing sextets are zero; a newline must never become payload
            // or let this line consume characters from the following line.
            java.util.ArrayList<Integer> line = new java.util.ArrayList<>();
            while (state.hasMoreCodePoints()) {
                int ch = state.nextCodePoint();
                if (ch == '\n' || ch == '\r') break;
                line.add((ch - 32) & 0x3F);
            }
            int column = 0;
            // Decode groups of 4 characters to 3 bytes
            int bytesDecoded = 0;
            while (bytesDecoded < lineLength) {
                int c1 = column < line.size() ? line.get(column++) : 0;
                int c2 = column < line.size() ? line.get(column++) : 0;
                int c3 = column < line.size() ? line.get(column++) : 0;
                int c4 = column < line.size() ? line.get(column++) : 0;

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

        }

        result.add(new RuntimeScalar(decoded.toByteArray()));
    }
}
