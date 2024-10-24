package org.perlonjava.operators;

import org.perlonjava.runtime.RuntimeBaseEntity;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class Unpack {

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

            // Check for repeat count
            if (i + 1 < template.length() && Character.isDigit(template.charAt(i + 1))) {
                int j = i + 1;
                while (j < template.length() && Character.isDigit(template.charAt(j))) {
                    j++;
                }
                count = Integer.parseInt(template.substring(i + 1, j));
                i = j - 1;
            }

            for (int j = 0; j < count; j++) {
                if (buffer.remaining() < getFormatSize(format)) {
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

    private static int readShortBigEndian(ByteBuffer buffer) {
        return (buffer.get() & 0xFF) << 8 | buffer.get() & 0xFF;
    }

    private static int readShortLittleEndian(ByteBuffer buffer) {
        return buffer.get() & 0xFF | (buffer.get() & 0xFF) << 8;
    }

    private static int readIntBigEndian(ByteBuffer buffer) {
        return (buffer.get() & 0xFF) << 24 | (buffer.get() & 0xFF) << 16 | (buffer.get() & 0xFF) << 8 | buffer.get() & 0xFF;
    }

    private static int readIntLittleEndian(ByteBuffer buffer) {
        return buffer.get() & 0xFF | (buffer.get() & 0xFF) << 8 | (buffer.get() & 0xFF) << 16 | (buffer.get() & 0xFF) << 24;
    }

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

