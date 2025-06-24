package org.perlonjava.operators;

import org.perlonjava.runtime.RuntimeBaseEntity;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Provides functionality to unpack binary data into a list of scalars
 * based on a specified template, similar to Perl's unpack function.
 */
public class Unpack {

    /**
     * Unpacks binary data into a list of RuntimeScalar objects according to the specified template.
     *
     * @param args A RuntimeList containing the template string and the packed data.
     * @return A RuntimeList of unpacked RuntimeScalar objects.
     * @throws RuntimeException if there are not enough arguments or if the data is insufficient for unpacking.
     */
    public static RuntimeList unpack(RuntimeList args) {
        if (args.elements.size() < 2) {
            throw new RuntimeException("unpack: not enough arguments");
        }
        RuntimeScalar templateScalar = (RuntimeScalar) args.elements.get(0);
        RuntimeScalar packedData = (RuntimeScalar) args.elements.get(1);

        String template = templateScalar.toString();
        byte[] data = packedData.toString().getBytes(StandardCharsets.ISO_8859_1);

        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN); // Ensure consistent byte order

        RuntimeList out = new RuntimeList();
        List<RuntimeBaseEntity> values = out.elements;

        for (int i = 0; i < template.length(); i++) {
            char format = template.charAt(i);

            // Skip spaces
            if (Character.isWhitespace(format)) {
                continue;
            }

            int count = 1;
            boolean isStarCount = false;

            // Check for repeat count or *
            if (i + 1 < template.length()) {
                char nextChar = template.charAt(i + 1);
                if (nextChar == '*') {
                    isStarCount = true;
                    i++; // Skip the '*' character
                } else if (Character.isDigit(nextChar)) {
                    int j = i + 1;
                    while (j < template.length() && Character.isDigit(template.charAt(j))) {
                        j++;
                    }
                    count = Integer.parseInt(template.substring(i + 1, j));
                    i = j - 1;
                }
            }

            if (isStarCount) {
                // For star count, process all remaining bytes for this format
                if (format == 'a' || format == 'A' || format == 'Z') {
                    // For string formats, read all remaining bytes as one string
                    count = buffer.remaining();
                } else {
                    // For other formats, calculate how many items we can read
                    int formatSize = getFormatSize(format);
                    if (formatSize == 0) {
                        throw new RuntimeException("unpack: unknown format size for: " + format);
                    }
                    count = buffer.remaining() / formatSize;
                }
            }

            for (int j = 0; j < count; j++) {
                if (buffer.remaining() < getFormatSize(format)) {
                    if (isStarCount) {
                        break; // For star count, just stop when we run out of data
                    }
                    throw new RuntimeException("unpack: not enough data");
                }

                switch (format) {
                    case 'C':
                        values.add(new RuntimeScalar(buffer.get() & 0xFF));
                        break;
                    case 'S':
                        values.add(new RuntimeScalar(buffer.getShort() & 0xFFFF));
                        break;
                    case 'L':
                        values.add(new RuntimeScalar(buffer.getInt() & 0xFFFFFFFFL));
                        break;
                    case 'N':
                        values.add(new RuntimeScalar(readIntBigEndian(buffer) & 0xFFFFFFFFL));
                        break;
                    case 'V':
                        values.add(new RuntimeScalar(readIntLittleEndian(buffer) & 0xFFFFFFFFL));
                        break;
                    case 'n':
                        values.add(new RuntimeScalar(readShortBigEndian(buffer)));
                        break;
                    case 'v':
                        values.add(new RuntimeScalar(readShortLittleEndian(buffer)));
                        break;
                    case 'f':
                        float floatValue = buffer.getFloat();
                        values.add(new RuntimeScalar(floatValue));
                        break;
                    case 'd':
                        double doubleValue = buffer.getDouble();
                        values.add(new RuntimeScalar(doubleValue));
                        break;
                    case 'a':
                    case 'A':
                    case 'Z':
                        values.add(new RuntimeScalar(readString(buffer, count, format)));
                        j = count; // Exit the inner loop
                        break;
                    case 'b':
                    case 'B':
                        values.add(new RuntimeScalar(readBitString(buffer, count, format)));
                        j = count; // Exit the inner loop
                        break;
                    default:
                        throw new RuntimeException("unpack: unsupported format character: " + format);
                }
            }
        }

