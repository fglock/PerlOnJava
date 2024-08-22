package org.perlonjava.runtime;

import java.util.Iterator;

/**
 * Represents a runtime typeglob in Perl. Typeglobs are special symbols that can represent
 * all types of Perl variables (scalars, arrays, hashes, subroutines, filehandles) with the same name.
 */
public class RuntimeGlob extends RuntimeBaseEntity implements RuntimeScalarReference {

    public String globName;

    /**
     * Constructor for RuntimeGlob.
     * Initializes a new instance of the RuntimeGlob class with the specified glob name.
     *
     * @param globName The name of the typeglob.
     */
    public RuntimeGlob(String globName) {
        this.globName = globName;
    }

    // Setters
    public RuntimeScalar set(RuntimeScalar value) {
        switch (value.type) {
            case CODE:
                Namespace.getGlobalCodeRef(this.globName).set(value);
                return value;
        }
        // XXX TODO
        throw new IllegalStateException("typeglob assignment not implemented");
        // return value;
    }

    /**
     * Returns a string representation of the typeglob reference.
     * The format is "GLOB(hashCode)" where hashCode is the unique identifier for this instance.
     *
     * @return A string representation of the typeglob reference.
     */
    public String toStringRef() {
        return "GLOB(" + this.hashCode() + ")";
    }

    /**
     * Returns an integer representation of the typeglob reference.
     * This is the hash code of the current instance.
     *
     * @return The hash code of this instance.
     */
    public int getIntRef() {
        return this.hashCode();
    }

    /**
     * Returns a double representation of the typeglob reference.
     * This is the hash code of the current instance, cast to a double.
     *
     * @return The hash code of this instance as a double.
     */
    public double getDoubleRef() {
        return this.hashCode();
    }

    /**
     * Returns a boolean representation of the typeglob reference.
     * This always returns true, indicating the presence of the typeglob.
     *
     * @return Always true.
     */
    public boolean getBooleanRef() {
        return true;
    }

    // Get the scalar value of the Scalar
    public RuntimeScalar getScalar() {
        RuntimeScalar ret = new RuntimeScalar();
        ret.type = RuntimeScalarType.GLOB;
        ret.value = this;
        return ret;
    }

    // Get the array value of the typeglob
    public RuntimeArray getArray() {
        return new RuntimeArray(this.getScalar());
    }

    // Get the list value of the Scalar
    public RuntimeList getList() {
        return new RuntimeList(this.getScalar());
    }

    // Add itself to a RuntimeArray.
    public void addToArray(RuntimeArray array) {
        array.push(this.getScalar());
    }

    public RuntimeList set(RuntimeList value) {
        return new RuntimeList(this.set(value.getScalar()));
    }

    // keys() operator
    public RuntimeArray keys() {
        throw new IllegalStateException("Type of arg 1 to values must be hash or array");
    }

    // values() operator
    public RuntimeArray values() {
        throw new IllegalStateException("Type of arg 1 to values must be hash or array");
    }

    // Method to return an iterator
    public Iterator<RuntimeScalar> iterator() {
        return this.getArray().iterator();
    }
}

