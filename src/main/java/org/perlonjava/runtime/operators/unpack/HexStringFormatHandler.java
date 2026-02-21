package org.perlonjava.runtime.operators.unpack;

import org.perlonjava.runtime.operators.UnpackState;
import org.perlonjava.runtime.runtimetypes.RuntimeBase;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Handles hex string formats 'h' and 'H'.
 */
public class HexStringFormatHandler implements FormatHandler {
    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();
    private final char format;

    public HexStringFormatHandler(char format) {
        this.format = format;
    }

    @Override
    public void unpack(UnpackState state, List<RuntimeBase> output, int count, boolean isStarCount) {
        StringBuilder hexString = new StringBuilder();

        if (state.isCharacterMode()) {
            // In character mode, read hex from character values
            int hexDigitsToRead = isStarCount ? state.remainingCodePoints() * 2 : count;

            while (hexString.length() < hexDigitsToRead && state.hasMoreCodePoints()) {
                int charValue = state.nextCodePoint();
                int byteValue = charValue & 0xFF;

                if (format == 'h') {
                    // Low nybble first
                    if (hexString.length() < hexDigitsToRead) {
                        hexString.append(HEX_CHARS[byteValue & 0x0F]);
                    }
                    if (hexString.length() < hexDigitsToRead) {
                        hexString.append(HEX_CHARS[(byteValue >> 4) & 0x0F]);
                    }
                } else {
                    // High nybble first
                    if (hexString.length() < hexDigitsToRead) {
                        hexString.append(HEX_CHARS[(byteValue >> 4) & 0x0F]);
                    }
                    if (hexString.length() < hexDigitsToRead) {
                        hexString.append(HEX_CHARS[byteValue & 0x0F]);
                    }
                }
            }
        } else {
            // In byte mode, read from buffer
            ByteBuffer buffer = state.getBuffer();
            int hexDigitsToRead = isStarCount ? buffer.remaining() * 2 : count;
            int bytesToRead = (hexDigitsToRead + 1) / 2;
            int actualBytesToRead = Math.min(bytesToRead, buffer.remaining());

            for (int i = 0; i < actualBytesToRead; i++) {
                int byteValue = buffer.get() & 0xFF;

                if (format == 'h') {
                    // Low nybble first
                    if (hexString.length() < hexDigitsToRead) {
                        hexString.append(HEX_CHARS[byteValue & 0x0F]);
                    }
                    if (hexString.length() < hexDigitsToRead) {
                        hexString.append(HEX_CHARS[(byteValue >> 4) & 0x0F]);
                    }
                } else {
                    // High nybble first
                    if (hexString.length() < hexDigitsToRead) {
                        hexString.append(HEX_CHARS[(byteValue >> 4) & 0x0F]);
                    }
                    if (hexString.length() < hexDigitsToRead) {
                        hexString.append(HEX_CHARS[byteValue & 0x0F]);
                    }
                }
            }
        }

        output.add(new RuntimeScalar(hexString.toString()));
    }
}