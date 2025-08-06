package org.perlonjava.operators;

import org.perlonjava.runtime.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Provides functionality to pack a list of scalars into a binary string
 * based on a specified template, similar to Perl's pack function.
 */
public class Pack {

    /**
     * Packs a list of RuntimeScalar objects into a binary string according to the specified template.
     *
     * @param args A RuntimeList containing the template string followed by the values to pack.
     * @return A RuntimeScalar representing the packed binary string.
     * @throws RuntimeException if there are not enough arguments or if an unsupported format character is encountered.
     */
    public static RuntimeScalar pack(RuntimeList args) {
        if (args.isEmpty()) {
            throw new PerlCompilerException("pack: not enough arguments");
        }

        RuntimeScalar templateScalar = args.getFirst();
        String template = templateScalar.toString();

        // Flatten the remaining arguments into a RuntimeArray
        List<RuntimeBase> remainingArgs = args.elements.subList(1, args.elements.size());
        RuntimeArray flattened = new RuntimeArray(remainingArgs.toArray(new RuntimeBase[0]));
        List<RuntimeScalar> values = flattened.elements;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int valueIndex = 0;

        // Track current mode - default is byte mode (U0) unless C0 is specified
        boolean characterMode = false;  // Default is byte mode

        for (int i = 0; i < template.length(); i++) {
            char format = template.charAt(i);

            // Skip spaces
            if (Character.isWhitespace(format)) {
                continue;
            }

            // Check for mode modifiers C0 and U0
            if (format == 'C' && i + 1 < template.length() && template.charAt(i + 1) == '0') {
                characterMode = true;
                i++; // Skip the '0'
                continue;
            } else if (format == 'U' && i + 1 < template.length() && template.charAt(i + 1) == '0') {
                characterMode = false;
                i++; // Skip the '0'
                continue;
            }

            int count = 1;
            boolean hasStar = false;

            // Check for repeat count or '*'
            if (i + 1 < template.length()) {
                char nextChar = template.charAt(i + 1);
                if (Character.isDigit(nextChar)) {
                    int j = i + 1;
                    while (j < template.length() && Character.isDigit(template.charAt(j))) {
                        j++;
                    }
                    count = Integer.parseInt(template.substring(i + 1, j));
                    i = j - 1;
                } else if (nextChar == '*') {
                    hasStar = true;
                    count = values.size() - valueIndex; // Use all remaining values
                    i++; // consume the '*'
                }
            }

            if (format == 'b' || format == 'B') {
                if (valueIndex >= values.size()) {
                    throw new PerlCompilerException("pack: not enough arguments");
                }
                RuntimeScalar value = (RuntimeScalar) values.get(valueIndex++);
                String bitString = value.toString();
                if (hasStar) {
                    count = bitString.length(); // For bit strings with *, use the entire string length
                }
                writeBitString(output, bitString, count, format);
            } else if (format == 'h' || format == 'H') {
                if (valueIndex >= values.size()) {
                    throw new PerlCompilerException("pack: not enough arguments");
                }
                RuntimeScalar value = (RuntimeScalar) values.get(valueIndex++);
                String hexString = value.toString();
                if (hasStar) {
                    count = hexString.length(); // For hex strings with *, use the entire string length
                }
                writeHexString(output, hexString, count, format);
            } else if (format == 'a' || format == 'A' || format == 'Z') {
                // String formats consume only one value
                if (valueIndex >= values.size()) {
                    throw new PerlCompilerException("pack: not enough arguments");
                }
                RuntimeScalar value = (RuntimeScalar) values.get(valueIndex++);
                String str = value.toString();
                if (hasStar) {
                    // For string formats with *, use the string length as count
                    if (format == 'Z') {
                        count = str.length() + 1; // Include space for null terminator
                    } else {
                        count = str.length();
                    }
                }
                // In character mode, we need to handle the string differently
                if (characterMode && format == 'a') {
                    // In character mode with 'a', preserve the string as-is
                    writeString(output, str, count, format, characterMode);
                } else {
                    writeString(output, str, count, format, false);
                }
            } else {
                // Numeric formats
                for (int j = 0; j < count; j++) {
                    if (valueIndex >= values.size()) {
                        throw new PerlCompilerException("pack: not enough arguments");
                    }

                    RuntimeScalar value = (RuntimeScalar) values.get(valueIndex++);

                    switch (format) {
                        case 'C':
                            // Always use numeric value for C format
                            int intValue = value.getInt();
                            output.write(intValue & 0xFF);
                            break;
                        case 's':
                            writeShortLittleEndian(output, value.getInt());
                            break;
                        case 'S':
                            writeShort(output, value.getInt());
                            break;
                        case 'L', 'J':
                            writeLong(output, (long) value.getDouble());
                            break;
                        case 'N':
                            writeIntBigEndian(output, (long) value.getDouble());
                            break;
                        case 'V':
                            writeIntLittleEndian(output, (long) value.getDouble());
                            break;
                        case 'W':
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
                            break;
                        case 'U':
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
                            // U format creates UTF-8 encoded output
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
                            break;
                        case 'n':
                            writeShortBigEndian(output, value.getInt());
                            break;
                        case 'v':
                            writeShortLittleEndian(output, value.getInt());
                            break;
                        case 'f':
                            writeFloat(output, (float) value.getDouble());
                            break;
                        case 'd':
                            writeDouble(output, value.getDouble());
                            break;
                        default:
                            throw new PerlCompilerException("pack: unsupported format character: " + format);
                    }
                }
            }
        }

        // Convert the byte array to a string
        // The result should be a byte string (not UTF-8 decoded)
        byte[] bytes = output.toByteArray();

        // Check if we packed any Unicode characters with U format
        boolean hasUnicodeFormat = false;
        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            if (c == 'U' || c == 'W') {
                hasUnicodeFormat = true;
                break;
            }
        }

