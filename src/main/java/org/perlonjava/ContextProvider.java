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
}

