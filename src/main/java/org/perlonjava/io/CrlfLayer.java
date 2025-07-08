package org.perlonjava.io;

import java.nio.ByteBuffer;

// CRLF layer - handles line ending conversions
class CrlfLayer implements IOLayer {
    private final LayeredIOHandle layeredIOHandle;

    // State for handling CR at end of buffer
    private boolean pendingCR = false;

    public CrlfLayer(LayeredIOHandle layeredIOHandle) {
        this.layeredIOHandle = layeredIOHandle;
    }

    @Override
    public void processInput(StreamingContext context) {
        ByteBuffer input = context.getInput();
        ByteBuffer output = context.getOutput();

        while (input.hasRemaining() && output.hasRemaining()) {
            byte b = input.get();

            if (pendingCR) {
                pendingCR = false;
                if (b == '\n') {
                    // CRLF -> LF (skip the CR)
                    output.put((byte)'\n');
                } else {
                    // Lone CR -> LF
                    output.put((byte)'\n');
                    // Process current byte normally
                    if (b == '\r') {
                        pendingCR = true;
                    } else {
                        output.put(b);
                    }
                }
            } else if (b == '\r') {
                // Could be start of CRLF
                if (input.hasRemaining()) {
                    byte next = input.get(input.position()); // Peek
                    if (next == '\n') {
                        input.get(); // Consume the LF
                        output.put((byte)'\n');
                    } else {
                        // Lone CR -> LF
                        output.put((byte)'\n');
                    }
                } else {
                    // CR at end of buffer - save state
                    pendingCR = true;
                }
            } else {
                output.put(b);
            }
        }
    }

    @Override
    public void processOutput(StreamingContext context) {
        ByteBuffer input = context.getInput();
        ByteBuffer output = context.getOutput();

        while (input.hasRemaining() && output.hasRemaining()) {
            byte b = input.get();

            if (b == '\n') {
                // LF -> CRLF
                if (output.remaining() >= 2) {
                    output.put((byte)'\r');
                    output.put((byte)'\n');
                } else {
                    // Not enough space - put byte back
                    input.position(input.position() - 1);
                    break;
                }
            } else {
                output.put(b);
            }
        }
    }

    @Override
    public void reset() {
        pendingCR = false;
    }
}
