package org.perlonjava.runtime;

import java.util.Iterator;

import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;

/**
 * The RuntimeBaseEntity class serves as an abstract base class for scalar, hash,
 * and array variables in the runtime environment. It provides common functionality
 * and interfaces for these entities.
 */
public abstract class RuntimeBaseEntity implements RuntimeDataProvider, DynamicState {
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
     * @return The first element as a RuntimeBaseEntity, or `undef` if empty
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

}