package org.perlonjava.operators;

import org.perlonjava.runtime.PerlCompilerException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Maintains state during unpacking operations.
 */
public class UnpackState {
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
        if (hasHighUnicode || hasSurrogates) {
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
            // Clear buffer to force recreation
            buffer = null;
        }
    }

    public void switchToByteMode() {
        if (characterMode) {
            characterMode = false;
            // Clear buffer to force recreation
            buffer = null;
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
}
