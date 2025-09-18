package org.perlonjava.operators;

import org.perlonjava.runtime.PerlCompilerException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Maintains state during unpacking operations.
 */
public class UnpackState {
    public final boolean isUTF8Data;
    private final String dataString;
    private final byte[] originalBytes;
    private final int[] codePoints;
    private int codePointIndex = 0;
    private boolean characterMode;
    private ByteBuffer buffer;

    public UnpackState(String dataString, boolean startsWithU) {
        this.dataString = dataString;
        this.codePoints = dataString.codePoints().toArray();
        this.characterMode = !startsWithU;

        // Determine if this is binary data or a Unicode string
        boolean hasSurrogates = false;
        boolean hasHighUnicode = false;

        for (int i = 0; i < dataString.length(); i++) {
            char ch = dataString.charAt(i);
            if (Character.isHighSurrogate(ch) || Character.isLowSurrogate(ch)) {
                hasSurrogates = true;
                break;
            }
            if (ch > 255) {
                hasHighUnicode = true;
                break;
            }
        }

        // If we have Unicode characters beyond Latin-1, use UTF-8
        this.isUTF8Data = hasHighUnicode || hasSurrogates;
        if (isUTF8Data) {
            this.originalBytes = dataString.getBytes(StandardCharsets.UTF_8);
        } else {
            // For strings that only contain characters 0-255, preserve as ISO-8859-1
            // This handles both ASCII and binary packed data correctly
            this.originalBytes = dataString.getBytes(StandardCharsets.ISO_8859_1);
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
                        int utf8ByteLength;

                        // Calculate actual UTF-8 byte length for this code point
                        if (cp <= 0x7F) {
                            utf8ByteLength = 1;
                        } else if (cp <= 0x7FF) {
                            utf8ByteLength = 2;
                        } else if (cp <= 0xFFFF) {
                            utf8ByteLength = 3;
                        } else {
                            utf8ByteLength = 4;
                        }

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
                buffer = ByteBuffer.wrap(originalBytes).order(ByteOrder.LITTLE_ENDIAN);
            }
            // Calculate byte position based on consumed code points
            int bytePos = 0;
            if (isUTF8Data) {
                // For UTF-8 data, calculate variable-length byte position
                for (int i = 0; i < codePointIndex; i++) {
                    int cp = codePoints[i];
                    if (cp <= 0x7F) {
                        bytePos += 1;
                    } else if (cp <= 0x7FF) {
                        bytePos += 2;
                    } else if (cp <= 0xFFFF) {
                        bytePos += 3;
                    } else {
                        bytePos += 4;
                    }
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
            buffer = ByteBuffer.wrap(originalBytes).order(ByteOrder.LITTLE_ENDIAN);
        }
        return buffer;
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
                buffer.order(ByteOrder.LITTLE_ENDIAN);
                // DEBUG: Reset buffer to position " + bytePos
            }
        }
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
                // For UTF-8 data, calculate variable-length byte position
                for (int i = 0; i < codePointIndex; i++) {
                    int cp = codePoints[i];
                    if (cp <= 0x7F) {
                        bytePos += 1;
                    } else if (cp <= 0x7FF) {
                        bytePos += 2;
                    } else if (cp <= 0xFFFF) {
                        bytePos += 3;
                    } else {
                        bytePos += 4;
                    }
                }
            } else {
                // For ISO-8859-1 data, each code point is exactly one byte
                bytePos = codePointIndex;
            }
            return bytePos;
        }
    }
}