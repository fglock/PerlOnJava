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
                GlobalContext.getGlobalCodeRef(this.globName).set(value);

                // Invalidate the method resolution cache
                InheritanceResolver.invalidateCache();

                return value;
            case GLOB:
                if (value.value instanceof RuntimeIO) {
                    // *STDOUT = $new_handle
                    GlobalContext.getGlobalIO(this.globName).set(value);
                }
                return value;
        }
        // XXX TODO
        throw new IllegalStateException("typeglob assignment not implemented");
        // return value;
    }

    public int countElements() {
        return 1;
    }

    public String toString() {
        return "*" + this.globName;
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
    public RuntimeScalar scalar() {
        RuntimeScalar ret = new RuntimeScalar();
        ret.type = RuntimeScalarType.GLOB;
        ret.value = this;
        return ret;
    }

    // Create a reference
    public RuntimeScalar createReference() {
        RuntimeScalar ret = new RuntimeScalar();
        ret.type = RuntimeScalarType.GLOBREFERENCE;
        ret.value = this;
        return ret;
    }

    // Get the list value of the Scalar
    public RuntimeList getList() {
        return new RuntimeList(this.scalar());
    }

    // Add itself to a RuntimeArray.
    public void addToArray(RuntimeArray array) {
        array.push(this.scalar());
    }

    /**
     * Add itself to a RuntimeScalar.
     *
     * @param scalar The RuntimeScalar object
     */
    public RuntimeScalar addToScalar(RuntimeScalar scalar) {
        return scalar.set(this);
    }

    public RuntimeArray set(RuntimeList value) {
        return new RuntimeArray(this.set(value.scalar()));
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
        return this.scalar().iterator();
    }

    // Get the Glob alias into an Array
    public RuntimeArray setArrayOfAlias(RuntimeArray arr) {
        arr.elements.add(this.scalar());
        return arr;
    }

    public RuntimeGlob undefine() {
        // undefine CODE        
        GlobalContext.getGlobalCodeRef(this.globName).set(new RuntimeScalar());

        // Invalidate the method resolution cache
        InheritanceResolver.invalidateCache();

        // XXX TODO undefine scalar, array, hash
        return this;
    }

}

