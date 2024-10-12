package org.perlonjava.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class Vec {
    public static RuntimeScalar vec(RuntimeList args) throws IllegalArgumentException {
        String str = args.elements.get(0).toString();
        int offset = ((RuntimeScalar) args.elements.get(1)).getInt();
        int bits = ((RuntimeScalar) args.elements.get(2)).getInt();

        byte[] data = new byte[str.length()];
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c > 0xFF) {
                throw new IllegalArgumentException("Use of strings with code points over 0xFF as arguments to vec is forbidden");
            }
            data[i] = (byte) c;
        }

        if (bits <= 0 || bits > 32) {
            throw new IllegalArgumentException("BITS must be between 1 and 32");
        }

        int byteOffset = offset * bits / 8;
        int bitOffset = (offset * bits) % 8;

        if (byteOffset >= data.length) {
            return new RuntimeScalar(0);
        }

        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN); // Change to BIG_ENDIAN
        int value = 0;

        if (bits == 32 && byteOffset + 3 < data.length) {
            value = buffer.getInt(byteOffset);
        } else if (bits == 16 && byteOffset + 1 < data.length) {
            value = buffer.getShort(byteOffset) & 0xFFFF;
        } else if (bits == 8) {
            value = buffer.get(byteOffset) & 0xFF;
        } else {
            for (int i = 0; i < bits; i++) {
                int byteIndex = byteOffset + (bitOffset + i) / 8;
                int bitIndex = (bitOffset + i) % 8;
                if (byteIndex < data.length) {
                    value |= ((data[byteIndex] >> bitIndex) & 1) << i; // Adjust bit extraction
                }
            }
        }

        return new RuntimeScalar(value);
    }

    public static RuntimeScalar set(RuntimeList args, RuntimeScalar value) throws IllegalArgumentException {
        String str = args.elements.get(0).toString();
        int offset = ((RuntimeScalar) args.elements.get(1)).getInt();
        int bits = ((RuntimeScalar) args.elements.get(2)).getInt();

        byte[] data = new byte[str.length()];
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c > 0xFF) {
                throw new IllegalArgumentException("Use of strings with code points over 0xFF as arguments to vec is forbidden");
            }
            data[i] = (byte) c;
        }

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

        int val = value.getInt();
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN); // Change to BIG_ENDIAN

        if (bits == 32 && byteOffset + 3 < data.length) {
            buffer.putInt(byteOffset, val);
        } else if (bits == 16 && byteOffset + 1 < data.length) {
            buffer.putShort(byteOffset, (short) val);
        } else if (bits == 8 && byteOffset < data.length) {
            buffer.put(byteOffset, (byte) val);
        } else {
            for (int i = 0; i < bits; i++) {
                int byteIndex = byteOffset + (bitOffset + i) / 8;
                int bitIndex = (bitOffset + i) % 8;
                if (byteIndex < data.length) {
                    if ((val & (1 << i)) != 0) {
                        data[byteIndex] |= (1 << bitIndex);
                    } else {
                        data[byteIndex] &= ~(1 << bitIndex);
                    }
                }
            }
        }

        ((RuntimeScalar) args.elements.get(0)).set(new String(data));
        return value;
    }
}