package org.perlonjava.runtime.operators.pack;

import org.perlonjava.runtime.operators.Pack;
import org.perlonjava.runtime.runtimetypes.PerlCompilerException;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

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
 * @see Pack
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

            Pack.adjustGroupBasesAfterTruncate(targetPosition);
        }
    }

    /**
     * Handles absolute positioning for Unicode strings (character-based).
     *
     * @param targetPosition The target character position
     * @param output         The output stream
     */
    private static void handleAbsolutePositionUnicode(int targetPosition, PackBuffer output) {
        int currentPosition = output.sizeInCharacters();

        if (targetPosition > currentPosition) {
            // Pad with null characters to reach target position
            for (int k = currentPosition; k < targetPosition; k++) {
                output.writeCharacter(0);
            }
        } else if (targetPosition < currentPosition) {
            // Truncate to target character position
            output.truncateToCharacter(targetPosition);
            Pack.adjustGroupBasesAfterTruncate(targetPosition);
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

            Pack.adjustGroupBasesAfterTruncate(newSize);
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

            Pack.adjustGroupBasesAfterTruncate(alignedPosition);
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
                // Absolute positioning is relative to the current group base.
                // At top-level, the group base is 0 so this matches Perl's normal semantics.
                // For UTF-8 strings (with W format), work in character mode.
                if (output.hasUnicodeCharacters()) {
                    if (modifiers.nativeSize) {
                        int groupBaseCharIndex = Pack.getCurrentGroupBase();
                        int groupBaseBytePos = output.utf8ByteOffsetAtIndex(groupBaseCharIndex);
                        int targetBytePos = groupBaseBytePos + count;

                        int currentByteSize = output.sizeInUtf8Bytes();
                        if (targetBytePos > currentByteSize) {
                            for (int k = currentByteSize; k < targetBytePos; k++) {
                                output.writeCharacter(0);
                            }
                        } else if (targetBytePos < currentByteSize) {
                            output.truncateToUtf8BytePos(targetBytePos);
                            Pack.adjustGroupBasesAfterTruncate(output.sizeInCharacters());
                        }
                    } else {
                        int targetPosition = Pack.getCurrentGroupBase() + count;
                        handleAbsolutePositionUnicode(targetPosition, output);
                    }
                } else {
                    int targetPosition = Pack.getCurrentGroupBase() + count;
                    handleAbsolutePosition(targetPosition, output);
                }
                break;
            case '.':
                // . means null-fill or truncate to position specified by value
                // .0 (count == 0): relative to current position
                // .1 or . (count == 1 or hasStar == false): relative to innermost group
                // .2 (count == 2): relative to parent group
                // .* (hasStar == true): absolute position from start
                if (valueIndex >= values.size()) {
                    throw new PerlCompilerException("pack: '.' requires a position value");
                }
                RuntimeScalar posValue = values.get(valueIndex);
                valueIndex++;
                int offset = (int) posValue.getDouble();

                boolean isUnicode = output.hasUnicodeCharacters();
                boolean bytePosMode = isUnicode && modifiers.nativeSize;

                int targetPos;
                int currentPos;

                if (bytePosMode) {
                    currentPos = output.sizeInUtf8Bytes();
                    if (count == 0) {
                        targetPos = currentPos + offset;
                    } else if (hasStar) {
                        targetPos = offset;
                    } else {
                        int groupBaseCharIndex = Pack.getGroupBaseAtLevel(count);
                        int groupBaseBytePos = output.utf8ByteOffsetAtIndex(groupBaseCharIndex);
                        targetPos = groupBaseBytePos + offset;
                    }
                } else {
                    currentPos = isUnicode ? output.sizeInCharacters() : output.size();
                    if (count == 0) {
                        targetPos = currentPos + offset;
                    } else if (hasStar) {
                        targetPos = offset;
                    } else {
                        int groupBase = Pack.getGroupBaseAtLevel(count);
                        targetPos = groupBase + offset;
                    }
                }

                if (targetPos < 0) {
                    throw new PerlCompilerException("'.' outside of string in pack");
                }

                if (targetPos > currentPos) {
                    for (int k = currentPos; k < targetPos; k++) {
                        if (isUnicode) {
                            output.writeCharacter(0);
                        } else {
                            output.write(0);
                        }
                    }
                } else if (targetPos < currentPos) {
                    if (bytePosMode) {
                        output.truncateToUtf8BytePos(targetPos);
                        Pack.adjustGroupBasesAfterTruncate(output.sizeInCharacters());
                    } else if (isUnicode) {
                        output.truncateToCharacter(targetPos);
                        Pack.adjustGroupBasesAfterTruncate(targetPos);
                    } else {
                        byte[] currentData = output.toByteArray();
                        output.reset();
                        output.write(currentData, 0, targetPos);
                        Pack.adjustGroupBasesAfterTruncate(targetPos);
                    }
                }
                // If targetPos == currentSize, do nothing
                break;
        }
        return valueIndex;
    }
}
