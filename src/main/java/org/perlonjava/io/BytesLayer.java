package org.perlonjava.io;

// Bytes/Raw layer - no transformation
class BytesLayer implements IOLayer {
    @Override
    public byte[] processInput(byte[] input) {
        return input;
    }

    @Override
    public byte[] processOutput(byte[] output) {
        return output;
    }

    @Override
    public void reset() {
        // No state to reset
    }
}
