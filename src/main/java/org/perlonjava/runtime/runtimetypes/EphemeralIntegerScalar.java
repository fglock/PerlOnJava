package org.perlonjava.runtime.runtimetypes;

import java.math.BigInteger;

/**
 * Mutable integer cell used only by compiler-proven ephemeral numeric foreach
 * topics. Its payload stays in a primitive long between iterator advances.
 */
final class EphemeralIntegerScalar extends RuntimeScalar {
    private long integerValue;

    EphemeralIntegerScalar() {
        super(0);
    }

    RuntimeScalar setEphemeralInteger(long value) {
        integerValue = value;
        return this;
    }

    @Override
    public int getInt() {
        return (int) integerValue;
    }

    @Override
    public long getLong() {
        return integerValue;
    }

    @Override
    public double getDouble() {
        return integerValue;
    }

    @Override
    public BigInteger getBigint() {
        return BigInteger.valueOf(integerValue);
    }

    @Override
    public String toString() {
        return Long.toString(integerValue);
    }
}
