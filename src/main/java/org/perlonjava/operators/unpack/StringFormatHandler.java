package org.perlonjava.operators.unpack;

import org.perlonjava.operators.UnpackState;
import org.perlonjava.runtime.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Handles string formats 'a', 'A', and 'Z'.
 */
public class StringFormatHandler implements FormatHandler {
    private final char format;

    public StringFormatHandler(char format) {
        this.format = format;
    }

    @Override
    public void unpack(UnpackState state, List<RuntimeBase> output, int count, boolean isStarCount) {
        if (state.isCharacterMode()) {
            // In character mode, read characters directly
            StringBuilder sb = new StringBuilder();
            int charsToRead = Math.min(count, state.remainingCodePoints());

            for (int i = 0; i < charsToRead; i++) {
                if (state.hasMoreCodePoints()) {
                    sb.appendCodePoint(state.nextCodePoint());
                } else {
                    break;
                }
            }

            // Pad if needed and not star count
            while (sb.length() < count && !isStarCount) {
                sb.append(format == 'A' ? ' ' : '\0');
            }

            String str = sb.toString();
            str = processString(str);
            output.add(new RuntimeScalar(str));
        } else {
            // In byte mode, read from buffer
            ByteBuffer buffer = state.getBuffer();
            String str = readString(buffer, count, isStarCount);
            output.add(new RuntimeScalar(str));
        }
    }

    private String processString(String str) {
        switch (format) {
            case 'A':
                // Trim trailing spaces
                return str.replaceAll("\\s+$", "");
            case 'Z':
                // Trim at first null
                int nullIndex = str.indexOf('\0');
                if (nullIndex >= 0) {
                    return str.substring(0, nullIndex);
                }
                return str;
            default: // 'a'
                return str;
        }
    }

    private String readString(ByteBuffer buffer, int count, boolean isStarCount) {
        int actualCount = isStarCount ? buffer.remaining() : Math.min(count, buffer.remaining());
        byte[] bytes = new byte[actualCount];
        buffer.get(bytes, 0, actualCount);

        String result = new String(bytes, StandardCharsets.UTF_8);

        // Apply format-specific processing
        result = processString(result);

        // Pad if necessary and not star count
        if (!isStarCount && result.length() < count) {
            StringBuilder sb = new StringBuilder(result);
            char padChar = format == 'A' ? ' ' : '\0';
            while (sb.length() < count) {
                sb.append(padChar);
            }
            result = sb.toString();
        }

        return result;
    }
}