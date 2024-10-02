package org.perlonjava.runtime;

public class Vec {

    public RuntimeScalar get(RuntimeScalar str, int offset, int bits) {
        StringBuilder data = new StringBuilder(str.toString());

        if (bits <= 0 || bits > 32) {
            throw new IllegalArgumentException("BITS must be between 1 and 32");
        }

        int charOffset = offset * bits / 16;
        int bitOffset = (offset * bits) % 16;

        if (charOffset >= data.length()) {
            return new RuntimeScalar(0);
        }

        int value = 0;
        int charsToRead = (bits + bitOffset + 15) / 16;
        for (int i = 0; i < charsToRead && charOffset + i < data.length(); i++) {
            value |= data.charAt(charOffset + i) << (i * 16);
        }

        value >>= bitOffset;
        return new RuntimeScalar(value & ((1 << bits) - 1));
    }

    public RuntimeScalar set(RuntimeScalar str, int offset, int bits, RuntimeScalar value) {
        StringBuilder data = new StringBuilder(str.toString());

        if (bits <= 0 || bits > 32) {
            throw new IllegalArgumentException("BITS must be between 1 and 32");
        }

        int charOffset = offset * bits / 16;
        int bitOffset = (offset * bits) % 16;

        int charsToWrite = (bits + bitOffset + 15) / 16;
        if (charOffset + charsToWrite > data.length()) {
            data.setLength(charOffset + charsToWrite);
        }

        int mask = (1 << bits) - 1;
        int val = value.getInt() & mask;

        for (int i = 0; i < charsToWrite; i++) {
            int charValue = data.charAt(charOffset + i);
            charValue &= ~((mask << bitOffset) >>> (i * 16));
            charValue |= (val << bitOffset) >>> (i * 16);
            data.setCharAt(charOffset + i, (char) charValue);
        }
        str.set(data.toString());
        return value;
    }
}