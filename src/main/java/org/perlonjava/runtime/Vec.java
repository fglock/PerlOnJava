package org.perlonjava.runtime;

import java.nio.charset.StandardCharsets;

public class Vec {

    public static RuntimeScalar vec(RuntimeList args) {
        String str = args.elements.get(0).toString();
        int offset = ((RuntimeScalar) args.elements.get(1)).getInt();
        int bits = ((RuntimeScalar) args.elements.get(2)).getInt();

        byte[] data = str.getBytes(StandardCharsets.UTF_8);

        if (bits <= 0 || bits > 32) {
            throw new IllegalArgumentException("BITS must be between 1 and 32");
        }

        int byteOffset = offset * bits / 8;
        int bitOffset = (offset * bits) % 8;

        if (byteOffset >= data.length) {
            return new RuntimeScalar(0);
        }

        long value = 0;
        int bytesToRead = (bits + bitOffset + 7) / 8;
        for (int i = 0; i < bytesToRead && byteOffset + i < data.length; i++) {
            value |= ((long) (data[byteOffset + i] & 0xFF)) << (i * 8);
        }

        value >>= bitOffset;
        return new RuntimeScalar((int) (value & ((1L << bits) - 1)));
    }

    public static RuntimeScalar set(RuntimeList args, RuntimeScalar value) {
        String str = args.elements.get(0).toString();
        int offset = ((RuntimeScalar) args.elements.get(1)).getInt();
        int bits = ((RuntimeScalar) args.elements.get(2)).getInt();

        byte[] data = str.getBytes(StandardCharsets.UTF_8);

        if (bits <= 0 || bits > 32) {
            throw new IllegalArgumentException("BITS must be between 1 and 32");
        }

        int byteOffset = offset * bits / 8;
        int bitOffset = (offset * bits) % 8;

        int bytesToWrite = (bits + bitOffset + 7) / 8;
        if (byteOffset + bytesToWrite > data.length) {
            byte[] newData = new byte[byteOffset + bytesToWrite];
            System.arraycopy(data, 0, newData, 0, data.length);
            data = newData;
        }

        long mask = (1L << bits) - 1;
        long val = value.getInt() & mask;

        for (int i = 0; i < bytesToWrite; i++) {
            int byteValue = data[byteOffset + i] & 0xFF;
            byteValue &= ~((int)((mask << bitOffset) >>> (i * 8)));
            byteValue |= (int)((val << bitOffset) >>> (i * 8));
            data[byteOffset + i] = (byte) byteValue;
        }

        ((RuntimeScalar) args.elements.get(0)).set(new String(data, StandardCharsets.UTF_8));
        return value;
    }
}