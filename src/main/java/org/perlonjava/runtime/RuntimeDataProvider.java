package org.perlonjava.runtime;

import java.util.Iterator;

/**
 * The RuntimeDataProvider interface defines methods for obtaining different types of runtime data.
 * Classes implementing this interface should provide implementations for these methods to interact
 * with various runtime data structures like arrays, lists, and scalars.
 */
public interface RuntimeDataProvider {

    /**
     * Retrieves a RuntimeArray of aliases instance.
     * This is used to build the subroutine call parameter list `@_`.
     *
     * @return a RuntimeArray object representing the array of aliases
     */
    RuntimeArray getArrayOfAlias();

    /**
     * Pushes the data into a RuntimeArray of aliases instance.
     * This provides data to getArrayOfAlias() and to iterator().
     *
     * @param arr the RuntimeArray to be set as the array of aliases
     * @return the updated RuntimeArray object
     */
    RuntimeArray setArrayOfAlias(RuntimeArray arr);

    /**
     * Retrieves a RuntimeList instance.
     * This is always called at the end of a subroutine to transform the return value to RuntimeList.
     *
     * @return a RuntimeList object representing the list of elements
     */
    RuntimeList getList();

    /**
     * Retrieves a RuntimeScalar instance.
     *
     * @return a RuntimeScalar object representing the scalar value
     */
    RuntimeScalar scalar();

    /**
     * Retrieves the boolean value of the object.
     *
     * @return a boolean representing the truthiness of the object
     */
    boolean getBoolean();

    /**
     * Retrieves the defined boolean value of the object.
     *
     * @return a boolean indicating whether the object is defined
     */
    boolean getDefinedBoolean();

    /**
     * Creates a reference to the current object.
     *
     * @return a RuntimeScalar representing the reference
     */
    RuntimeScalar createReference();

    /**
     * Adds itself to a RuntimeArray.
     *
     * @param array the RuntimeArray object to which this entity will be added
     */
    void addToArray(RuntimeArray array);

    /**
     * Adds itself to a RuntimeList.
     *
     * @param list the RuntimeList object to which this entity will be added
     */
    void addToList(RuntimeList list);

    /**
     * Counts the number of RuntimeScalar elements.
     *
     * @return the number of elements as an integer
     */
    int countElements();

    /**
     * Gets the total number of elements in all elements of the list as a RuntimeScalar.
     *
     * @return a RuntimeScalar representing the count of elements
     */
    RuntimeScalar count();

    /**
     * Adds itself to a RuntimeScalar.
     *
     * @param scalar the RuntimeScalar object to which this entity will be added
     * @return the updated RuntimeScalar object
     */
    RuntimeScalar addToScalar(RuntimeScalar scalar);

    /**
     * Sets itself from a RuntimeList.
     *
     * @param list the RuntimeList object from which this entity will be set
     * @return the updated RuntimeArray object
     */
    RuntimeArray setFromList(RuntimeList list);

    /**
     * Retrieves the result of keys() as a RuntimeArray instance.
     *
     * @return a RuntimeArray object representing the keys
     */
    RuntimeArray keys();

    /**
     * Retrieves the result of values() as a RuntimeArray instance.
     *
     * @return a RuntimeArray object representing the values
     */
    RuntimeArray values();

    /**
     * Retrieves the result of each() as a RuntimeList instance.
     *
     * @return a RuntimeList object representing the key-value pairs
     */
    RuntimeList each();

    /**
     * Performs the chop operation on the object.
     *
     * @return a RuntimeScalar representing the result of the chop operation
     */
    RuntimeScalar chop();

    /**
     * Performs the chomp operation on the object.
     *
     * @return a RuntimeScalar representing the result of the chomp operation
     */
    RuntimeScalar chomp();

    /**
     * Returns an iterator over the elements of type RuntimeScalar.
     *
     * @return an Iterator<RuntimeScalar> for iterating over the elements
     */
    Iterator<RuntimeScalar> iterator();

    /**
     * Undefines the elements of the object.
     *
     * @return the object after undefining its elements
     */
    RuntimeDataProvider undefine();
}
