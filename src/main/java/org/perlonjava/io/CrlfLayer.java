package org.perlonjava.io;

import java.util.ArrayList;
import java.util.List;

// CRLF layer
class CrlfLayer implements IOLayer {
    private final LayeredIOHandle layeredIOHandle;

    public CrlfLayer(LayeredIOHandle layeredIOHandle) {
        this.layeredIOHandle = layeredIOHandle;
    }

    @Override
    public byte[] processInput(byte[] input) {
        return convertCrlfToLf(input);
    }

    @Override
    public byte[] processOutput(byte[] output) {
        return convertLfToCrlf(output);
    }

    @Override
    public void reset() {
        layeredIOHandle.lastWasCR = false;
    }

    private byte[] convertCrlfToLf(byte[] data) {
        List<Byte> result = new ArrayList<>();

        for (int i = 0; i < data.length; i++) {
            if (data[i] == '\r') {
                if (i + 1 < data.length && data[i + 1] == '\n') {
                    // Skip CR in CRLF
                    continue;
                } else {
                    // Convert lone CR to LF
                    result.add((byte) '\n');
                }
            } else {
                result.add(data[i]);
            }
        }

        byte[] output = new byte[result.size()];
        for (int i = 0; i < result.size(); i++) {
            output[i] = result.get(i);
        }
        return output;
    }

    private byte[] convertLfToCrlf(byte[] data) {
        // Count LF characters that need CR added
        int lfCount = 0;
        for (int i = 0; i < data.length; i++) {
            if (data[i] == '\n' && (i == 0 || data[i - 1] != '\r')) {
                lfCount++;
            }
        }

        if (lfCount == 0) return data;

        byte[] result = new byte[data.length + lfCount];
        int j = 0;
        for (int i = 0; i < data.length; i++) {
            if (data[i] == '\n' && (i == 0 || data[i - 1] != '\r')) {
                result[j++] = '\r';
            }
            result[j++] = data[i];
        }

        return result;
    }
}
