package org.perlonjava.operators.unpack;

import org.perlonjava.operators.UnpackState;
import org.perlonjava.runtime.RuntimeBase;
import org.perlonjava.runtime.RuntimeScalar;

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
                    int cp = state.nextCodePoint();
                    sb.appendCodePoint(cp);
                } else {
                    break;
                }
            }

            String str = sb.toString();
            str = processString(str);

            // Pad if needed and not star count
            // Note: 'A' and 'Z' formats strip content, so don't pad them back!
            // Only 'a' format needs padding to maintain exact count
            if (!isStarCount && format == 'a' && str.length() < count) {
                StringBuilder padded = new StringBuilder(str);
                while (padded.length() < count) {
                    padded.append('\0');
                }
                str = padded.toString();
            }

            output.add(new RuntimeScalar(str));
        } else {
            // In byte mode, read from buffer
            ByteBuffer buffer = state.getBuffer();
            String str = readString(buffer, count, isStarCount);
            output.add(new RuntimeScalar(str));
        }
    }

    /**
     * Process string in character mode (UTF-8 mode).
     * For 'A' format, removes all trailing Unicode whitespace.
     */
    private String processString(String str) {
        switch (format) {
            case 'A':
                // In character mode, trim all Unicode whitespace including null
                // Perl considers \xa0, \x{1680}, and other Unicode spaces as whitespace
                // Note: Java's Character.isWhitespace() doesn't include \0, so we check it explicitly
                int endPos = str.length();
                while (endPos > 0) {
                    char ch = str.charAt(endPos - 1);
                    if (Character.isWhitespace(ch) || ch == '\0') {
                        endPos--;
                    } else {
                        break;
                    }
                }
                return str.substring(0, endPos);
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

    /**
     * Process string in byte mode.
     * For 'A' format, removes only ASCII trailing whitespace (not \xa0).
     */
    private String processStringByteMode(String str) {
        switch (format) {
            case 'A':
                // In byte mode, only trim ASCII whitespace: space, tab, newline, etc.
                // Do NOT trim \xa0 (non-breaking space) - it's not ASCII whitespace
                int endPos = str.length();
                while (endPos > 0) {
                    char ch = str.charAt(endPos - 1);
                    // ASCII whitespace: space, tab, newline, carriage return, form feed
                    if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' || ch == '\f' || ch == '\0') {
                        endPos--;
                    } else {
                        break;
                    }
                }
                return str.substring(0, endPos);
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

        // Use ISO-8859-1 for byte mode to preserve binary data
        String result = new String(bytes, StandardCharsets.ISO_8859_1);

        // Apply format-specific processing (byte mode - ASCII whitespace only)
        result = processStringByteMode(result);

        // Pad if necessary and not star count
        // Note: 'A' and 'Z' formats strip content, so don't pad them back!
        // Only 'a' format needs padding to maintain exact count
        if (!isStarCount && format == 'a' && result.length() < count) {
            StringBuilder sb = new StringBuilder(result);
            while (sb.length() < count) {
                sb.append('\0');
            }
            result = sb.toString();
        }

        return result;
    }
}