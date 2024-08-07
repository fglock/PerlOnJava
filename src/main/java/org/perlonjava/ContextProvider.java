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
     * 
     * @return a RuntimeList object.
     */
    RuntimeList getList();

    /**
     * Retrieves a Runtime instance.
     * 
     * @return a Runtime object.
     */
    Runtime getScalar();

    /**
     * Add itself to a RuntimeArray.
     * 
     * @param array The RuntimeArray object
     * @return void.
     */
    public void addToArray(RuntimeArray array);

    /**
     * Add itself to a RuntimeList.
     * 
     * @param list The RuntimeList object
     * @return void.
     */
    void addToList(RuntimeList list);
}

