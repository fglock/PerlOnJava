package org.perlonjava.operators.pack;

import org.perlonjava.runtime.PerlCompilerException;


import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

public class PackWriter {
    /**
     * Writes a uuencoded string to the output stream.
     * Uuencoding converts binary data to printable ASCII characters.
     *
     * @param output The PackBuffer to write to.
     * @param str    The string to uuencode.
     */
    public static void writeUuencodedString(PackBuffer output, String str) {
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
     * @param output The PackBuffer to write to.
     * @param str    The hex string to write.
     * @param count  The number of hex digits to write.
     * @param format The format character indicating the hex string type ('h' for low nybble first, 'H' for high nybble first).
     */
    public static void writeHexString(PackBuffer output, String str, int count, char format) {
        int hexDigitsToProcess = Math.min(str.length(), count);

        // Process pairs of hex digits from the input string
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

        // Handle the last hex digit if we have an odd count from input string
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
            i++;
        }

        // Zero-pad if count is larger than available hex digits
        // Each pair of hex digits produces one byte, so we need (count - hexDigitsToProcess) / 2 more bytes
        int remainingHexDigits = count - hexDigitsToProcess;
        if (remainingHexDigits > 0) {
            // Write zero bytes for the remaining hex digit pairs
            int zeroBytesToWrite = (remainingHexDigits + 1) / 2; // Round up for odd counts
            for (int j = 0; j < zeroBytesToWrite; j++) {
                output.write(0);
            }
        }
    }

    /**
     * Write a BER compressed integer
     */
    public static void writeBER(PackBuffer output, long value) {
        if (value < 0) {
            throw new PerlCompilerException("Cannot compress negative numbers");
        }

        if (value < 128) {
            // Values < 128 are written directly without continuation bit
            output.write((int) value);
        } else {
            // Build bytes from least significant to most significant
            java.util.List<Integer> bytes = new java.util.ArrayList<>();
            long remaining = value;
            
            // Extract 7-bit chunks
            while (remaining > 0) {
                bytes.add((int) (remaining & 0x7F));
                remaining >>= 7;
            }
            
            // Write bytes in reverse order (most significant first)
            // All bytes except the last have continuation bit set
            for (int i = bytes.size() - 1; i >= 0; i--) {
                int byteValue = bytes.get(i);
                if (i > 0) {
                    // Not the last byte - set continuation bit
                    byteValue |= 0x80;
                }
                output.write(byteValue);
            }
        }
    }

    /**
     * Write a BER compressed integer using BigInteger for very large values
     */
    public static void writeBER(PackBuffer output, BigInteger value) {
        if (value.signum() < 0) {
            throw new PerlCompilerException("Cannot compress negative numbers");
        }

        if (value.compareTo(BigInteger.valueOf(128)) < 0) {
            // Values < 128 are written directly without continuation bit
            output.write(value.intValue());
        } else {
            // Build bytes from least significant to most significant
            java.util.List<Integer> bytes = new java.util.ArrayList<>();
            BigInteger remaining = value;
            
            // Extract 7-bit chunks
            while (remaining.signum() > 0) {
                bytes.add(remaining.and(BigInteger.valueOf(0x7F)).intValue());
                remaining = remaining.shiftRight(7);
            }
            
            // Write bytes in reverse order (most significant first)
            // All bytes except the last have continuation bit set
            for (int i = bytes.size() - 1; i >= 0; i--) {
                int byteValue = bytes.get(i);
                if (i > 0) {
                    // Not the last byte - set continuation bit
                    byteValue |= 0x80;
                }
                output.write(byteValue);
            }
        }
    }

    /**
     * Writes a short integer to the output stream in little-endian order.
     *
     * @param output The PackBuffer to write to.
     * @param value  The integer value to write.
     */
    public static void writeShort(PackBuffer output, int value) {
        output.write(value & 0xFF);
        output.write((value >> 8) & 0xFF);
    }

