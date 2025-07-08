package org.perlonjava.io;

/**
 * Simplified I/O layer interface.
 * Layers transform strings during read/write operations.
 */
public interface IOLayer {
    /**
     * Process input data (reading from file/socket).
     * @param input the input string where each char represents a byte
     * @return the processed string
     */
    String processInput(String input);

    /**
     * Process output data (writing to file/socket).
     * @param output the output string to process
     * @return the processed string where each char represents a byte
     */
    String processOutput(String output);

    /**
     * Reset any internal state.
     */
    default void reset() {
        // Default no-op implementation
    }
}
