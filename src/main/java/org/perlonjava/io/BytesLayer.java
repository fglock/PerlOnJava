package org.perlonjava.io;

// Bytes/Raw layer - no transformation
class BytesLayer implements IOLayer {
    @Override
    public void processInput(StreamingContext context) {
        // Pass through without transformation
        context.passThrough();
    }

    @Override
    public void processOutput(StreamingContext context) {
        // Pass through without transformation
        context.passThrough();
    }

    @Override
    public void reset() {
        // No state to reset
    }
}
