package org.perlonjava.runtime;

public class Vec {
    private final StringBuilder data;

    public Vec(RuntimeScalar str) {
        this.data = new StringBuilder(str.toString());
    }

    public static void main(String[] args) {
        Vec vec = new Vec(new RuntimeScalar(""));
        vec.set(0, 32, new RuntimeScalar(0x5065726C)); // 'Perl'
        System.out.println(vec.toRuntimeScalar().toString()); // Prints "Perl"

        vec.set(2, 16, new RuntimeScalar(0x5065)); // 'PerlPe'
        vec.set(3, 16, new RuntimeScalar(0x726C)); // 'PerlPerl'
        vec.set(8, 8, new RuntimeScalar(0x50));    // 'PerlPerlP'
        vec.set(9, 8, new RuntimeScalar(0x65));    // 'PerlPerlPe'
        vec.set(20, 4, new RuntimeScalar(2));      // 'PerlPerlPe'   . "\x02"
        vec.set(21, 4, new RuntimeScalar(7));      // 'PerlPerlPer'
        vec.set(45, 2, new RuntimeScalar(3));      // 'PerlPerlPer'  . "\x0c"
        vec.set(93, 1, new RuntimeScalar(1));      // 'PerlPerlPer'  . "\x2c"
        vec.set(94, 1, new RuntimeScalar(1));      // 'PerlPerlPerl'

        System.out.println(vec.toRuntimeScalar().toString()); // Prints "PerlPerlPerl"

        // Test lvalue-like operations
        VecLvalue lvalue = vec.lvalue(0, 8);
        System.out.println(lvalue.value().toString()); // Prints "P"
        lvalue.assign(new RuntimeScalar(0x51)); // 'Q'
        System.out.println(vec.toRuntimeScalar().toString()); // Prints "QerlPerlPerl"
    }

    public RuntimeScalar get(int offset, int bits) {
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

    public void set(int offset, int bits, RuntimeScalar value) {
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
    }

    public RuntimeScalar toRuntimeScalar() {
        return new RuntimeScalar(data.toString());
    }

    public VecLvalue lvalue(int offset, int bits) {
        return new VecLvalue(this, offset, bits);
    }

    public static class VecLvalue {
        private final Vec vec;
        private final int offset;
        private final int bits;

        private VecLvalue(Vec vec, int offset, int bits) {
            this.vec = vec;
            this.offset = offset;
            this.bits = bits;
        }

        public void assign(RuntimeScalar value) {
            vec.set(offset, bits, value);
        }

        public RuntimeScalar value() {
            return vec.get(offset, bits);
        }
    }
}