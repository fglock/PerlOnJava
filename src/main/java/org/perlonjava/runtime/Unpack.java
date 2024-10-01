package org.perlonjava.runtime;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class Unpack {

    public static RuntimeList unpack(RuntimeScalar packedData, RuntimeScalar templateScalar) {
        String template = templateScalar.toString();
        byte[] data = packedData.toString().getBytes(StandardCharsets.ISO_8859_1);
        ByteBuffer buffer = ByteBuffer.wrap(data);
        RuntimeList out = new RuntimeList();
        List<RuntimeBaseEntity> values = out.elements;

        for (int i = 0; i < template.length(); i++) {
            char format = template.charAt(i);
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
                switch (format) {
                    case 'C':
                        values.add(new RuntimeScalar(buffer.get() & 0xFF));
                        break;
                    case 'S':
                        values.add(new RuntimeScalar(readShort(buffer)));
                        break;
                    case 'L':
                        values.add(new RuntimeScalar(readLong(buffer)));
                        break;
                    case 'N':
                        values.add(new RuntimeScalar(readIntBigEndian(buffer)));
                        break;
                    case 'V':
                        values.add(new RuntimeScalar(readIntLittleEndian(buffer)));
                        break;
                    case 'n':
                        values.add(new RuntimeScalar(readShortBigEndian(buffer)));
                        break;
                    case 'v':
                        values.add(new RuntimeScalar(readShortLittleEndian(buffer)));
                        break;
                    case 'f':
                        values.add(new RuntimeScalar(readFloat(buffer)));
                        break;
                    case 'd':
                        values.add(new RuntimeScalar(readDouble(buffer)));
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
                        break;
                    default:
                        throw new RuntimeException("unpack: unsupported format character: " + format);
                }
            }
        }

        return out;
    }

    private static int readShort(ByteBuffer buffer) {
        return buffer.get() & 0xFF | (buffer.get() & 0xFF) << 8;
    }

    private static int readShortBigEndian(ByteBuffer buffer) {
        return (buffer.get() & 0xFF) << 8 | buffer.get() & 0xFF;
    }

    private static int readShortLittleEndian(ByteBuffer buffer) {
        return buffer.get() & 0xFF | (buffer.get() & 0xFF) << 8;
    }

    private static long readLong(ByteBuffer buffer) {
        return buffer.getInt() & 0xFFFFFFFFL;
    }

    private static int readIntBigEndian(ByteBuffer buffer) {
        return (buffer.get() & 0xFF) << 24 | (buffer.get() & 0xFF) << 16 | (buffer.get() & 0xFF) << 8 | buffer.get() & 0xFF;
    }

    private static int readIntLittleEndian(ByteBuffer buffer) {
        return buffer.get() & 0xFF | (buffer.get() & 0xFF) << 8 | (buffer.get() & 0xFF) << 16 | (buffer.get() & 0xFF) << 24;
    }

    private static float readFloat(ByteBuffer buffer) {
        return buffer.getFloat();
    }

    private static double readDouble(ByteBuffer buffer) {
        return buffer.getDouble();
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
        }

        return new String(bytes, StandardCharsets.UTF_8).trim();
    }

    private static String readBitString(ByteBuffer buffer, int count, char format) {
        StringBuilder bitString = new StringBuilder();
        int bitsRead = 0;
        while (bitsRead < count) {
            int byteValue = buffer.get() & 0xFF;
            for (int i = 0; i < 8 && bitsRead < count; i++) {
                if (format == 'b') {
                    bitString.append((byteValue & (1 << i)) != 0 ? '1' : '0');
                } else {
                    bitString.append((byteValue & (1 << (7 - i))) != 0 ? '1' : '0');
                }
                bitsRead++;
            }
        }
        return bitString.toString();
    }
}

