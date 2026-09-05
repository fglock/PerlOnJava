package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.runtime.operators.WarnDie;

/**
 * Represents the value of $#{array}.
 */
public class RuntimeArraySizeLvalue extends RuntimeBaseProxy {

    private boolean isOrphaned() {
        return lvalue != null
                && lvalue.value instanceof RuntimeArray parent
                && parent.arrayLengthLvalueOrphaned;
    }

    private RuntimeScalar orphanedValue() {
        return RuntimeScalarCache.scalarUndef;
    }

    private RuntimeScalar warnFreedArray() {
        WarnDie.warn(new RuntimeScalar("Attempt to set length of freed array"),
                RuntimeScalarCache.scalarEmptyString);
        return this;
    }

    /**
     * Constructs a new RuntimeArraySizeLvalue.
     *
     * @param parent The parent RuntimeArray.
     */
    public RuntimeArraySizeLvalue(RuntimeArray parent) {
        this.lvalue = parent.createReference();
        this.type = RuntimeScalarType.INTEGER;
        this.value = parent.lastElementIndex();
        parent.registerArraySizeLvalue(this);
    }

    void orphan() {
        type = RuntimeScalarType.UNDEF;
        value = null;
    }

    /**
     * Vivification method (currently empty as it doesn't require vivification).
     */
    @Override
    void vivify() {
    }

    /**
     * The array-size proxy is already a complete lvalue.  RuntimeBaseProxy's
     * generic implementation copies the backing reference's ARRAYREFERENCE
     * type and value into the proxy, destroying the integer $# value before a
     * compound assignment can use it.
     */
    @Override
    public void vivifyLvalue() {
        // Nothing to vivify: set() writes through to the referenced array.
    }

    /**
     * Sets the value of this scalar and updates the parent array accordingly.
     *
     * @param value The new size for the array.
     * @return This instance.
     */
    @Override
    public RuntimeScalar set(RuntimeScalar value) {
        if (isOrphaned()) return warnFreedArray();
        RuntimeArray parent = lvalue.arrayDeref();
        parent.setLastElementIndex(value);
        this.type = RuntimeScalarType.INTEGER;
        this.value = parent.lastElementIndex();
        return this;
    }

    @Override
    public RuntimeScalar preAutoIncrement() {
        if (isOrphaned()) return warnFreedArray();
        RuntimeArray parent = lvalue.arrayDeref();
        int newIndex = parent.lastElementIndex() + 1;
        parent.setLastElementIndex(new RuntimeScalar(newIndex));
        this.type = RuntimeScalarType.INTEGER;
        this.value = newIndex;
        return this;
    }

    @Override
    public RuntimeScalar postAutoIncrement() {
        if (isOrphaned()) return warnFreedArray();
        RuntimeArray parent = lvalue.arrayDeref();
        int oldIndex = parent.lastElementIndex();
        parent.setLastElementIndex(new RuntimeScalar(oldIndex + 1));
        this.type = RuntimeScalarType.INTEGER;
        this.value = oldIndex + 1;
        return new RuntimeScalar(oldIndex);
    }

    @Override
    public RuntimeScalar preAutoDecrement() {
        if (isOrphaned()) return warnFreedArray();
        RuntimeArray parent = lvalue.arrayDeref();
        int newIndex = parent.lastElementIndex() - 1;
        parent.setLastElementIndex(new RuntimeScalar(newIndex));
        this.type = RuntimeScalarType.INTEGER;
        this.value = newIndex;
        return this;
    }

    @Override
    public RuntimeScalar postAutoDecrement() {
        if (isOrphaned()) return warnFreedArray();
        RuntimeArray parent = lvalue.arrayDeref();
        int oldIndex = parent.lastElementIndex();
        parent.setLastElementIndex(new RuntimeScalar(oldIndex - 1));
        this.type = RuntimeScalarType.INTEGER;
        this.value = oldIndex - 1;
        return new RuntimeScalar(oldIndex);
    }

    @Override
    public boolean getDefinedBoolean() {
        return !isOrphaned() && super.getDefinedBoolean();
    }

    @Override
    public boolean getBoolean() {
        return isOrphaned() ? false : super.getBoolean();
    }

    @Override
    public int getInt() {
        return isOrphaned() ? 0 : super.getInt();
    }

    @Override
    public long getLong() {
        return isOrphaned() ? 0 : super.getLong();
    }

    @Override
    public double getDouble() {
        return isOrphaned() ? 0 : super.getDouble();
    }

    @Override
    public String toString() {
        return isOrphaned() ? orphanedValue().toString() : super.toString();
    }
}
