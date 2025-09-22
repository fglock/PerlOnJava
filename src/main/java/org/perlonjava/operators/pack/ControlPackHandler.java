package org.perlonjava.operators.pack;

import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeScalar;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Handler for control formats 'x', 'X', '@', '.'.
 */
public class ControlPackHandler implements PackFormatHandler {
    private final char format;

    public ControlPackHandler(char format) {
        this.format = format;
    }

    @Override
    public int pack(List<RuntimeScalar> values, int valueIndex, int count, boolean hasStar, 
                    ParsedModifiers modifiers, ByteArrayOutputStream output) {
        switch (format) {
            case 'x':
                handleNullPadding(count, output);
                break;
            case 'X':
                handleBackup(count, output);
                break;
            case '@':
                handleAbsolutePosition(count, output);
                break;
            case '.':
                // . means null-fill or truncate to absolute position specified by value
                if (valueIndex >= values.size()) {
                    throw new PerlCompilerException("pack: '.' requires a position value");
                }
                RuntimeScalar posValue = values.get(valueIndex);
                valueIndex++;
                int targetPos = (int) posValue.getDouble();

                // DEBUG: '.' format - current size: " + output.size() + ", target position: " + targetPos

                if (targetPos < 0) {
                    throw new PerlCompilerException("pack: negative position for '.'");
                }

                int currentSize = output.size();
                if (targetPos > currentSize) {
                    // Null-fill to reach the target position
                    for (int k = currentSize; k < targetPos; k++) {
                        output.write(0);
                    }
                } else if (targetPos < currentSize) {
                    // Truncate to the target position
                    byte[] currentData = output.toByteArray();
                    output.reset();
                    output.write(currentData, 0, targetPos);
                }
                // If targetPos == currentSize, do nothing
                break;
        }
        return valueIndex;
    }

    /**
     * Handles null padding.
     * 
     * @param count The number of null bytes to write
     * @param output The output stream
     */
    private static void handleNullPadding(int count, ByteArrayOutputStream output) {
        for (int j = 0; j < count; j++) {
            output.write(0);
        }
    }

    /**
     * Handles absolute positioning.
     * 
     * @param targetPosition The target position
     * @param output The output stream
     */
    private static void handleAbsolutePosition(int targetPosition, ByteArrayOutputStream output) {
        int currentPosition = output.size();

        if (targetPosition > currentPosition) {
            // Pad with nulls to reach target position
            for (int k = currentPosition; k < targetPosition; k++) {
                output.write(0);
            }
        } else if (targetPosition < currentPosition) {
            // Truncate to target position
            byte[] truncated = new byte[targetPosition];
            System.arraycopy(output.toByteArray(), 0, truncated, 0, targetPosition);
            output.reset();
            output.write(truncated, 0, targetPosition);
        }
    }

    /**
     * Handles backup.
     * 
     * @param count The number of bytes to back up
     * @param output The output stream
     */
    private static void handleBackup(int count, ByteArrayOutputStream output) {
        // DEBUG: handleBackup called with count=" + count + ", current size=" + output.size()
        int currentSize = output.size();

        if (count > currentSize) {
            throw new PerlCompilerException("'X' outside of string in pack");
        }

        int newSize = currentSize - count;

        if (newSize < currentSize) {
            // Truncate the output by backing up
            byte[] currentData = output.toByteArray();
            output.reset();
            if (newSize > 0) {
                output.write(currentData, 0, newSize);
            }
        }
        // DEBUG: handleBackup finished, new size=" + output.size()
    }
}
