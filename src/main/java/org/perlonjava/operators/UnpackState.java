package org.perlonjava.operators;

import org.perlonjava.runtime.PerlCompilerException;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Maintains state during unpack operations, managing the dual representation
 * of data as both character codes and bytes, along with position tracking.
 * 
 * <p><b>Data Representation:</b></p>
 * <ul>
 *   <li><b>Character codes (codePoints array):</b> Logical view of the string as an array
 *       of Unicode code points. Each element represents one character (0 to 0x10FFFF+).</li>
 *   <li><b>Byte data (originalBytes):</b> Physical representation as UTF-8 encoded bytes
 *       (for strings with characters > 255) or ISO-8859-1 bytes (for binary data).</li>
 *   <li><b>isUTF8Data flag:</b> True if the string contains characters > 255, indicating
 *       that originalBytes contains UTF-8 encoded data.</li>
 * </ul>
 * 
 * <p><b>Position Tracking:</b></p>
 * <ul>
 *   <li><b>Character position (codePointIndex):</b> Current index in the codePoints array.</li>
 *   <li><b>Byte position:</b> Current position in the ByteBuffer wrapping originalBytes.</li>
 *   <li><b>Group baselines:</b> Stacks (groupCharBase, groupByteBase) track the starting
 *       positions of nested groups for relative addressing (e.g., .(.). format).</li>
 * </ul>
 * 
 * <p><b>Mode Management:</b></p>
 * <ul>
 *   <li><b>Character mode (default):</b> Formats read from the codePoints array.
 *       Used by: a, A, Z, C, U, W formats.</li>
 *   <li><b>Byte mode:</b> Formats read from the ByteBuffer wrapping originalBytes.
 *       Used by: s, S, i, I, l, L, q, Q, n, N, v, V, f, d formats.</li>
 *   <li><b>Mode switches:</b> C0 forces byte mode, U0 forces character mode.</li>
 * </ul>
 * 
 * <p><b>Critical Insight:</b> Perl internally stores UTF-8 bytes but tracks character
 * length separately. When unpacking strings with the UTF-8 flag set:
 * <ul>
 *   <li>C format reads character codes (0-255) from codePoints</li>
 *   <li>N/V/I formats read raw bytes from originalBytes (UTF-8 encoded)</li>
 *   <li>x format skips characters in character mode, bytes in byte mode</li>
 * </ul>
 * 
 * <p><b>Group-Relative Positioning:</b> The . and .! formats support multi-level
 * group-relative positioning:
 * <ul>
 *   <li>.0 - Position relative to current position (always 0)</li>
 *   <li>. or .1 - Position relative to current (innermost) group</li>
 *   <li>.2 - Position relative to parent group</li>
 *   <li>.* - Absolute position from start of string</li>
 * </ul>
 * 
 * @see org.perlonjava.operators.Unpack
 * @see org.perlonjava.operators.unpack.UnpackGroupProcessor
 * @see org.perlonjava.operators.unpack.DotFormatHandler
 */
public class UnpackState {
    public final boolean isUTF8Data;
    private final String dataString;
    private final byte[] originalBytes;
    private final int[] codePoints;
    // Stacks to track group-relative baselines (character and byte domains)
    private final java.util.Deque<Integer> groupCharBase = new java.util.ArrayDeque<>();
    private final java.util.Deque<Integer> groupByteBase = new java.util.ArrayDeque<>();
    private int codePointIndex = 0;
    private boolean characterMode;
    private ByteBuffer buffer;
    private ByteOrder currentByteOrder = ByteOrder.LITTLE_ENDIAN; // Default to little-endian

    public UnpackState(String dataString, boolean startsWithU) {
        this(dataString, startsWithU, false);
    }

