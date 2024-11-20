package org.perlonjava.runtime;

import java.util.Iterator;

/**
 * Represents a runtime typeglob in Perl. Typeglobs are special symbols that can represent
 * all types of Perl variables (scalars, arrays, hashes, subroutines, filehandles) with the same name.
 * This class provides methods to manipulate and interact with typeglobs in the runtime environment.
 */
public class RuntimeGlob extends RuntimeScalar implements RuntimeScalarReference {

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
        // Initialize the RuntimeScalar fields
        this.type = RuntimeScalarType.GLOB;
        this.value = this;
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
                GlobalVariable.getGlobalCodeRef(this.globName).set(value);

                // Invalidate the method resolution cache
                InheritanceResolver.invalidateCache();

                return value;
            case GLOB:
                if (value.value instanceof RuntimeIO) {
                    // *STDOUT = $new_handle
                    GlobalVariable.getGlobalIO(this.globName).set(value);
                }
                return value;
            case ARRAYREFERENCE:
                // Handle the case where a typeglob is assigned a reference to an array
                if (value.value instanceof RuntimeArray) {
                    GlobalVariable.getGlobalArray(this.globName).setFromList(((RuntimeArray) value.value).getList());
                }
                return value;
            case HASHREFERENCE:
                if (value.value instanceof RuntimeHash) {
                    GlobalVariable.getGlobalHash(this.globName).setFromList(((RuntimeHash) value.value).getList());
                }
                return value;
            case REFERENCE:
                if (value.value instanceof RuntimeScalar) {
                    GlobalVariable.getGlobalVariable(this.globName).set(value.scalarDeref());
                }
                return value;
            case UNDEF:
                return value;
        }
        throw new IllegalStateException("typeglob assignment not implemented for " + value.type);
    }

    /**
     * Sets the current RuntimeScalar object to the values associated with the given RuntimeGlob.
     * This method effectively implements the behavior of assigning one typeglob to another,
     * similar to Perl's typeglob assignment.
     *
     * @param value The RuntimeGlob object whose associated values are to be assigned.
     * @return The scalar value associated with the provided RuntimeGlob.
     */
    public RuntimeScalar set(RuntimeGlob value) {
        // Retrieve the name of the glob from the provided RuntimeGlob object.
        String globName = value.globName;

        // Set the current scalar to the global code reference associated with the glob name.
        this.set(GlobalVariable.getGlobalCodeRef(globName));

        // Set the current scalar to the global IO (input/output) reference associated with the glob name.
        this.set(GlobalVariable.getGlobalIO(globName));

        // Set the current scalar to a reference of the global array associated with the glob name.
        this.set(GlobalVariable.getGlobalArray(globName).createReference());

        // Set the current scalar to a reference of the global hash associated with the glob name.
        this.set(GlobalVariable.getGlobalHash(globName).createReference());

        // Set the current scalar to a reference of the global variable associated with the glob name.
        this.set(GlobalVariable.getGlobalVariable(globName).createReference());

        // Return the scalar value associated with the provided RuntimeGlob.
        return value.scalar();
    }

    /**
     * Retrieves a reference or value associated with a specific key from a global variable.
     *
     * <p>This method implements the dereferencing operation for a glob hash, allowing access
     * to various global entities such as CODE, IO, SCALAR, ARRAY, and HASH based on the
     * provided index. It returns a reference or value corresponding to the key.
     *
     * @param index The scalar representing the key to dereference.
     * @return A RuntimeScalar representing the dereferenced value or reference. If the key
     *         is not recognized, an empty RuntimeScalar is returned.
     */
    public RuntimeScalar hashDerefGet(RuntimeScalar index) {
        // System.out.println("glob hashDerefGet " + index.toString());
        return switch (index.toString()) {
            case "CODE" -> GlobalVariable.getGlobalCodeRef(this.globName);
            case "IO" -> GlobalVariable.getGlobalIO(this.globName);
            case "SCALAR" -> GlobalVariable.getGlobalVariable(this.globName);
            case "ARRAY" ->  GlobalVariable.getGlobalArray(this.globName).createReference();
            case "HASH" -> GlobalVariable.getGlobalHash(this.globName).createReference();
            default -> new RuntimeScalar();
        };
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
        String ref = "GLOB(0x" + this.hashCode() + ")";
        return (blessId == 0
                ? ref
                : NameNormalizer.getBlessStr(blessId) + "=" + ref);
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
        return this;
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
        GlobalVariable.getGlobalCodeRef(this.globName).set(new RuntimeScalar());

        // Invalidate the method resolution cache
        InheritanceResolver.invalidateCache();

        // XXX TODO undefine scalar, array, hash
        return this;
    }

    /**
     * Saves the current state of the typeglob.
     */
    @Override
    public void dynamicSaveState() {
        GlobalVariable.getGlobalCodeRef(this.globName).dynamicSaveState();
        GlobalVariable.getGlobalArray(this.globName).dynamicSaveState();
        GlobalVariable.getGlobalHash(this.globName).dynamicSaveState();
        GlobalVariable.getGlobalVariable(this.globName).dynamicSaveState();
        GlobalVariable.getGlobalIO(this.globName).dynamicSaveState();
    }

    /**
     * Restores the most recently saved state of the typeglob.
     */
    @Override
    public void dynamicRestoreState() {
        GlobalVariable.getGlobalIO(this.globName).dynamicRestoreState();
        GlobalVariable.getGlobalVariable(this.globName).dynamicRestoreState();
        GlobalVariable.getGlobalHash(this.globName).dynamicRestoreState();
        GlobalVariable.getGlobalArray(this.globName).dynamicRestoreState();
        GlobalVariable.getGlobalCodeRef(this.globName).dynamicRestoreState();
    }
}
