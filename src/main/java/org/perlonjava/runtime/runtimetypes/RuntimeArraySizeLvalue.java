package org.perlonjava.runtime.runtimetypes;

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
        this.type = RuntimeScalarType.INTEGER;
        this.value = parent.lastElementIndex();
        return this;
    }

    @Override
    public RuntimeScalar preAutoIncrement() {
        RuntimeArray parent = lvalue.arrayDeref();
        int newIndex = parent.lastElementIndex() + 1;
        parent.setLastElementIndex(new RuntimeScalar(newIndex));
        this.type = RuntimeScalarType.INTEGER;
        this.value = newIndex;
        return this;
    }

    @Override
    public RuntimeScalar postAutoIncrement() {
        RuntimeArray parent = lvalue.arrayDeref();
        int oldIndex = parent.lastElementIndex();
        parent.setLastElementIndex(new RuntimeScalar(oldIndex + 1));
        this.type = RuntimeScalarType.INTEGER;
        this.value = oldIndex + 1;
        return new RuntimeScalar(oldIndex);
    }

    @Override
    public RuntimeScalar preAutoDecrement() {
        RuntimeArray parent = lvalue.arrayDeref();
        int newIndex = parent.lastElementIndex() - 1;
        parent.setLastElementIndex(new RuntimeScalar(newIndex));
        this.type = RuntimeScalarType.INTEGER;
        this.value = newIndex;
        return this;
    }

    @Override
    public RuntimeScalar postAutoDecrement() {
        RuntimeArray parent = lvalue.arrayDeref();
        int oldIndex = parent.lastElementIndex();
        parent.setLastElementIndex(new RuntimeScalar(oldIndex - 1));
        this.type = RuntimeScalarType.INTEGER;
        this.value = oldIndex - 1;
        return new RuntimeScalar(oldIndex);
    }
}
