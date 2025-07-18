package org.perlonjava.runtime;

import java.util.Stack;

/**
 * RuntimeTiedArrayProxyEntry acts as a proxy for accessing elements within a tied RuntimeArray.
 * It delegates all operations to the tied object's FETCH and STORE methods.
 */
public class RuntimeTiedArrayProxyEntry extends RuntimeBaseProxy {
    private static final Stack<RuntimeScalar> dynamicStateStack = new Stack<>();

    // Reference to the parent RuntimeArray (which is tied)
    private final RuntimeArray parent;
    // Index associated with this proxy in the parent array
    private final RuntimeScalar key;

    /**
     * Constructs a RuntimeTiedArrayProxyEntry for a given index in the specified tied array.
     *
     * @param parent the parent RuntimeArray that is tied
     * @param key    the index in the array for which this proxy is created
     */
    public RuntimeTiedArrayProxyEntry(RuntimeArray parent, RuntimeScalar key) {
        super();
        this.parent = parent;
        this.key = key;
        // Note: this.type is RuntimeScalarType.UNDEF
    }

    /**
     * Vivifies the element by calling FETCH on the tied object.
     * This ensures lvalue is populated with the current value from the tied array.
     */
    @Override
    void vivify() {
        if (lvalue == null) {
            // Create a new scalar to hold the fetched value
            lvalue = new RuntimeScalar();
        }
        // Always fetch the current value from the tied object
        RuntimeScalar fetchedValue = TieArray.tiedFetch(parent, key);
        lvalue.set(fetchedValue);
        this.type = lvalue.type;
        this.value = lvalue.value;
    }

    /**
     * Sets the value by calling STORE on the tied object.
     *
     * @param value The new value to set.
     * @return The updated underlying scalar.
     */
    @Override
    public RuntimeScalar set(RuntimeScalar value) {
        // Call STORE on the tied object
        TieArray.tiedStore(parent, key, value);

        lvalue = null;
        return this;
//        // Update our local copy
//        if (lvalue == null) {
//            lvalue = new RuntimeScalar();
//        }
//        lvalue.set(value);
//        this.type = lvalue.type;
//        this.value = lvalue.value;
//
//        return lvalue;
    }

    /**
     * Saves the current state of the tied array element.
     * This fetches the current value from the tied object and saves it.
     */
    @Override
    public void dynamicSaveState() {
        // Fetch current value from tied object
        RuntimeScalar currentValue = TieArray.tiedFetch(parent, key);

        // Create a new RuntimeScalar to save the current state
        RuntimeScalar currentState = new RuntimeScalar();
        currentState.type = currentValue.type;
        currentState.value = currentValue.value;
        currentState.blessId = currentValue.blessId;

        dynamicStateStack.push(currentState);
    }

    /**
     * Restores the most recently saved state of the tied array element.
     * This stores the saved value back to the tied object.
     */
    @Override
    public void dynamicRestoreState() {
        if (!dynamicStateStack.isEmpty()) {
            // Pop the most recent saved state from the stack
            RuntimeScalar previousState = dynamicStateStack.pop();

            // Store the previous value back to the tied object
            TieArray.tiedStore(parent, key, previousState);

            // Update our local copy if it exists
            if (lvalue != null) {
                lvalue.set(previousState);
                lvalue.blessId = previousState.blessId;
                this.type = lvalue.type;
                this.value = lvalue.value;
                this.blessId = previousState.blessId;
            }
        }
    }
}