package org.perlonjava;

/**
 * ContextProvider interface defines methods for obtaining different types of runtime data.
 * Classes implementing this interface should provide implementations for these methods.
 */
public interface ContextProvider {

    /**
     * Retrieves a RuntimeArray instance.
     * 
     * @return a RuntimeArray object.
     */
    RuntimeArray getArray();

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
    RuntimeScalar getScalar();

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
     * Set itself to a RuntimeList.
     * 
     * @param list The RuntimeList object
     * @return list
     */
    RuntimeList set(RuntimeList list);
}

