package org.perlonjava.runtime;

import java.util.Iterator;

/**
 * Represents a runtime typeglob in Perl. Typeglobs are special symbols that can represent
 * all types of Perl variables (scalars, arrays, hashes, subroutines, filehandles) with the same name.
 * This class provides methods to manipulate and interact with typeglobs in the runtime environment.
 */
public class RuntimeGlob extends RuntimeBaseEntity implements RuntimeScalarReference {

    // The name of the typeglob
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

    /**
     * Sets the value of the typeglob based on the type of the provided RuntimeScalar.
     * Supports setting CODE and GLOB types, with special handling for IO objects.
     *
     * @param value The RuntimeScalar value to set.
     * @return The set RuntimeScalar value.
     * @throws IllegalStateException if the typeglob assignment is not implemented for the given type.
     */
    public RuntimeScalar set(RuntimeScalar value) {
        // System.out.println("glob set " + value.type);
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

    /**
     * Counts the number of elements in the typeglob.
     *
     * @return The number of elements, which is always 1 for a typeglob.
     */
    public int countElements() {
        return 1;
    }

    /**
     * Returns a string representation of the typeglob.
     *
     * @return A string in the format "*globName".
     */
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

    /**
     * Returns a boolean indicating whether the typeglob is defined.
     *
     * @return Always true, as typeglobs are always considered defined.
     */
    public boolean getDefinedBoolean() {
        return true;
    }

    /**
     * Gets the scalar value of the typeglob.
     *
     * @return A RuntimeScalar representing the typeglob.
     */
    public RuntimeScalar scalar() {
        RuntimeScalar ret = new RuntimeScalar();
        ret.type = RuntimeScalarType.GLOB;
        ret.value = this;
        return ret;
    }

    /**
     * Retrieves the boolean value of the typeglob.
     *
     * @return Always true, indicating the presence of the typeglob.
     */
    public boolean getBoolean() {
        return true;
    }

    /**
     * Creates a reference to the typeglob.
     *
     * @return A RuntimeScalar representing the reference to the typeglob.
     */
    public RuntimeScalar createReference() {
        RuntimeScalar ret = new RuntimeScalar();
        ret.type = RuntimeScalarType.GLOBREFERENCE;
        ret.value = this;
        return ret;
    }

    /**
     * Gets the list value of the typeglob.
     *
     * @return A RuntimeList containing the scalar representation of the typeglob.
     */
    public RuntimeList getList() {
        return new RuntimeList(this.scalar());
    }

    /**
     * Adds itself to a RuntimeArray.
     *
     * @param array The RuntimeArray to which this typeglob will be added.
     */
    public void addToArray(RuntimeArray array) {
        array.push(this.scalar());
    }

    /**
     * Adds itself to a RuntimeScalar.
     *
     * @param scalar The RuntimeScalar to which this typeglob will be added.
     * @return The updated RuntimeScalar.
     */
    public RuntimeScalar addToScalar(RuntimeScalar scalar) {
        return scalar.set(this);
    }

    /**
     * Sets itself from a RuntimeList.
     *
     * @param value The RuntimeList from which this typeglob will be set.
     * @return The updated RuntimeArray.
     */
    public RuntimeArray setFromList(RuntimeList value) {
        return new RuntimeArray(this.set(value.scalar()));
    }

    /**
     * The keys() operator for typeglobs.
     *
     * @return Throws an IllegalStateException as typeglobs do not support keys.
     */
    public RuntimeArray keys() {
        throw new IllegalStateException("Type of arg 1 to values must be hash or array");
    }

    /**
     * The values() operator for typeglobs.
     *
     * @return Throws an IllegalStateException as typeglobs do not support values.
     */
    public RuntimeArray values() {
        throw new IllegalStateException("Type of arg 1 to values must be hash or array");
    }

    /**
     * The each() operator for typeglobs.
     *
     * @return Throws an IllegalStateException as typeglobs do not support each.
     */
    public RuntimeList each() {
        throw new IllegalStateException("Type of arg 1 to each must be hash or array");
    }

    /**
     * Performs the chop operation on the typeglob.
     *
     * @return Throws an IllegalStateException as chop is not implemented for typeglobs.
     */
    public RuntimeScalar chop() {
        throw new IllegalStateException("chop glob is not implemented");
    }

    /**
     * Performs the chomp operation on the typeglob.
     *
     * @return Throws an IllegalStateException as chomp is not implemented for typeglobs.
     */
    public RuntimeScalar chomp() {
        throw new IllegalStateException("chomp glob is not implemented");
    }

    /**
     * Returns an iterator over the elements of type RuntimeScalar.
     *
     * @return An Iterator<RuntimeScalar> for iterating over the elements.
     */
    public Iterator<RuntimeScalar> iterator() {
        return this.scalar().iterator();
    }

    /**
     * Gets the Glob alias into an Array.
     *
     * @param arr The RuntimeArray to which the alias will be added.
     * @return The updated RuntimeArray.
     */
    public RuntimeArray setArrayOfAlias(RuntimeArray arr) {
        arr.elements.add(this.scalar());
        return arr;
    }

    /**
     * Undefines the elements of the typeglob.
     * This method clears the CODE reference and invalidates the method resolution cache.
     *
     * @return The current RuntimeGlob instance after undefining its elements.
     */
    public RuntimeGlob undefine() {
        // Undefine CODE
        GlobalContext.getGlobalCodeRef(this.globName).set(new RuntimeScalar());

        // Invalidate the method resolution cache
        InheritanceResolver.invalidateCache();

        // XXX TODO undefine scalar, array, hash
        return this;
    }

    /**
     * Saves the current state of the instance.
     *
     * <p>This method creates a snapshot of the current value,
     * and pushes it onto a static stack for later restoration. After saving, it clears
     * the current elements and resets the value.
     */
    @Override
    public void dynamicSaveState() {
        throw new PerlCompilerException("not implemented: local GLOB");
    }

    /**
     * Restores the most recently saved state of the instance.
     *
     * <p>This method pops the most recent state from the static stack and restores
     * the value. If no state is saved, it does nothing.
     */
    @Override
    public void dynamicRestoreState() {
        throw new PerlCompilerException("not implemented: local GLOB");
    }
}