    public UnpackState(String dataString, boolean startsWithU, boolean utf8Flagged) {
        this.dataString = dataString;
        this.characterMode = !startsWithU;

        // Check for special marker format for code points > 0x10FFFF
        // Format: "\uFFFD<XXXXXXXX>" where XXXXXXXX is hex code point
        java.util.List<Integer> codePointList = new java.util.ArrayList<>();
        int i = 0;
        while (i < dataString.length()) {
            if (i < dataString.length() - 10 && 
                dataString.charAt(i) == '\uFFFD' && 
                dataString.charAt(i + 1) == '<' &&
                dataString.charAt(i + 10) == '>') {
                // Extract the hex code point
                String hexStr = dataString.substring(i + 2, i + 10);
                try {
                    int cp = Integer.parseUnsignedInt(hexStr, 16);
                    codePointList.add(cp);
                    i += 11; // Skip the marker
                    continue;
                } catch (NumberFormatException e) {
                    // Not a valid marker, treat as regular character
                }
            }
            codePointList.add(dataString.codePointAt(i));
            i += Character.charCount(dataString.codePointAt(i));
        }
        
        this.codePoints = codePointList.stream().mapToInt(Integer::intValue).toArray();

        // Determine if this is binary data or a Unicode string
        boolean hasSurrogates = false;
        boolean hasHighUnicode = false;
        boolean hasBeyondUnicode = false;

        for (int cp : this.codePoints) {
            if (cp >= 0xD800 && cp <= 0xDFFF) {
                hasSurrogates = true;
            }
            if (cp > 255 && cp <= 0x10FFFF) {
                hasHighUnicode = true;
            }
            if (cp > 0x10FFFF) {
                hasBeyondUnicode = true;
            }
        }

        // If we have Unicode characters beyond Latin-1, use extended UTF-8 (Perl semantics).
        // Also, if the original scalar was UTF-8 flagged, treat it as UTF-8 data even when
        // all code points are <= 255. This matches Perl behavior and is required for
        // A* trimming of Unicode whitespace.
        this.isUTF8Data = utf8Flagged || hasHighUnicode || hasSurrogates || hasBeyondUnicode;
        if (isUTF8Data) {
            this.originalBytes = encodeUtf8Extended(this.codePoints);
        } else {
            // For strings that only contain characters 0-255, preserve as ISO-8859-1
            // This handles both ASCII and binary packed data correctly
            this.originalBytes = dataString.getBytes(StandardCharsets.ISO_8859_1);
        }
    }

    /**
     * Push current position as the baseline for a new group scope.
     */
    public void pushGroupBase() {
        groupCharBase.push(getCurrentCodePointIndex());
        groupByteBase.push(getBytePosition());
    }

    /**
     * Pop the current group baseline. Safe to call when stack is empty.
     */
    public void popGroupBase() {
        if (!groupCharBase.isEmpty()) groupCharBase.pop();
        if (!groupByteBase.isEmpty()) groupByteBase.pop();
    }

    /**
     * Get the relative position from the current group baseline in the active mode.
     */
    public int getRelativePosition() {
        if (isCharacterMode()) {
            int base = groupCharBase.isEmpty() ? 0 : groupCharBase.peek();
            return getCurrentCodePointIndex() - base;
        } else {
            int base = groupByteBase.isEmpty() ? 0 : groupByteBase.peek();
            return getBytePosition() - base;
        }
    }

    /**
     * Set the position relative to the current group baseline.
     */
    public void setRelativePosition(int offset) {
        if (isCharacterMode()) {
            int base = groupCharBase.isEmpty() ? 0 : groupCharBase.peek();
            setPosition(base + offset);
        } else {
            int base = groupByteBase.isEmpty() ? 0 : groupByteBase.peek();
            setPosition(base + offset);
        }
    }

    /**
     * Get the relative byte position from the current group baseline.
     */
    public int getRelativeBytePosition() {
        int base = groupByteBase.isEmpty() ? 0 : groupByteBase.peek();
        return getBytePosition() - base;
    }

    /**
     * Get the byte position relative to the Nth group level up.
     * 
     * @param level 1 = innermost group, 2 = parent group, etc.
     * @return byte position relative to the specified group level, or absolute if level > depth
     */
    public int getRelativeBytePosition(int level) {
        int depth = groupByteBase.size();
        if (depth == 0 || level > depth) {
            // No groups or level exceeds depth: return absolute byte position
            return getBytePosition();
        }
        // Get the base at the specified level
        int baseIndex = depth - level;
        if (baseIndex < 0) baseIndex = 0;
        
        Integer[] bases = groupByteBase.toArray(new Integer[0]);
        int base = bases[baseIndex];
        return getBytePosition() - base;
    }

    /**
     * Returns true if there is an active group baseline on the stack.
     */
    public boolean hasGroupBase() {
        return !groupCharBase.isEmpty() || !groupByteBase.isEmpty();
    }

