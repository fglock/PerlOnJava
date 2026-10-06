package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.runtime.operators.Vec;

import java.math.BigInteger;

/**
 * Represents a vector (bit string) that can be used as an lvalue (left-hand value).
 * This class allows for modification of specific bits within a string, similar to Perl's vec function.
 */
public class RuntimeVecLvalue extends RuntimeBaseProxy {
    /**
     * The offset in the bit string.
     */
    private final BigInteger offset;

    /**
     * The number of bits to operate on.
     */
    private final int bits;

    /**
     * Constructs a new RuntimeVecLvalue.
     *
     * @param parent The parent RuntimeScalar containing the original string.
     * @param offset The offset in the bit string.
     * @param bits   The number of bits to operate on.
     * @param value  The initial value of the vector.
     */
    public RuntimeVecLvalue(RuntimeScalar parent, BigInteger offset, int bits, RuntimeScalar value) {
        this.lvalue = parent;
        this.offset = offset;
        this.bits = bits;
        this.type = value.type;
        this.value = value.value;
    }

    /**
     * Vivification method (currently empty as vec doesn't require vivification).
     */
    @Override
    void vivify() {
    }

    /**
     * A vec lvalue's visible scalar value is the extracted numeric field,
     * not the parent string.  RuntimeBaseProxy's default implementation
     * synchronizes the proxy with {@code lvalue}, which would replace that
     * field with the parent string before a compound assignment is applied.
     */
    @Override
    public void vivifyLvalue() {
    }

    /**
     * Sets the value of this vector and updates the parent string accordingly.
     *
     * @param value The new value to set for this vector.
     * @return This RuntimeVecLvalue instance.
     * @throws RuntimeException if the operation is invalid.
     */
    @Override
    public RuntimeScalar set(RuntimeScalar value) {
        // Update the local type and value
        this.type = value.type;
        this.value = value.value;

        try {
            // Create arguments for Vec.set method
            RuntimeList args = new RuntimeList(
                    lvalue, new RuntimeScalar(offset), new RuntimeScalar(bits));
            // Use Vec.set to update the parent string
            if (bits >= 32) {
                // Use getLong() for 32-bit and 64-bit to preserve bit patterns for
                // unsigned values > Integer.MAX_VALUE (getInt() clamps via double→int)
                long newValue = value.getLong();
                Vec.set(args, new RuntimeScalar(newValue));
            } else {
                int newValue = value.getInt();
                Vec.set(args, new RuntimeScalar(newValue));
            }
        } catch (PerlCompilerException e) {
            throw new RuntimeException(e.getMessage());
        }

        return this;
    }

    private RuntimeScalar autoModify(int delta, boolean postfix, boolean integer) {
        RuntimeList args = new RuntimeList(
                lvalue, new RuntimeScalar(offset), new RuntimeScalar(bits));
        RuntimeScalar current = Vec.vec(args);
        RuntimeScalar previous = new RuntimeScalar(current);
        RuntimeScalar updated = new RuntimeScalar(current);
        if (integer) {
            if (delta > 0) {
                updated = updated.integerPreAutoIncrement();
            } else {
                updated = updated.integerPreAutoDecrement();
            }
        } else if (delta > 0) {
            updated = updated.preAutoIncrement();
        } else {
            updated = updated.preAutoDecrement();
        }
        Vec.set(args, updated);
        this.type = updated.type;
        this.value = updated.value;
        return postfix ? previous : this;
    }

    @Override
    public RuntimeScalar preAutoIncrement() { return autoModify(1, false, false); }

    @Override
    public RuntimeScalar postAutoIncrement() { return autoModify(1, true, false); }

    @Override
    public RuntimeScalar preAutoDecrement() { return autoModify(-1, false, false); }

    @Override
    public RuntimeScalar postAutoDecrement() { return autoModify(-1, true, false); }

    @Override
    public RuntimeScalar integerPreAutoIncrement() { return autoModify(1, false, true); }

    @Override
    public RuntimeScalar integerPostAutoIncrement() { return autoModify(1, true, true); }

    @Override
    public RuntimeScalar integerPreAutoDecrement() { return autoModify(-1, false, true); }

    @Override
    public RuntimeScalar integerPostAutoDecrement() { return autoModify(-1, true, true); }
}
