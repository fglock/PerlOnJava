package org.perlonjava.io;

import java.nio.ByteBuffer;

/**
 * Interface for I/O layers that can transform data during read and write operations.
 *
 * Uses a streaming approach to handle stateful transformations like multi-byte
 * character encodings.
 */
interface IOLayer {
    /**
     * Process input data in streaming mode.
     * The layer reads from context.getInput() and writes to context.getOutput().
     * Any unprocessed bytes remain in the input buffer for the next call.
     */
    void processInput(StreamingContext context);

    /**
     * Process output data in streaming mode.
     */
    void processOutput(StreamingContext context);

    /**
     * Reset the layer's internal state.
     */
    void reset();

    // Compatibility methods - default implementations
    default byte[] processInput(byte[] input) {
        ByteBuffer inputBuffer = ByteBuffer.wrap(input);
        ByteBuffer outputBuffer = ByteBuffer.allocate(input.length * 4); // Allow for expansion

        StreamingContext context = new StreamingContext(inputBuffer, outputBuffer);
        processInput(context);

        outputBuffer.flip();
        byte[] result = new byte[outputBuffer.remaining()];
        outputBuffer.get(result);
        return result;
    }

    default byte[] processOutput(byte[] output) {
        ByteBuffer inputBuffer = ByteBuffer.wrap(output);
        ByteBuffer outputBuffer = ByteBuffer.allocate(output.length * 4); // Allow for expansion

        StreamingContext context = new StreamingContext(inputBuffer, outputBuffer);
        processOutput(context);

        outputBuffer.flip();
        byte[] result = new byte[outputBuffer.remaining()];
        outputBuffer.get(result);
        return result;
    }
}

/**
 * Context for streaming I/O operations.
 * Maintains input and output buffers that can be processed incrementally.
 */
class StreamingContext {
    private final ByteBuffer input;
    private final ByteBuffer output;

    public StreamingContext(ByteBuffer input, ByteBuffer output) {
        this.input = input;
        this.output = output;
    }

    public ByteBuffer getInput() { return input; }
    public ByteBuffer getOutput() { return output; }

    /**
     * Transfer all remaining bytes from input to output without transformation.
     */
    public void passThrough() {
        output.put(input);
    }

    /**
     * Check if there's space in the output buffer.
     */
    public boolean hasOutputSpace() {
        return output.hasRemaining();
    }
}
