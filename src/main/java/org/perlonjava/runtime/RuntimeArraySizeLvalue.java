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
}
