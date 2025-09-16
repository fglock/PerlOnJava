package org.perlonjava.operators.pack;

import org.perlonjava.operators.Pack;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeScalar;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class PackHelper {
    /**
     * Writes a uuencoded string to the output stream.
     * Uuencoding converts binary data to printable ASCII characters.
     *
     * @param output The ByteArrayOutputStream to write to.
     * @param str    The string to uuencode.
     */
    public static void writeUuencodedString(ByteArrayOutputStream output, String str) {
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        int length = bytes.length;

        // Process in chunks of 45 bytes (standard uuencode line length)
        for (int lineStart = 0; lineStart < length; lineStart += 45) {
            int lineLength = Math.min(45, length - lineStart);

            // Write line length character
            output.write((lineLength & 0x3F) + 32);

            // Process groups of 3 bytes
            for (int i = lineStart; i < lineStart + lineLength; i += 3) {
                int b1 = (i < length) ? (bytes[i] & 0xFF) : 0;
                int b2 = (i + 1 < length) ? (bytes[i + 1] & 0xFF) : 0;
                int b3 = (i + 2 < length) ? (bytes[i + 2] & 0xFF) : 0;

                // Convert 3 bytes to 4 uuencoded characters
                int c1 = (b1 >> 2) & 0x3F;
                int c2 = ((b1 << 4) | (b2 >> 4)) & 0x3F;
                int c3 = ((b2 << 2) | (b3 >> 6)) & 0x3F;
                int c4 = b3 & 0x3F;

                // Convert to printable characters (space becomes backtick)
                output.write(c1 == 0 ? 96 : c1 + 32);  // 96 is backtick
                output.write(c2 == 0 ? 96 : c2 + 32);
                if (i + 1 < length || i + 1 < lineStart + lineLength) {
                    output.write(c3 == 0 ? 96 : c3 + 32);
                }
                if (i + 2 < length || i + 2 < lineStart + lineLength) {
                    output.write(c4 == 0 ? 96 : c4 + 32);
                }
            }

            // Add newline after each line (except possibly the last)
            if (lineStart + 45 < length) {
                output.write('\n');
            }
        }
    }

    /**
     * Writes a hex string to the output stream based on the specified format and count.
     *
     * @param output The ByteArrayOutputStream to write to.
     * @param str    The hex string to write.
     * @param count  The number of hex digits to write.
     * @param format The format character indicating the hex string type ('h' for low nybble first, 'H' for high nybble first).
     */
    public static void writeHexString(ByteArrayOutputStream output, String str, int count, char format) {
        int hexDigitsToProcess = Math.min(str.length(), count);

        // Process pairs of hex digits
        int i;
        for (i = 0; i + 1 < hexDigitsToProcess; i += 2) {
            // Get first nybble
            char c1 = str.charAt(i);
            int nybble1 = Character.digit(c1, 16);
            if (nybble1 == -1) nybble1 = 0; // Default to 0 for invalid hex digit

            // Get second nybble
            char c2 = str.charAt(i + 1);
            int nybble2 = Character.digit(c2, 16);
            if (nybble2 == -1) nybble2 = 0; // Default to 0 for invalid hex digit

            int byteValue;
            if (format == 'h') {
                // Low nybble first
                byteValue = (nybble2 << 4) | nybble1;
            } else {
                // High nybble first
                byteValue = (nybble1 << 4) | nybble2;
            }

            output.write(byteValue);
        }

        // Handle the last hex digit if we have an odd count
        if (i < hexDigitsToProcess) {
            char c = str.charAt(i);
            int nybble = Character.digit(c, 16);
            if (nybble == -1) nybble = 0;

            int byteValue;
            if (format == 'h') {
                // Low nybble first - the single digit goes in the low nybble
                byteValue = nybble;
            } else {
                // High nybble first - the single digit goes in the high nybble
                byteValue = nybble << 4;
            }

            output.write(byteValue);
        }
    }

    /**
     * Check if the format is an integer format that should reject Inf/NaN values
     */
    public static boolean isIntegerFormat(char format) {
        return switch (format) {
            case 'c', 'C', 's', 'S', 'l', 'L', 'i', 'I', 'n', 'N', 'v', 'V', 'j', 'J', 'w', 'W', 'U' -> true;
            default -> false;
        };
    }

    /**
     * Write a BER compressed integer
     */
    public static void writeBER(ByteArrayOutputStream output, long value) {
        if (value < 0) {
            throw new PerlCompilerException("Cannot compress negative numbers");
        }

        if (value < 128) {
            output.write((int) value);
        } else {
            // Write high-order bytes with continuation bit set
            writeBER(output, value >> 7);
            // Write low-order 7 bits with continuation bit
            output.write((int) ((value & 0x7F) | 0x80));
        }
    }

    /**
     * Writes a short integer to the output stream in little-endian order.
     *
     * @param output The ByteArrayOutputStream to write to.
     * @param value  The integer value to write.
     */
    public static void writeShort(ByteArrayOutputStream output, int value) {
        output.write(value & 0xFF);
        output.write((value >> 8) & 0xFF);
    }

    /**
     * Writes a short integer to the output stream in big-endian order.
     *
     * @param output The ByteArrayOutputStream to write to.
     * @param value  The integer value to write.
     */
    public static void writeShortBigEndian(ByteArrayOutputStream output, int value) {
        output.write((value >> 8) & 0xFF);
        output.write(value & 0xFF);
    }

    /**
     * Writes a short integer to the output stream in little-endian order.
     *
     * @param output The ByteArrayOutputStream to write to.
     * @param value  The integer value to write.
     */
    public static void writeShortLittleEndian(ByteArrayOutputStream output, int value) {
        output.write(value & 0xFF);
        output.write((value >> 8) & 0xFF);
    }

    /**
     * Writes a 32-bit integer to the output stream in little-endian order.
     *
     * @param output The ByteArrayOutputStream to write to.
     * @param value  The integer value to write.
     */
    private static void writeInt(ByteArrayOutputStream output, int value) {
        output.write(value & 0xFF);
        output.write((value >> 8) & 0xFF);
        output.write((value >> 16) & 0xFF);
        output.write((value >> 24) & 0xFF);
    }

    /**
     * Writes a 32-bit integer to the output stream in big-endian order.
     *
     * @param output The ByteArrayOutputStream to write to.
     * @param value  The long value to write.
     */
    public static void writeIntBigEndian(ByteArrayOutputStream output, long value) {
        output.write((int) ((value >> 24) & 0xFF));
        output.write((int) ((value >> 16) & 0xFF));
        output.write((int) ((value >> 8) & 0xFF));
        output.write((int) (value & 0xFF));
    }

    /**
     * Writes a 32-bit integer to the output stream in little-endian order.
     *
     * @param output The ByteArrayOutputStream to write to.
     * @param value  The long value to write.
     */
    public static void writeIntLittleEndian(ByteArrayOutputStream output, long value) {
        output.write((int) (value & 0xFF));
        output.write((int) ((value >> 8) & 0xFF));
        output.write((int) ((value >> 16) & 0xFF));
        output.write((int) ((value >> 24) & 0xFF));
    }

    /**
     * Writes a long integer to the output stream in little-endian order.
     *
     * @param output The ByteArrayOutputStream to write to.
     * @param value  The long value to write.
     */
    public static void writeLong(ByteArrayOutputStream output, long value) {
        output.write((int) (value & 0xFF));
        output.write((int) ((value >> 8) & 0xFF));
        output.write((int) ((value >> 16) & 0xFF));
        output.write((int) ((value >> 24) & 0xFF));
    }

    /**
     * Writes a float to the output stream in little-endian order.
     *
     * @param output The ByteArrayOutputStream to write to.
     * @param value  The float value to write.
     */
    public static void writeFloat(ByteArrayOutputStream output, float value) {
        int intBits = Float.floatToIntBits(value);
        writeInt(output, intBits);
    }

    /**
     * Writes a double to the output stream in little-endian order.
     *
     * @param output The ByteArrayOutputStream to write to.
     * @param value  The double value to write.
     */
    public static void writeDouble(ByteArrayOutputStream output, double value) {
        long longBits = Double.doubleToLongBits(value);
        output.write((int) (longBits & 0xFF));
        output.write((int) ((longBits >> 8) & 0xFF));
        output.write((int) ((longBits >> 16) & 0xFF));
        output.write((int) ((longBits >> 24) & 0xFF));
        output.write((int) ((longBits >> 32) & 0xFF));
        output.write((int) ((longBits >> 40) & 0xFF));
        output.write((int) ((longBits >> 48) & 0xFF));
        output.write((int) ((longBits >> 56) & 0xFF));
    }

    /**
     * Writes a string to the output stream based on the specified format and count.
     *
     * @param output   The ByteArrayOutputStream to write to.
     * @param str      The string to write.
     * @param count    The number of characters to write.
     * @param format   The format character indicating the string type.
     * @param byteMode Whether we're in character mode (C0) or byte mode (U0)
     */
    public static void writeString(ByteArrayOutputStream output, String str, int count, char format, boolean byteMode) {
        byte[] bytes;

        if (byteMode && format == 'a') {
            // In byte mode with 'a', use the string as-is (already UTF-8 encoded if needed)
            bytes = str.getBytes(StandardCharsets.ISO_8859_1);
        } else {
            // Normal UTF-8 encoding
            bytes = str.getBytes(StandardCharsets.UTF_8);
        }

        int length = Math.min(bytes.length, count);
        output.write(bytes, 0, length);

        // Pad with nulls or spaces
        byte padByte = (format == 'A') ? (byte) ' ' : (byte) 0;
        for (int i = length; i < count; i++) {
            output.write(padByte);
        }

        // Add null terminator for 'Z' format if not already present
        if (format == 'Z') {
            byte[] currentOutput = output.toByteArray();
            boolean needsNullTerminator = currentOutput.length <= 0 || currentOutput[currentOutput.length - 1] != 0;

            if (needsNullTerminator) {
                output.write(0);
            }
        }
    }

    /**
     * Writes a bit string to the output stream based on the specified format and count.
     *
     * @param output The ByteArrayOutputStream to write to.
     * @param str    The bit string to write.
     * @param count  The number of bits to write.
     * @param format The format character indicating the bit string type.
     */
    public static void writeBitString(ByteArrayOutputStream output, String str, int count, char format) {
        int bitIndex = 0;
        int byteValue = 0;
        int bitsToProcess = Math.min(str.length(), count);

        for (int i = 0; i < bitsToProcess; i++) {
            char c = str.charAt(i);
            if (format == 'b') {
                byteValue |= (c == '1' ? 1 : 0) << bitIndex;
            } else {
                byteValue |= (c == '1' ? 1 : 0) << (7 - bitIndex);
            }
            bitIndex++;
            if (bitIndex == 8) {
                output.write(byteValue);
                bitIndex = 0;
                byteValue = 0;
            }
        }
        if (bitIndex > 0) {
            output.write(byteValue);
        }
    }

    /**
     * Check if a format at the given position is part of a '/' construct.
     * For example, in "n/a*", the 'n' is part of a '/' construct.
     *
     * @param template The template string
     * @param position The position of the format character
     * @return The position of '/' if found, or -1 if not part of '/' construct
     */
    public static int checkForSlashConstruct(String template, int position) {
        int lookAhead = position + 1;

        // Skip modifiers (<, >, !)
        while (lookAhead < template.length() &&
               (template.charAt(lookAhead) == '<' ||
                template.charAt(lookAhead) == '>' ||
                template.charAt(lookAhead) == '!')) {
            lookAhead++;
        }

        // Skip count or *
        if (lookAhead < template.length()) {
            if (template.charAt(lookAhead) == '*') {
                lookAhead++;
            } else if (Character.isDigit(template.charAt(lookAhead))) {
                while (lookAhead < template.length() && Character.isDigit(template.charAt(lookAhead))) {
                    lookAhead++;
                }
            }
        }

        // Check if followed by '/'
        if (lookAhead < template.length() && template.charAt(lookAhead) == '/') {
            return lookAhead;
        }

        return -1;
    }

    public static int handleStringFormat(int valueIndex, List<RuntimeScalar> values, boolean hasStar, char format, int count, boolean byteMode, ByteArrayOutputStream output) {
        // String formats consume only one value
        if (valueIndex >= values.size()) {
            throw new PerlCompilerException("pack: not enough arguments");
        }
        RuntimeScalar value = values.get(valueIndex++);
        String str = value.toString();
        if (hasStar) {
            // For string formats with *, use the string length as count
            if (format == 'Z') {
                count = str.length() + 1; // Include space for null terminator
            } else {
                count = str.length();
            }
        }
        // In byte mode, we need to handle the string differently
        if (byteMode && format == 'a') {
            // In byte mode with 'a', preserve the string as-is
            writeString(output, str, count, format, byteMode);
        } else {
            writeString(output, str, count, format, false);
        }
        return valueIndex;
    }

    public static void handleInfinity(RuntimeScalar value, char format) {
        String strValue = value.toString().trim();
        if (strValue.equalsIgnoreCase("Inf") || strValue.equalsIgnoreCase("+Inf") || strValue.equalsIgnoreCase("Infinity")) {
            if (format == 'w') {
                throw new PerlCompilerException("Cannot compress Inf");
            } else {
                throw new PerlCompilerException("Cannot pack Inf");
            }
        } else if (strValue.equalsIgnoreCase("-Inf") || strValue.equalsIgnoreCase("-Infinity")) {
            if (format == 'w') {
                throw new PerlCompilerException("Cannot compress -Inf");
            } else {
                throw new PerlCompilerException("Cannot pack -Inf");
            }
        } else if (strValue.equalsIgnoreCase("NaN")) {
            if (format == 'w') {
                throw new PerlCompilerException("Cannot compress NaN");
            } else {
                throw new PerlCompilerException("Cannot pack NaN");
            }
        }
    }

    public static void packW(RuntimeScalar value, ByteArrayOutputStream output) {
        // Pack a Unicode code point as UTF-8 bytes
        int codePoint;
        String strValue = value.toString();
        if (!strValue.isEmpty() && !Character.isDigit(strValue.charAt(0))) {
            // If it's a character, get its code point
            codePoint = strValue.codePointAt(0);
        } else {
            // If it's a number, use it directly as code point
            codePoint = value.getInt();
        }

        if (Character.isValidCodePoint(codePoint)) {
            String unicodeChar = new String(Character.toChars(codePoint));
            byte[] utf8Bytes = unicodeChar.getBytes(StandardCharsets.UTF_8);
            try {
                output.write(utf8Bytes);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            throw new PerlCompilerException("pack: invalid Unicode code point: " + codePoint);
        }
    }

    public static int countValuesNeeded(String template) {
        int count = 0;
        System.err.println("DEBUG countValuesNeeded: template='" + template + "'");
        for (int i = 0; i < template.length(); i++) {
            char format = template.charAt(i);
            System.err.println("DEBUG countValuesNeeded: i=" + i + ", format='" + format + "', count=" + count);

            if (Character.isWhitespace(format) || format == '#') {
                continue;
            }

            // Skip comments
            if (format == '#') {
                while (i + 1 < template.length() && template.charAt(i + 1) != '\n') {
                    i++;
                }
                continue;
            }

            if (format == '(') {
                int closePos = findMatchingParen(template, i);
                if (closePos != -1) {
                    // Count values needed for the group content
                    String groupContent = template.substring(i + 1, closePos);
                    int groupCount = countValuesNeeded(groupContent);

                    // Skip past the group and check for repeat count
                    i = closePos;
                    // Skip modifiers
                    while (i + 1 < template.length() &&
                            (template.charAt(i + 1) == '<' ||
                                    template.charAt(i + 1) == '>' ||
                                    template.charAt(i + 1) == '!')) {
                        i++;
                    }

                    // Check for repeat count after group
                    int repeatCount = 1;
                    if (i + 1 < template.length()) {
                        if (template.charAt(i + 1) == '*') {
                            System.err.println("DEBUG countValuesNeeded: returning MAX_VALUE due to group with *");
                            return Integer.MAX_VALUE;
                        } else if (Character.isDigit(template.charAt(i + 1))) {
                            int j = i + 1;
                            while (j < template.length() && Character.isDigit(template.charAt(j))) {
                                j++;
                            }
                            repeatCount = Integer.parseInt(template.substring(i + 1, j));
                            i = j - 1;
                        }
                    }

                    count += groupCount * repeatCount;
                }
                continue;
            }

            // FIRST: Check if this numeric format is part of a '/' construct
            if (isNumericFormat(format) || format == 'Z') {
                int slashPos = checkForSlashConstruct(template, i);
                if (slashPos != -1) {
                    System.err.println("DEBUG countValuesNeeded: format '" + format + "' at " + i + " is part of N/ construct, skipping to " + slashPos);
                    // This numeric format is part of N/X construct
                    // It doesn't consume a value - skip to the '/'
                    i = slashPos - 1; // -1 because loop will increment
                    continue;
                }
            }

            // Handle '/' - it doesn't consume values itself
            if (format == '/') {
                // Skip whitespace after '/'
                int j = i + 1;
                while (j < template.length() && Character.isWhitespace(template.charAt(j))) {
                    j++;
                }
                if (j < template.length()) {
                    // Skip the string format after '/'
                    i = j;
                    format = template.charAt(i);
                    System.err.println("DEBUG countValuesNeeded: '/' skipping to format '" + format + "' at position " + i);
                }
                // Fall through to count the string format normally
            }

            // Skip modifiers
            while (i + 1 < template.length() &&
                    (template.charAt(i + 1) == '<' ||
                            template.charAt(i + 1) == '>' ||
                            template.charAt(i + 1) == '!')) {
                i++;
            }

            // Parse repeat count
            int repeatCount = 1;
            boolean hasStar = false;
            if (i + 1 < template.length()) {
                if (template.charAt(i + 1) == '*') {
                    hasStar = true;
                    i++;
                    System.err.println("DEBUG countValuesNeeded: found * after format '" + format + "'");
                } else if (template.charAt(i + 1) == '[') {
                    // Parse repeat count in brackets [n]
                    int j = i + 2;
                    while (j < template.length() && Character.isDigit(template.charAt(j))) {
                        j++;
                    }
                    if (j < template.length() && template.charAt(j) == ']') {
                        repeatCount = Integer.parseInt(template.substring(i + 2, j));
                        i = j;
                    }
                } else if (Character.isDigit(template.charAt(i + 1))) {
                    int j = i + 1;
                    while (j < template.length() && Character.isDigit(template.charAt(j))) {
                        j++;
                    }
                    repeatCount = Integer.parseInt(template.substring(i + 1, j));
                    i = j - 1;
                }
            }

            // Count based on format
            if ("xX@.".indexOf(format) >= 0) {
                // These don't consume values
                continue;
            } else if ("aAZbBhHu".indexOf(format) >= 0) {
                // String/binary formats consume exactly one value regardless of repeat count
                count += 1;
                System.err.println("DEBUG countValuesNeeded: string format '" + format + "' adds 1, count=" + count);
            } else if (isNumericFormat(format) || format == 'p') {
                // Numeric formats consume 'repeatCount' values (or return MAX for *)
                if (hasStar) {
                    System.err.println("DEBUG countValuesNeeded: returning MAX_VALUE due to numeric format '" + format + "' with *");
                    return Integer.MAX_VALUE;
                }
                count += repeatCount;
                System.err.println("DEBUG countValuesNeeded: numeric format '" + format + "' adds " + repeatCount + ", count=" + count);
            }
        }
        System.err.println("DEBUG countValuesNeeded: returning " + count);
        return count;
    }

    public static boolean hasConflictingEndianness(String groupContent, char groupEndian) {
        // Check if the group content has endianness modifiers that conflict with the group's endianness
        for (int i = 0; i < groupContent.length(); i++) {
            char c = groupContent.charAt(i);

            // Check for nested groups with conflicting endianness
            if (c == '(') {
                int closePos = findMatchingParen(groupContent, i);
                if (closePos != -1) {
                    // Check for endianness modifier after the nested group
                    int checkPos = closePos + 1;
                    while (checkPos < groupContent.length()) {
                        char modifier = groupContent.charAt(checkPos);
                        if (modifier == '<' || modifier == '>') {
                            if ((modifier == '<' && groupEndian == '>') ||
                                    (modifier == '>' && groupEndian == '<')) {
                                return true;
                            }
                            break;
                        } else if (modifier == '!') {
                            checkPos++;
                        } else {
                            break;
                        }
                    }
                    // Also recursively check inside the nested group
                    String nestedContent = groupContent.substring(i + 1, closePos);
                    if (hasConflictingEndianness(nestedContent, groupEndian)) {
                        return true;
                    }
                    i = closePos;
                }
            } else if ((c == '<' || c == '>') && i > 0) {
                // Check if this is a modifier for a format that supports it
                char prevChar = groupContent.charAt(i - 1);
                if ("sSiIlLqQjJfFdDpP".indexOf(prevChar) >= 0) {
                    if ((c == '<' && groupEndian == '>') || (c == '>' && groupEndian == '<')) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static int findMatchingParen(String template, int openPos) {
        int depth = 1;
        for (int i = openPos + 1; i < template.length(); i++) {
            if (template.charAt(i) == '(') depth++;
            else if (template.charAt(i) == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    public static boolean isNumericFormat(char format) {
        return switch (format) {
            case 'c', 'C', 's', 'S', 'l', 'L', 'i', 'I', 'n', 'N', 'v', 'V', 'w', 'W', 'q', 'Q', 'j', 'J',
                 'Z' ->  // Z can be used as a count
                    true;
            default -> false;
        };
    }

    public static boolean packU(RuntimeScalar value, boolean byteMode, boolean hasUnicodeInNormalMode, ByteArrayOutputStream output) {
        // Pack a Unicode character number as UTF-8
        int codePoint1;
        String strValue1 = value.toString();
        if (!strValue1.isEmpty() && !Character.isDigit(strValue1.charAt(0))) {
            // If it's a character, get its code point
            codePoint1 = strValue1.codePointAt(0);
        } else {
            // If it's a number, use it directly
            codePoint1 = value.getInt();
        }

        // Track if U is used in character mode (not byte mode)
        if (!byteMode) {
            hasUnicodeInNormalMode = true;
        }

        // U format always writes UTF-8 encoded bytes
        // The difference between modes is handled at the final string conversion
        if (Character.isValidCodePoint(codePoint1)) {
            String unicodeChar1 = new String(Character.toChars(codePoint1));
            byte[] utf8Bytes1 = unicodeChar1.getBytes(StandardCharsets.UTF_8);
            try {
                output.write(utf8Bytes1);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            throw new PerlCompilerException("pack: invalid Unicode code point: " + codePoint1);
        }
        return hasUnicodeInNormalMode;
    }
}