    /**
     * Writes a short integer to the output stream in big-endian order.
     *
     * @param output The PackBuffer to write to.
     * @param value  The integer value to write.
     */
    public static void writeShortBigEndian(PackBuffer output, int value) {
        output.write((value >> 8) & 0xFF);
        output.write(value & 0xFF);
    }

    /**
     * Writes a short integer to the output stream in little-endian order.
     *
     * @param output The PackBuffer to write to.
     * @param value  The integer value to write.
     */
    public static void writeShortLittleEndian(PackBuffer output, int value) {
        output.write(value & 0xFF);
        output.write((value >> 8) & 0xFF);
    }

    /**
     * Writes a 32-bit integer to the output stream in little-endian order.
     *
     * @param output The PackBuffer to write to.
     * @param value  The integer value to write.
     */
    private static void writeInt(PackBuffer output, int value) {
        output.write(value & 0xFF);
        output.write((value >> 8) & 0xFF);
        output.write((value >> 16) & 0xFF);
        output.write((value >> 24) & 0xFF);
    }

    /**
     * Writes a 32-bit integer to the output stream in big-endian order.
     *
     * @param output The PackBuffer to write to.
     * @param value  The long value to write.
     */
    public static void writeIntBigEndian(PackBuffer output, long value) {
        output.write((int) ((value >> 24) & 0xFF));
        output.write((int) ((value >> 16) & 0xFF));
        output.write((int) ((value >> 8) & 0xFF));
        output.write((int) (value & 0xFF));
    }

    /**
     * Writes a 32-bit integer to the output stream in little-endian order.
     *
     * @param output The PackBuffer to write to.
     * @param value  The long value to write.
     */
    public static void writeIntLittleEndian(PackBuffer output, long value) {
        output.write((int) (value & 0xFF));
        output.write((int) ((value >> 8) & 0xFF));
        output.write((int) ((value >> 16) & 0xFF));
        output.write((int) ((value >> 24) & 0xFF));
    }

    /**
     * Writes a long integer to the output stream in little-endian order (4 bytes).
     *
     * @param output The PackBuffer to write to.
     * @param value  The long value to write.
     */
    public static void writeLong(PackBuffer output, long value) {
        output.write((int) (value & 0xFF));
        output.write((int) ((value >> 8) & 0xFF));
        output.write((int) ((value >> 16) & 0xFF));
        output.write((int) ((value >> 24) & 0xFF));
    }

    /**
     * Writes a 64-bit long integer to the output stream in little-endian order (8 bytes).
     *
     * @param output The PackBuffer to write to.
     * @param value  The long value to write.
     */
    public static void writeLongLittleEndian(PackBuffer output, long value) {
        output.write((int) (value & 0xFF));
        output.write((int) ((value >> 8) & 0xFF));
        output.write((int) ((value >> 16) & 0xFF));
        output.write((int) ((value >> 24) & 0xFF));
        output.write((int) ((value >> 32) & 0xFF));
        output.write((int) ((value >> 40) & 0xFF));
        output.write((int) ((value >> 48) & 0xFF));
        output.write((int) ((value >> 56) & 0xFF));
    }

    /**
     * Writes a 64-bit long integer to the output stream in big-endian order (8 bytes).
     *
     * @param output The PackBuffer to write to.
     * @param value  The long value to write.
     */
    public static void writeLongBigEndian(PackBuffer output, long value) {
        output.write((int) ((value >> 56) & 0xFF));
        output.write((int) ((value >> 48) & 0xFF));
        output.write((int) ((value >> 40) & 0xFF));
        output.write((int) ((value >> 32) & 0xFF));
        output.write((int) ((value >> 24) & 0xFF));
        output.write((int) ((value >> 16) & 0xFF));
        output.write((int) ((value >> 8) & 0xFF));
        output.write((int) (value & 0xFF));
    }

    /**
     * Writes a float to the output stream in little-endian order.
     *
     * @param output The PackBuffer to write to.
     * @param value  The float value to write.
     */
    public static void writeFloat(PackBuffer output, float value) {
        int intBits = Float.floatToIntBits(value);
        writeInt(output, intBits);
    }

