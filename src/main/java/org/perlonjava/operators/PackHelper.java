package org.perlonjava.operators;

import org.perlonjava.runtime.PerlCompilerException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public class PackHelper {
    /**
     * Writes a uuencoded string to the output stream.
     * Uuencoding converts binary data to printable ASCII characters.
     *
     * @param output The ByteArrayOutputStream to write to.
     * @param str    The string to uuencode.
     */
    static void writeUuencodedString(ByteArrayOutputStream output, String str) {
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        int length = bytes.length;

        // Uuencode line format: length byte followed by encoded data
        // For pack 'u', we encode the entire string as one line
        output.write((length & 0x3F) + 32); // Length byte (add 32 to make printable)

        // Process in groups of 3 bytes (which encode to 4 uuencoded characters)
        for (int i = 0; i < length; i += 3) {
            int b1 = (i < length) ? (bytes[i] & 0xFF) : 0;
            int b2 = (i + 1 < length) ? (bytes[i + 1] & 0xFF) : 0;
            int b3 = (i + 2 < length) ? (bytes[i + 2] & 0xFF) : 0;

            // Convert 3 bytes to 4 uuencoded characters
            int c1 = (b1 >> 2) & 0x3F;
            int c2 = ((b1 << 4) | (b2 >> 4)) & 0x3F;
            int c3 = ((b2 << 2) | (b3 >> 6)) & 0x3F;
            int c4 = b3 & 0x3F;

            // Write encoded characters (add 32 to convert to printable ASCII)
            output.write(c1 + 32);
            output.write(c2 + 32);
            output.write(c3 + 32);
            output.write(c4 + 32);
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
    static void writeHexString(ByteArrayOutputStream output, String str, int count, char format) {
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
    static boolean isIntegerFormat(char format) {
        switch (format) {
            case 'c':
            case 'C':
            case 's':
            case 'S':
            case 'l':
            case 'L':
            case 'i':
            case 'I':
            case 'n':
            case 'N':
            case 'v':
            case 'V':
            case 'j':
            case 'J':
            case 'w':
            case 'W':
            case 'U':
                return true;
            default:
                return false;
        }
    }

    /**
     * Write a BER compressed integer
     */
    static void writeBER(ByteArrayOutputStream output, long value) {
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
    static void writeShort(ByteArrayOutputStream output, int value) {
        output.write(value & 0xFF);
        output.write((value >> 8) & 0xFF);
    }

    /**
     * Writes a short integer to the output stream in big-endian order.
     *
     * @param output The ByteArrayOutputStream to write to.
     * @param value  The integer value to write.
     */
    static void writeShortBigEndian(ByteArrayOutputStream output, int value) {
        output.write((value >> 8) & 0xFF);
        output.write(value & 0xFF);
    }

    /**
     * Writes a short integer to the output stream in little-endian order.
     *
     * @param output The ByteArrayOutputStream to write to.
     * @param value  The integer value to write.
     */
    static void writeShortLittleEndian(ByteArrayOutputStream output, int value) {
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
    static void writeIntBigEndian(ByteArrayOutputStream output, long value) {
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
    static void writeIntLittleEndian(ByteArrayOutputStream output, long value) {
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
    static void writeLong(ByteArrayOutputStream output, long value) {
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
    static void writeFloat(ByteArrayOutputStream output, float value) {
        int intBits = Float.floatToIntBits(value);
        writeInt(output, intBits);
    }

    /**
     * Writes a double to the output stream in little-endian order.
     *
     * @param output The ByteArrayOutputStream to write to.
     * @param value  The double value to write.
     */
    static void writeDouble(ByteArrayOutputStream output, double value) {
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
    static void writeString(ByteArrayOutputStream output, String str, int count, char format, boolean byteMode) {
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
    static void writeBitString(ByteArrayOutputStream output, String str, int count, char format) {
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
