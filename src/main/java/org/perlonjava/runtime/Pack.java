package org.perlonjava.runtime;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

public class Pack {

public static RuntimeScalar pack(RuntimeList args) {
    if (args.elements.isEmpty()) {
        throw new RuntimeException("pack: not enough arguments");
    }

    RuntimeScalar templateScalar = (RuntimeScalar) args.elements.get(0);
    String template = templateScalar.toString();
    List<RuntimeBaseEntity> values = args.elements.subList(1, args.elements.size());

    ByteArrayOutputStream output = new ByteArrayOutputStream();
    int valueIndex = 0;

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
            if (valueIndex >= values.size()) {
                throw new RuntimeException("pack: not enough arguments");
            }

            RuntimeScalar value = (RuntimeScalar) values.get(valueIndex++);

            switch (format) {
                case 'C':
                    output.write(value.getInt() & 0xFF);
                    break;
                case 'S':
                    writeShort(output, value.getInt());
                    break;
                case 'L':
                    writeInt(output, value.getInt());
                    break;
                case 'N':
                    writeIntBigEndian(output, value.getInt());
                    break;
                case 'V':
                    writeIntLittleEndian(output, value.getInt());
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

    return new RuntimeScalar(Arrays.toString(output.toByteArray()));
}

private static void writeShort(ByteArrayOutputStream output, int value) {
    output.write(value & 0xFF);
    output.write((value >> 8) & 0xFF);
}

private static void writeInt(ByteArrayOutputStream output, int value) {
    output.write(value & 0xFF);
    output.write((value >> 8) & 0xFF);
    output.write((value >> 16) & 0xFF);
    output.write((value >> 24) & 0xFF);
}

private static void writeIntBigEndian(ByteArrayOutputStream output, int value) {
    output.write((value >> 24) & 0xFF);
    output.write((value >> 16) & 0xFF);
    output.write((value >> 8) & 0xFF);
    output.write(value & 0xFF);
}

private static void writeIntLittleEndian(ByteArrayOutputStream output, int value) {
    output.write(value & 0xFF);
    output.write((value >> 8) & 0xFF);
    output.write((value >> 16) & 0xFF);
    output.write((value >> 24) & 0xFF);
}

private static void writeString(ByteArrayOutputStream output, String str, int count, char format) {
    byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
    int length = Math.min(bytes.length, count);
    output.write(bytes, 0, length);

    // Pad with nulls or spaces
    byte padByte = (format == 'A') ? (byte) ' ' : (byte) 0;
    for (int i = length; i < count; i++) {
        output.write(padByte);
    }

    // Add null terminator for 'Z' format
    if (format == 'Z' && length < count) {
        output.write(0);
    }
}

}

