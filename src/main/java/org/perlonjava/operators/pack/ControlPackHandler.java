package org.perlonjava.operators.pack;

import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeScalar;

import java.util.List;

/**
 * Handler for control formats 'x', 'X', '@', '.'.
 * 
 * <p><b>Format Descriptions:</b></p>
 * <ul>
 *   <li><b>x:</b> Insert null bytes (forward padding/skip)
 *       <ul>
 *         <li>x - skip/insert 1 null byte</li>
 *         <li>x5 - skip/insert 5 null bytes</li>
 *         <li>x! - pad to alignment boundary</li>
 *       </ul>
 *   </li>
 *   <li><b>X:</b> Back up (remove bytes from end)
 *       <ul>
 *         <li>X - back up 1 byte</li>
 *         <li>X3 - back up 3 bytes</li>
 *         <li>X! - back up to alignment boundary</li>
 *       </ul>
 *   </li>
 *   <li><b>@:</b> Absolute positioning
 *       <ul>
 *         <li>@10 - move to position 10 (pad with nulls or truncate)</li>
 *         <li>@0 - truncate to beginning</li>
 *         <li>@! - pad to alignment boundary</li>
 *       </ul>
 *   </li>
 *   <li><b>.:</b> Dynamic positioning based on value
 *       <ul>
 *         <li>. - move to absolute position specified by next value</li>
 *         <li>.0 - move relative to current position (offset from next value)</li>
 *       </ul>
 *   </li>
 * </ul>
 * 
 * <p><b>Critical Distinction - '.' Format:</b></p>
 * <ul>
 *   <li><b>. (no count or count &gt; 0):</b> Absolute positioning - move to position N
 *       <ul>
 *         <li>Example: pack("a5 .", "hello", 2) → "he" (truncate to position 2)</li>
 *         <li>Negative positions throw error: "'.' outside of string in pack"</li>
 *       </ul>
 *   </li>
 *   <li><b>.0 (count == 0):</b> Relative positioning - move current + N
 *       <ul>
 *         <li>Example: pack("a5 .0", "hello", -3) → "he" (back up 3 from current)</li>
 *         <li>Allows negative offsets to truncate from current position</li>
 *       </ul>
 *   </li>
 * </ul>
 * 
 * <p><b>Error Handling:</b></p>
 * <ul>
 *   <li>'X' with count &gt; current size: throws "'X' outside of string in pack"</li>
 *   <li>'.' with negative absolute position: throws "'.' outside of string in pack"</li>
 *   <li>'.0' with negative offset that goes before start: throws error</li>
 * </ul>
 * 
 * @see org.perlonjava.operators.Pack
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
                // @ positions relative to start of innermost group
                // @!N means position in bytes relative to group start
                // @* is equivalent to @0
                int baseIndex = output.getGroupStart(1);
                if (hasStar) {
                    count = 0;
                }
                if (modifiers.nativeSize) {
                    int baseByte = output.byteOffsetOfIndex(baseIndex);
                    output.moveToBytePosition(baseByte + count);
                } else {
                    output.moveToIndex(baseIndex + count);
                }
                break;
            case '.':
                // . means null-fill or truncate to position specified by value
                // Per perldoc pack:
                // - .0: offset relative to current position
                // - .*: offset relative to start of string
                // - .n: offset relative to start of nth innermost group (or start of string if n too large)
                // - .! uses byte-based positioning (for UTF-8 / multi-byte characters)
                if (valueIndex >= values.size()) {
                    throw new PerlCompilerException("pack: '.' requires a position value");
                }
                RuntimeScalar posValue = values.get(valueIndex);
                valueIndex++;
                int offset = (int) posValue.getDouble();

                int base;
                if (hasStar) {
                    base = 0;
                } else if (count == 0) {
                    base = modifiers.nativeSize ? output.byteSize() : output.size();
                } else {
                    int baseIndexForDot = output.getGroupStart(count);
                    base = modifiers.nativeSize ? output.byteOffsetOfIndex(baseIndexForDot) : baseIndexForDot;
                }

                int targetPos = base + offset;

                if (targetPos < 0) {
                    throw new PerlCompilerException("'.' outside of string in pack");
                }

                if (modifiers.nativeSize) {
                    output.moveToBytePosition(targetPos);
                } else {
                    output.moveToIndex(targetPos);
                }
                break;
        }
        return valueIndex;
    }
}