        if (hasUnicodeFormat) {
            // For U and W formats, return UTF-8 decoded string
            return new RuntimeScalar(new String(bytes, StandardCharsets.UTF_8));
        } else {
            // For other formats, return as byte string
            return new RuntimeScalar(new String(bytes, StandardCharsets.ISO_8859_1));
        }
    }

    /**
     * Writes a short integer to the output stream in little-endian order.
     *
     * @param output The ByteArrayOutputStream to write to.
     * @param value  The integer value to write.
     */
    private static void writeShort(ByteArrayOutputStream output, int value) {
        output.write(value & 0xFF);
        output.write((value >> 8) & 0xFF);
    }

    /**
     * Writes a short integer to the output stream in big-endian order.
     *
     * @param output The ByteArrayOutputStream to write to.
     * @param value  The integer value to write.
     */
    private static void writeShortBigEndian(ByteArrayOutputStream output, int value) {
        output.write((value >> 8) & 0xFF);
        output.write(value & 0xFF);
    }

    /**
     * Writes a short integer to the output stream in little-endian order.
     *
     * @param output The ByteArrayOutputStream to write to.
     * @param value  The integer value to write.
     */
    private static void writeShortLittleEndian(ByteArrayOutputStream output, int value) {
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
    private static void writeIntBigEndian(ByteArrayOutputStream output, long value) {
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
    private static void writeIntLittleEndian(ByteArrayOutputStream output, long value) {
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
    private static void writeLong(ByteArrayOutputStream output, long value) {
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
    private static void writeFloat(ByteArrayOutputStream output, float value) {
        int intBits = Float.floatToIntBits(value);
        writeInt(output, intBits);
    }

    /**
     * Writes a double to the output stream in little-endian order.
     *
     * @param output The ByteArrayOutputStream to write to.
     * @param value  The double value to write.
     */
    private static void writeDouble(ByteArrayOutputStream output, double value) {
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
     * @param output The ByteArrayOutputStream to write to.
     * @param str    The string to write.
     * @param count  The number of characters to write.
     * @param format The format character indicating the string type.
     * @param characterMode Whether we're in character mode (C0) or byte mode (U0)
     */
    private static void writeString(ByteArrayOutputStream output, String str, int count, char format, boolean characterMode) {
        byte[] bytes;

        if (characterMode && format == 'a') {
            // In character mode with 'a', use the string as-is (already UTF-8 encoded if needed)
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
     * Overloaded writeString for backward compatibility
     */
    private static void writeString(ByteArrayOutputStream output, String str, int count, char format) {
        writeString(output, str, count, format, false);
    }

    /**
     * Writes a bit string to the output stream based on the specified format and count.
     *
     * @param output The ByteArrayOutputStream to write to.
     * @param str    The bit string to write.
     * @param count  The number of bits to write.
     * @param format The format character indicating the bit string type.
     */
    private static void writeBitString(ByteArrayOutputStream output, String str, int count, char format) {
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
     * Writes a hex string to the output stream based on the specified format and count.
     *
     * @param output The ByteArrayOutputStream to write to.
     * @param str    The hex string to write.
     * @param count  The number of hex digits to write.
     * @param format The format character indicating the hex string type ('h' for low nybble first, 'H' for high nybble first).
     */
    private static void writeHexString(ByteArrayOutputStream output, String str, int count, char format) {
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
}