    /**
     * Returns the current group nesting depth.
     * Used by dot format to determine which group level to use as baseline.
     * 
     * @return number of active groups (0 if no groups, 1 for innermost, etc.)
     */
    public int getGroupDepth() {
        return isCharacterMode() ? groupCharBase.size() : groupByteBase.size();
    }

    /**
     * Get the position relative to the Nth group level up.
     * 
     * @param level 1 = innermost group, 2 = parent group, etc.
     * @return position relative to the specified group level, or absolute if level > depth
     */
    public int getRelativePosition(int level) {
        if (isCharacterMode()) {
            int depth = groupCharBase.size();
            if (depth == 0 || level > depth) {
                // No groups or level exceeds depth: return absolute position
                return getCurrentCodePointIndex();
            }
            // Get the base at the specified level (1 = top of stack, 2 = one below, etc.)
            // Stack index: size-level gives us the Nth element from top
            int baseIndex = depth - level;
            if (baseIndex < 0) baseIndex = 0;
            
            // Convert Deque to array to access by index
            Integer[] bases = groupCharBase.toArray(new Integer[0]);
            int base = bases[baseIndex];
            return getCurrentCodePointIndex() - base;
        } else {
            int depth = groupByteBase.size();
            if (depth == 0 || level > depth) {
                // No groups or level exceeds depth: return absolute position
                return getBytePosition();
            }
            int baseIndex = depth - level;
            if (baseIndex < 0) baseIndex = 0;
            
            Integer[] bases = groupByteBase.toArray(new Integer[0]);
            int base = bases[baseIndex];
            return getBytePosition() - base;
        }
    }

    public boolean isCharacterMode() {
        return characterMode;
    }

    public void switchToCharacterMode() {
        if (!characterMode) {
            characterMode = true;
            // Need to synchronize code point position with consumed bytes
            if (buffer != null) {
                int bytesConsumed = buffer.position();
                if (isUTF8Data) {
                    // Count code points in consumed UTF-8 bytes
                    int cpIndex = 0;
                    int byteIndex = 0;
                    while (byteIndex < bytesConsumed && cpIndex < codePoints.length) {
                        int cp = codePoints[cpIndex];
                        int utf8ByteLength = utf8Len(cp);

                        // Check if we have consumed exactly this character's bytes
                        if (byteIndex + utf8ByteLength <= bytesConsumed) {
                            byteIndex += utf8ByteLength;
                            cpIndex++;
                        } else {
                            break; // Haven't consumed enough bytes for this character
                        }
                    }
                    codePointIndex = cpIndex;
                } else {
                    // For ISO-8859-1 data, each byte is one code point
                    codePointIndex = bytesConsumed;
                }
            }
            // DON'T reset buffer to null - keep the current position!
        }
    }

    public boolean isUTF8Data() {
        return isUTF8Data;
    }

    public void switchToByteMode() {
        if (characterMode) {
            characterMode = false;
            // Need to synchronize byte position with consumed code points
            if (buffer == null) {
                buffer = ByteBuffer.wrap(originalBytes).order(currentByteOrder);
            }
            // Calculate byte position based on consumed code points
            int bytePos = 0;
            if (isUTF8Data) {
                // For UTF-8 data, calculate variable-length byte position (extended UTF-8)
                for (int i = 0; i < codePointIndex; i++) {
                    bytePos += utf8Len(codePoints[i]);
                }
            } else {
                // For ISO-8859-1 data, each code point is exactly one byte
                bytePos = codePointIndex;
            }
            buffer.position(bytePos);
        }
    }

    public ByteBuffer getBuffer() {
        if (buffer == null) {
            buffer = ByteBuffer.wrap(originalBytes).order(currentByteOrder);
        }
        return buffer;
    }

    /**
     * Sets the byte order for the ByteBuffer used in unpacking.
     * This must be called based on endianness modifiers in the pack template.
     *
     * @param bigEndian true for big-endian ('>'), false for little-endian ('<')
     */
    public void setByteOrder(boolean bigEndian) {
        currentByteOrder = bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
        if (buffer != null) {
            // If buffer exists, update its byte order
            buffer.order(currentByteOrder);
        }
    }

    public boolean hasMoreCodePoints() {
        return codePointIndex < codePoints.length;
    }

