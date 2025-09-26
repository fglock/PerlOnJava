package org.perlonjava.runtime;

import java.util.Iterator;

import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;

/**
 * The RuntimeBase class serves as an abstract base class for scalar, hash,
 * and array variables in the runtime environment. It provides common functionality
 * and interfaces for these entities.
 */
public abstract class RuntimeBase implements DynamicState, Iterable<RuntimeScalar> {
    // Index to the class that this reference belongs
    public int blessId;

    /**
     * Adds this entity to the specified RuntimeList.
     *
     * @param list the RuntimeList to which this entity will be added
     */
    public void addToList(RuntimeList list) {
        list.add(this);
    }

    /**
     * Adds itself to a RuntimeArray.
     *
     * @param array the RuntimeArray object to which this entity will be added
     */
    public abstract void addToArray(RuntimeArray array);

    /**
     * Retrieves a RuntimeScalar instance.
     *
     * @return a RuntimeScalar object representing the scalar value
     */
    public abstract RuntimeScalar scalar();

    /**
     * Retrieves a RuntimeList instance.
     * This is always called at the end of a subroutine to transform the return value to RuntimeList.
     *
     * @return a RuntimeList object representing the list of elements
     */
    public abstract RuntimeList getList();

    /**
     * Retrieves the array value of the object as aliases.
     * This method initializes a new RuntimeArray and sets it as the alias for this entity.
     *
     * @return a RuntimeArray representing the array of aliases for this entity
     */
    public RuntimeArray getArrayOfAlias() {
        RuntimeArray arr = new RuntimeArray();
        this.setArrayOfAlias(arr);
        return arr;
    }

    /**
     * Gets the total number of elements in all elements of the list as a RuntimeScalar.
     * This method provides a count of elements, useful for determining the size of collections.
     *
     * @return a RuntimeScalar representing the count of elements
     */
    public RuntimeScalar count() {
        return new RuntimeScalar(countElements());
    }

    /**
     * Abstract method to set the array of aliases for this entity.
     * Subclasses should provide an implementation for this method.
     *
     * @param arr the RuntimeArray to be set as the array of aliases
     */
    public abstract RuntimeArray setArrayOfAlias(RuntimeArray arr);

    /**
     * Abstract method to count the elements within this entity.
     * Subclasses should provide an implementation for this method.
     *
     * @return the number of elements as an integer
     */
    public abstract int countElements();

    public void setBlessId(int blessId) {
        this.blessId = blessId;
    }

    /**
     * Gets the first element of the list.
     * For arrays and hashes, returns their first element using iteration.
     *
     * @return The first element as a RuntimeBase, or `undef` if empty
     */
    public RuntimeScalar getFirst() {
        Iterator<RuntimeScalar> iterator = this.iterator();
        if (iterator.hasNext()) {
            return iterator.next();
        }
        return scalarUndef;
    }

    public String toStringRef() {
        return this.toString();
    }

    public double getDoubleRef() {
        return this.hashCode();
    }

    /**
     * Retrieves the boolean value of the object.
     *
     * @return a boolean representing the truthiness of the object
     */
    public abstract boolean getBoolean();

    /**
     * Retrieves the defined boolean value of the object.
     *
     * @return a boolean indicating whether the object is defined
     */
    public abstract boolean getDefinedBoolean();

    /**
     * Creates a reference to the current object.
     *
     * @return a RuntimeScalar representing the reference
     */
    public abstract RuntimeScalar createReference();

    /**
     * Undefines the elements of the object.
     *
     * @return the object after undefining its elements
     */
    public abstract RuntimeBase undefine();

    /**
     * Adds itself to a RuntimeScalar.
     *
     * @param scalar the RuntimeScalar object to which this entity will be added
     * @return the updated RuntimeScalar object
     */
    public abstract RuntimeScalar addToScalar(RuntimeScalar scalar);

    /**
     * Sets itself from a RuntimeList.
     *
     * @param list the RuntimeList object from which this entity will be set
     * @return the updated RuntimeArray object
     */
    public abstract RuntimeArray setFromList(RuntimeList list);

    /**
     * Retrieves the result of keys() as a RuntimeArray instance.
     *
     * @return a RuntimeArray object representing the keys
     */
    public abstract RuntimeArray keys();

    /**
     * Retrieves the result of values() as a RuntimeArray instance.
     *
     * @return a RuntimeArray object representing the values
     */
    public abstract RuntimeArray values();

    /**
     * Retrieves the result of each() as a RuntimeList instance.
     *
     * @return a RuntimeList object representing the key-value pairs
     */
    public abstract RuntimeList each(int ctx);

    /**
     * Performs the chop operation on the object.
     *
     * @return a RuntimeScalar representing the result of the chop operation
     */
    public abstract RuntimeScalar chop();

    /**
     * Performs the chomp operation on the object.
     *
     * @return a RuntimeScalar representing the result of the chomp operation
     */
    public abstract RuntimeScalar chomp();
}