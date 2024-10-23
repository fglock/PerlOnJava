package org.perlonjava.runtime;

import java.util.ArrayList;
import java.util.List;

/**
 * The StateContext class manages state variables, ensuring they persist across function calls.
 */
public class StateContext {

    // Lists to store state variables of different types
    private static final List<RuntimeScalar> stateScalars = new ArrayList<>();
    private static final List<RuntimeArray> stateArrays = new ArrayList<>();
    private static final List<RuntimeHash> stateHashes = new ArrayList<>();
    private static final List<RuntimeScalar> stateCodeRefs = new ArrayList<>();

    /**
     * Retrieves a state scalar by its index, initializing it if necessary.
     *
     * @param index The index of the state scalar.
     * @return The RuntimeScalar representing the state scalar.
     */
    public static RuntimeScalar getStateScalar(int index) {
        return getStateVariable(stateScalars, index, RuntimeScalar::new);
    }

    /**
     * Retrieves a state array by its index, initializing it if necessary.
     *
     * @param index The index of the state array.
     * @return The RuntimeArray representing the state array.
     */
    public static RuntimeArray getStateArray(int index) {
        return getStateVariable(stateArrays, index, RuntimeArray::new);
    }

    /**
     * Retrieves a state hash by its index, initializing it if necessary.
     *
     * @param index The index of the state hash.
     * @return The RuntimeHash representing the state hash.
     */
    public static RuntimeHash getStateHash(int index) {
        return getStateVariable(stateHashes, index, RuntimeHash::new);
    }

    /**
     * Retrieves a state code reference by its index, initializing it if necessary.
     *
     * @param index The index of the state code reference.
     * @return The RuntimeScalar representing the state code reference.
     */
    public static RuntimeScalar getStateCodeRef(int index) {
        return getStateVariable(stateCodeRefs, index, RuntimeScalar::new);
    }

    // Generic method to retrieve or initialize a state variable
    private static <T> T getStateVariable(List<T> list, int index, java.util.function.Supplier<T> initializer) {
        ensureCapacity(list, index);
        T var = list.get(index);
        if (var == null) {
            var = initializer.get();
            list.set(index, var);
        }
        return var;
    }

    // Helper method to ensure the list has enough capacity
    private static <T> void ensureCapacity(List<T> list, int index) {
        while (list.size() <= index) {
            list.add(null);
        }
    }
}