    public int nextCodePoint() {
        if (codePointIndex < codePoints.length) {
            return codePoints[codePointIndex++];
        }
        throw new PerlCompilerException("unpack: not enough data");
    }

    public int remainingCodePoints() {
        return codePoints.length - codePointIndex;
    }

    public int remainingBytes() {
        if (buffer == null) {
            return originalBytes.length;
        }
        return buffer.remaining();
    }

    /**
     * Gets the total length of the data.
     */
    public int getTotalLength() {
        if (characterMode) {
            return codePoints.length;
        } else {
            return originalBytes.length;
        }
    }

    /**
     * Gets the current code point index (for character mode).
     */
    public int getCurrentCodePointIndex() {
        return codePointIndex;
    }

    /**
     * Sets the code point index directly (for character mode).
     */
    public void setCodePointIndex(int index) {
        codePointIndex = Math.max(0, Math.min(index, codePoints.length));
    }

    /**
     * Gets the current position in the data.
     * In character mode, returns the code point index.
     * In byte mode, returns the byte position.
     */
    public int getPosition() {
        if (characterMode) {
            return codePointIndex;
        } else {
            if (buffer == null) {
                return 0;
            }
            return buffer.position();
        }
    }

    /**
     * Sets the absolute position in the data.
     *
     * @param newPosition The new position (0-based)
     */
    public void setPosition(int newPosition) {
        // DEBUG: setPosition called with " + newPosition + ", characterMode=" + characterMode
        if (characterMode) {
            // In character mode, position is in code points
            codePointIndex = Math.min(newPosition, codePoints.length);
            // DEBUG: Set codePointIndex to " + codePointIndex
        } else {
            // In byte mode, we need to recreate the buffer at the new position
            if (originalBytes != null && originalBytes.length > 0) {
                int bytePos = Math.min(newPosition, originalBytes.length);
                buffer = ByteBuffer.wrap(originalBytes, bytePos, originalBytes.length - bytePos);
                buffer.order(currentByteOrder);
                // DEBUG: Reset buffer to position " + bytePos
            }
        }
    }

    /**
     * Gets the current byte position regardless of mode.
     */
    public int getBytePosition() {
        if (buffer != null) {
            return buffer.position();
        } else if (!characterMode) {
            // We're in byte mode but buffer hasn't been initialized yet
            return 0;
        } else {
            // We're in character mode - need to calculate byte position
            int bytePos = 0;
            if (isUTF8Data) {
                // For UTF-8 data, calculate variable-length byte position (extended UTF-8)
                for (int i = 0; i < codePointIndex; i++) {
                    bytePos += utf8Len(codePoints[i]);
                }
            } else {
                // For ISO-8859-1 data, each code point is exactly one byte
                bytePos = codePointIndex;
            }
            return bytePos;
        }
    }

    /**
     * Compute the extended UTF-8 length (Perl semantics) for a code point.
     * Supports 1 to 6 byte sequences.
     */
    private static int utf8Len(int cp) {
        if (cp <= 0x7F) return 1;
        if (cp <= 0x7FF) return 2;
        if (cp <= 0xFFFF) return 3;
        if (cp <= 0x1FFFFF) return 4;
        if (cp <= 0x3FFFFFF) return 5;
        return 6;
    }

    /**
     * Encode an array of code points into extended UTF-8 bytes (Perl semantics).
     */
    private static byte[] encodeUtf8Extended(int[] cps) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        for (int cp : cps) {
            int len = utf8Len(cp);
            byte[] out = new byte[len];
            int val = cp;
            // Fill continuation bytes from the end
            for (int i = len - 1; i >= 1; i--) {
                out[i] = (byte) (0x80 | (val & 0x3F));
                val >>= 6;
            }
            // First byte prefix: 0xxxxxxx, 110xxxxx, 1110xxxx, 11110xxx, 111110xx, 1111110x
            if (len == 1) {
                out[0] = (byte) (val & 0x7F);
            } else {
                int prefix = (0xFF << (8 - len)) & 0xFF; // 11000000, 11100000, 11110000, 11111000, 11111100
                out[0] = (byte) (prefix | (val & ((1 << (8 - (len + 1))) - 1)));
            }
            baos.write(out, 0, out.length);
        }
        return baos.toByteArray();
    }
}