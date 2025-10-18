package org.perlonjava.operators.pack;

import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeScalar;

import java.util.List;

/**
 * Handler for control formats 'x', 'X', '@', '.'.
 */
public class ControlPackHandler implements PackFormatHandler {
    private final char format;

    public ControlPackHandler(char format) {
        this.format = format;
    }

    /**
     * Handles alignment to a boundary.
     * Pads with null bytes to align the current position to the specified boundary.
     *
     * @param alignment The alignment boundary (e.g., 8 for 8-byte alignment)
     * @param output    The output stream
     */
    private static void handleAlignment(int alignment, PackBuffer output) {
        int currentPosition = output.size();
        // Calculate padding needed: (alignment - (position % alignment)) % alignment
        int padding = (alignment - (currentPosition % alignment)) % alignment;
        for (int j = 0; j < padding; j++) {
            output.write(0);
        }
    }

    /**
     * Handles null padding.
     *
     * @param count  The number of null bytes to write
     * @param output The output stream
     */
    private static void handleNullPadding(int count, PackBuffer output) {
        for (int j = 0; j < count; j++) {
            output.write(0);
        }
    }

    /**
     * Handles absolute positioning.
     *
     * @param targetPosition The target position
     * @param output         The output stream
     */
    private static void handleAbsolutePosition(int targetPosition, PackBuffer output) {
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
     * @param count  The number of bytes to back up
     * @param output The output stream
     */
    private static void handleBackup(int count, PackBuffer output) {
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

    /**
     * Handles backup to an alignment boundary.
     * Backs up to the previous N-byte aligned position.
     *
     * @param alignment The alignment boundary (e.g., 4 for 4-byte alignment)
     * @param output    The output stream
     */
    private static void handleBackupToAlignment(int alignment, PackBuffer output) {
        int currentSize = output.size();
        // Calculate the position of the previous alignment boundary
        // For position P and alignment A: aligned_pos = (P / A) * A
        int alignedPosition = (currentSize / alignment) * alignment;

        if (alignedPosition < currentSize) {
            // Truncate to the aligned position
            byte[] currentData = output.toByteArray();
            output.reset();
            if (alignedPosition > 0) {
                output.write(currentData, 0, alignedPosition);
            }
        }
    }

    @Override
    public int pack(List<RuntimeScalar> values, int valueIndex, int count, boolean hasStar,
                    ParsedModifiers modifiers, PackBuffer output) {
        switch (format) {
            case 'x':
                // When nativeSize is true (x!N), align to N-byte boundary
                // Otherwise, just add N null bytes
                if (modifiers.nativeSize && count > 0) {
                    handleAlignment(count, output);
                } else {
                    handleNullPadding(count, output);
                }
                break;
            case 'X':
                // When nativeSize is true (X!N), back up to N-byte aligned boundary
                // Otherwise, just back up by N bytes
                if (modifiers.nativeSize && count > 0) {
                    handleBackupToAlignment(count, output);
                } else {
                    handleBackup(count, output);
                }
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

                // Handle negative positions: throw error (can't position before string start)
                if (targetPos < 0) {
                    throw new PerlCompilerException("'.' outside of string in pack");
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
}
