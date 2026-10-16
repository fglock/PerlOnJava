package org.perlonjava.runtime.runtimetypes;

import java.math.BigInteger;

/** Compact storage for a Perl unsigned 64-bit integer represented by a Java long. */
public final class UnsignedWordValue extends Number {
    private final long bits;
    private volatile BigInteger bigInteger;

    public UnsignedWordValue(long bits) {
        this.bits = bits;
    }

    public long bits() {
        return bits;
    }

    public BigInteger toBigInteger() {
        BigInteger result = bigInteger;
        if (result == null) {
            result = BigInteger.valueOf(bits & Long.MAX_VALUE);
            if (bits < 0) result = result.setBit(63);
            bigInteger = result;
        }
        return result;
    }

    @Override public int intValue() { return (int) bits; }
    @Override public long longValue() { return bits; }
    @Override public float floatValue() { return (float) doubleValue(); }
    @Override public double doubleValue() {
        return (double) (bits & Long.MAX_VALUE) + (bits < 0 ? 0x1.0p63 : 0.0);
    }
    @Override public String toString() { return Long.toUnsignedString(bits); }
}
