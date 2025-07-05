package org.perlonjava.runtime;

import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;

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
        this.value = parent.elements.size() - 1;
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
        int newSize = value.getInt();
        if (newSize < -1) newSize = -1;
        int currentSize = parent.elements.size() - 1;

        // Update the parent with the new size
        if (newSize > currentSize) {
            parent.set(newSize, scalarUndef);
        } else {
            while (newSize < currentSize) {
                currentSize--;
                parent.elements.removeLast();
            }
        }

        // Update the local value
        this.value = newSize;
        return this;
    }
}