        return out;
    }

    /**
     * Determines the size in bytes of the data type specified by the format character.
     *
     * @param format The format character indicating the data type.
     * @return The size in bytes of the data type.
     */
    private static int getFormatSize(char format) {
        switch (format) {
            case 'C':
                return 1;
            case 'S':
            case 'n':
            case 'v':
                return 2;
            case 'L':
            case 'N':
            case 'V':
            case 'f':
                return 4;
            case 'd':
                return 8;
            default:
                return 1; // For string and bit formats, we'll check in their respective methods
        }
    }

    /**
     * Reads a 16-bit short from the buffer in big-endian order.
     *
     * @param buffer The ByteBuffer containing the data.
     * @return The short value read from the buffer.
     */
    private static int readShortBigEndian(ByteBuffer buffer) {
        return (buffer.get() & 0xFF) << 8 | buffer.get() & 0xFF;
    }

    /**
     * Reads a 16-bit short from the buffer in little-endian order.
     *
     * @param buffer The ByteBuffer containing the data.
     * @return The short value read from the buffer.
     */
    private static int readShortLittleEndian(ByteBuffer buffer) {
        return buffer.get() & 0xFF | (buffer.get() & 0xFF) << 8;
    }

    /**
     * Reads a 32-bit integer from the buffer in big-endian order.
     *
     * @param buffer The ByteBuffer containing the data.
     * @return The integer value read from the buffer.
     */
    private static int readIntBigEndian(ByteBuffer buffer) {
        return (buffer.get() & 0xFF) << 24 | (buffer.get() & 0xFF) << 16 | (buffer.get() & 0xFF) << 8 | buffer.get() & 0xFF;
    }

    /**
     * Reads a 32-bit integer from the buffer in little-endian order.
     *
     * @param buffer The ByteBuffer containing the data.
     * @return The integer value read from the buffer.
     */
    private static int readIntLittleEndian(ByteBuffer buffer) {
        return buffer.get() & 0xFF | (buffer.get() & 0xFF) << 8 | (buffer.get() & 0xFF) << 16 | (buffer.get() & 0xFF) << 24;
    }

    /**
     * Reads a string from the buffer based on the specified format and count.
     *
     * @param buffer The ByteBuffer containing the data.
     * @param count  The number of characters to read.
     * @param format The format character indicating the string type.
     * @return The string read from the buffer.
     */
    private static String readString(ByteBuffer buffer, int count, char format) {
        byte[] bytes = new byte[count];
        buffer.get(bytes, 0, count);

        if (format == 'Z') {
            int nullIndex = 0;
            while (nullIndex < count && bytes[nullIndex] != 0) {
                nullIndex++;
            }
            return new String(bytes, 0, nullIndex, StandardCharsets.UTF_8);
        } else if (format == 'a') {
            return new String(bytes, StandardCharsets.UTF_8);
        } else { // 'A'
            return new String(bytes, StandardCharsets.UTF_8).trim();
        }
    }

    /**
     * Reads a bit string from the buffer based on the specified format and count.
     *
     * @param buffer The ByteBuffer containing the data.
     * @param count  The number of bits to read.
     * @param format The format character indicating the bit string type.
     * @return The bit string read from the buffer.
     */
    private static String readBitString(ByteBuffer buffer, int count, char format) {
        StringBuilder bitString = new StringBuilder();
        int bytesToRead = (count + 7) / 8;
        byte[] bytes = new byte[bytesToRead];
        buffer.get(bytes);

        for (int i = 0; i < count; i++) {
            int byteIndex = i / 8;
            int bitIndex = i % 8;
            boolean bit = (bytes[byteIndex] & (1 << (format == 'b' ? bitIndex : 7 - bitIndex))) != 0;
            bitString.append(bit ? '1' : '0');
        }

        return bitString.toString();
    }
}
