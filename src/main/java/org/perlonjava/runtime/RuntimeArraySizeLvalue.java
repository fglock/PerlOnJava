package org.perlonjava.runtime;

/**
 * Represents the value of $#{array}.
 */
public class RuntimeArraySizeLvalue extends RuntimeBaseProxy {

    /**
     * Constructs a new RuntimeArraySizeLvalue.
     *
     * @param parent The parent RuntimeArray.
     */
    public RuntimeArraySizeLvalue(RuntimeArray parent) {
        this.lvalue = parent.createReference();
        this.type = RuntimeScalarType.INTEGER;
        this.value = parent.lastElementIndex();
    }

    /**
     * Vivification method (currently empty as it doesn't require vivification).
     */
    @Override
    void vivify() {
    }

    /**
     * Sets the value of this scalar and updates the parent array accordingly.
     *
     * @param value The new size for the array.
     * @return This instance.
     */
    @Override
    public RuntimeScalar set(RuntimeScalar value) {
        RuntimeArray parent = lvalue.arrayDeref();
        parent.setLastElementIndex(value);
        return this;
    }

    /**
     * Performs pre-decrement on the array size ($#array--).
     *
     * @return The updated array size after decrement.
     */
    @Override
    public RuntimeScalar preAutoDecrement() {
        RuntimeArray parent = lvalue.arrayDeref();
        RuntimeScalar currentSize = new RuntimeScalar(parent.lastElementIndex());
        RuntimeScalar newSize = new RuntimeScalar(currentSize.getInt() - 1);
        parent.setLastElementIndex(newSize);
        this.value = newSize.value;
        this.type = newSize.type;
        return newSize;
    }

    /**
     * Performs post-decrement on the array size ($#array--).
     *
     * @return The original array size before decrement.
     */
    @Override
    public RuntimeScalar postAutoDecrement() {
        RuntimeArray parent = lvalue.arrayDeref();
        RuntimeScalar originalSize = new RuntimeScalar(parent.lastElementIndex());
        RuntimeScalar newSize = new RuntimeScalar(originalSize.getInt() - 1);
        parent.setLastElementIndex(newSize);
        this.value = newSize.value;
        this.type = newSize.type;
        return originalSize;
    }
}
