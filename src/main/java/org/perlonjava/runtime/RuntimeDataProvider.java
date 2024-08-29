package org.perlonjava.runtime;

import java.util.Iterator;

/**
 * RuntimeDataProvider interface defines methods for obtaining different types of runtime data.
 * Classes implementing this interface should provide implementations for these methods.
 */
public interface RuntimeDataProvider {

    /**
     * Retrieves a RuntimeArray of aliases instance.
     * This is used to build the subroutine call parameter list `@_`
     *
     * @return a RuntimeArray object.
     */
    RuntimeArray getArrayOfAlias();

    /**
     * Push the data into a RuntimeArray of aliases instance.
     * This provides data to getArrayOfAlias() and to iterator()
     *
     * @return a RuntimeArray object.
     */
    RuntimeArray setArrayOfAlias(RuntimeArray arr);

    /**
     * Retrieves a RuntimeList instance.
     * This is always called at the end of a subroutine to transform the return value to RuntimeList
     *
     * @return a RuntimeList object.
     */
    RuntimeList getList();

    /**
     * Retrieves a RuntimeScalar instance.
     *
     * @return a RuntimeScalar object.
     */
    RuntimeScalar scalar();

    /**
     * Add itself to a RuntimeArray.
     *
     * @param array The RuntimeArray object
     */
    void addToArray(RuntimeArray array);

    /**
     * Add itself to a RuntimeList.
     *
     * @param list The RuntimeList object
     */
    void addToList(RuntimeList list);

    /**
     * Count the number of RuntimeScalar elements.
     */
    int countElements();

    /**
     * Add itself to a RuntimeScalar.
     *
     * @param scalar The RuntimeScalar object
     * @return scalar
     */
    RuntimeScalar addToScalar(RuntimeScalar scalar);

    /**
     * Set itself to a RuntimeList.
     *
     * @param list The RuntimeList object
     * @return list
     */
    RuntimeList set(RuntimeList list);

    /**
     * Retrives the result of keys() as a RuntimeArray instance.
     *
     * @return a RuntimeList object.
     */
    RuntimeArray keys();

    /**
     * Retrives the result of values() as a RuntimeArray instance.
     *
     * @return a RuntimeArray object.
     */
    RuntimeArray values();

    /**
     * Method to return an iterator.
     *
     * @return a Iterator<Runtime>
     */
    Iterator<RuntimeScalar> iterator();

    /**
     * undefine the elements of the object
     *
     * @return the object.
     */
    RuntimeDataProvider undefine();
}

