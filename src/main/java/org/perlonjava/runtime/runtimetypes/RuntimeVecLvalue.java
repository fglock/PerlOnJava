package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.runtime.operators.Vec;

/**
 * Represents a vector (bit string) that can be used as an lvalue (left-hand value).
 * This class allows for modification of specific bits within a string, similar to Perl's vec function.
 */
public class RuntimeVecLvalue extends RuntimeBaseProxy {
    /**
     * The offset in the bit string.
     */
    private final int offset;

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
    public RuntimeVecLvalue(RuntimeScalar parent, int offset, int bits, int value) {
        this.lvalue = parent;
        this.offset = offset;
        this.bits = bits;

        this.type = RuntimeScalarType.INTEGER;
        this.value = value;
    }

    /**
     * Constructs a new RuntimeVecLvalue with a long value (for 64-bit vec).
     */
    public RuntimeVecLvalue(RuntimeScalar parent, int offset, int bits, long value) {
        this.lvalue = parent;
        this.offset = offset;
        this.bits = bits;

        // Store long values properly - use DOUBLE for values that don't fit in int
        if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
            this.type = RuntimeScalarType.INTEGER;
            this.value = (int) value;
        } else {
            this.type = RuntimeScalarType.DOUBLE;
            this.value = (double) value;
        }
    }

    /**
     * Vivification method (currently empty as vec doesn't require vivification).
     */
    @Override
    void vivify() {
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
}