    /**
     * Writes a double to the output stream in little-endian order.
     *
     * @param output The PackBuffer to write to.
     * @param value  The double value to write.
     */
    public static void writeDouble(PackBuffer output, double value) {
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
     * Writes a float to the output stream in big-endian order.
     *
     * @param output The PackBuffer to write to.
     * @param value  The float value to write.
     */
    public static void writeFloatBigEndian(PackBuffer output, float value) {
        int intBits = Float.floatToIntBits(value);
        writeIntBigEndian(output, (long) intBits);
    }

    /**
     * Writes a float to the output stream in little-endian order.
     *
     * @param output The PackBuffer to write to.
     * @param value  The float value to write.
     */
    public static void writeFloatLittleEndian(PackBuffer output, float value) {
        int intBits = Float.floatToIntBits(value);
        writeIntLittleEndian(output, (long) intBits);
    }

    /**
     * Writes a double to the output stream in big-endian order.
     *
     * @param output The PackBuffer to write to.
     * @param value  The double value to write.
     */
    public static void writeDoubleBigEndian(PackBuffer output, double value) {
        long longBits = Double.doubleToLongBits(value);
        output.write((int) ((longBits >> 56) & 0xFF));
        output.write((int) ((longBits >> 48) & 0xFF));
        output.write((int) ((longBits >> 40) & 0xFF));
        output.write((int) ((longBits >> 32) & 0xFF));
        output.write((int) ((longBits >> 24) & 0xFF));
        output.write((int) ((longBits >> 16) & 0xFF));
        output.write((int) ((longBits >> 8) & 0xFF));
        output.write((int) (longBits & 0xFF));
    }

    /**
     * Writes a double to the output stream in little-endian order.
     *
     * @param output The PackBuffer to write to.
     * @param value  The double value to write.
     */
    public static void writeDoubleLittleEndian(PackBuffer output, double value) {
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
     * @param output   The PackBuffer to write to.
     * @param str      The string to write.
     * @param count    The number of characters to write.
     * @param format   The format character indicating the string type.
     * @param byteMode Whether we're in character mode (C0) or byte mode (U0)
     */
    public static void writeString(PackBuffer output, String str, int count, char format, boolean byteMode) {
        byte[] bytes;

        if (byteMode && format == 'a') {
            // In byte mode with 'a', use the string as-is (already UTF-8 encoded if needed)
            bytes = str.getBytes(StandardCharsets.ISO_8859_1);
        } else {
            // Normal UTF-8 encoding
            bytes = str.getBytes(StandardCharsets.UTF_8);
        }

        // For Z format, null terminator must be within count bytes
        if (format == 'Z') {
            if (count == 0) {
                // Z0 format: write nothing
                return;
            } else if (bytes.length > count) {
                // String is longer than count: truncate to (count-1) bytes + null
                output.write(bytes, 0, count - 1);
                output.write(0);
            } else if (bytes.length == count) {
                // String fits exactly: write all bytes, no null needed
                output.write(bytes, 0, count);
            } else {
                // String is shorter: write string + null padding to count bytes
                output.write(bytes, 0, bytes.length);
                for (int i = bytes.length; i < count; i++) {
                    output.write(0);
                }
            }
        } else {
            // For 'a' and 'A' formats
            int length = Math.min(bytes.length, count);
            output.write(bytes, 0, length);

            // Pad with nulls or spaces
            byte padByte = (format == 'A') ? (byte) ' ' : (byte) 0;
            for (int i = length; i < count; i++) {
                output.write(padByte);
            }
        }
    }

    /**
     * Writes a bit string to the output stream based on the specified format and count.
     *
     * @param output The PackBuffer to write to.
     * @param str    The bit string to write.
     * @param count  The number of bits to write.
     * @param format The format character indicating the bit string type.
     */
    public static void writeBitString(PackBuffer output, String str, int count, char format) {
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
}
