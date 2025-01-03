package org.perlonjava.operators;

import org.perlonjava.runtime.RuntimeBaseEntity;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;

import java.io.ByteArrayOutputStream;
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
            throw new RuntimeException("pack: not enough arguments");
        }

        RuntimeScalar templateScalar = (RuntimeScalar) args.getFirst();
        String template = templateScalar.toString();
        List<RuntimeBaseEntity> values = args.elements.subList(1, args.elements.size());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int valueIndex = 0;

        for (int i = 0; i < template.length(); i++) {
            char format = template.charAt(i);

            // Skip spaces
            if (Character.isWhitespace(format)) {
                continue;
            }

            int count = 1;

            // Check for repeat count
            if (i + 1 < template.length() && Character.isDigit(template.charAt(i + 1))) {
                int j = i + 1;
                while (j < template.length() && Character.isDigit(template.charAt(j))) {
                    j++;
                }
                count = Integer.parseInt(template.substring(i + 1, j));
                i = j - 1;
            }

            if (format == 'b' || format == 'B') {
                if (valueIndex >= values.size()) {
                    throw new RuntimeException("pack: not enough arguments");
                }
                RuntimeScalar value = (RuntimeScalar) values.get(valueIndex++);
                writeBitString(output, value.toString(), count, format);
            } else {
                for (int j = 0; j < count; j++) {
                    if (valueIndex >= values.size()) {
                        throw new RuntimeException("pack: not enough arguments");
                    }

                    RuntimeScalar value = (RuntimeScalar) values.get(valueIndex++);

                    switch (format) {
                        case 'C':
                            output.write(value.getInt() & 0xFF);
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
                        case 'a':
                        case 'A':
                        case 'Z':
                            writeString(output, value.toString(), count, format);
                            j = count; // Exit the inner loop
                            break;
                        default:
                            throw new RuntimeException("pack: unsupported format character: " + format);
                    }
                }
            }
        }

        // Convert the byte array to a string using ISO-8859-1 encoding
        return new RuntimeScalar(output.toString(StandardCharsets.ISO_8859_1));
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
     */
    private static void writeString(ByteArrayOutputStream output, String str, int count, char format) {
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
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

            // Check if the last byte written is a null byte

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
    private static void writeBitString(ByteArrayOutputStream output, String str, int count, char format) {
        int bitIndex = 0;
        int byteValue = 0;
        for (int i = 0; i < str.length(); i++) {
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
