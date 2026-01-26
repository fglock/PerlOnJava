package org.perlonjava.runtime;

import java.util.Stack;

/**
 * RuntimeHashProxyEntry acts as a proxy for accessing elements within a RuntimeHash.
 * It provides a mechanism to lazily initialize (vivify) elements in the hash
 * when they are accessed.
 */
public class RuntimeHashProxyEntry extends RuntimeBaseProxy {
    private static final Stack<RuntimeScalar> dynamicStateStack = new Stack<>();

    // Reference to the parent RuntimeHash
    private final RuntimeHash parent;
    // Key associated with this proxy in the parent hash
    private final String key;

    /**
     * Constructs a RuntimeHashProxyEntry for a given key in the specified parent hash.
     *
     * @param parent the parent RuntimeHash containing the elements
     * @param key    the key in the hash for which this proxy is created
     */
    public RuntimeHashProxyEntry(RuntimeHash parent, String key) {
        super();
        this.parent = parent;
        this.key = key;
        // Note: this.type is RuntimeScalarType.UNDEF
    }

    /**
     * Vivifies (initializes) the element in the parent hash if it does not exist.
     * If the element associated with the key is not present, it creates a new
     * RuntimeScalar and assigns it to the key in the parent hash.
     */
    void vivify() {
        if (lvalue == null) {
            // Check if the key is not present in the hash
            if (!parent.elements.containsKey(key)) {
                // Add a new RuntimeScalar for the key
                // If parent is a RuntimeStash, create a RuntimeStashEntry
                if (parent instanceof RuntimeStash stash) {
                    parent.put(key, new RuntimeStashEntry(stash.namespace + key, false));
                } else {
                    parent.put(key, new RuntimeScalar());
                }
            }
            // Retrieve the element associated with the key
            lvalue = parent.elements.get(key);
        }
    }

    /**
     * Saves the current state of the RuntimeScalar instance.
     *
     * <p>This method creates a snapshot of the current type and value of the scalar,
     * and pushes it onto a static stack for later restoration.
     */
    @Override
    public void dynamicSaveState() {
        // Create a new RuntimeScalar to save the current state
        if (this.lvalue == null) {
            dynamicStateStack.push(null);
            vivify();
        } else {
            RuntimeScalar currentState = new RuntimeScalar();
            // Copy the current type and value to the new state
            currentState.type = this.lvalue.type;
            currentState.value = this.lvalue.value;
            currentState.blessId = this.lvalue.blessId;
            dynamicStateStack.push(currentState);
            // Clear the current type and value
            this.undefine();
        }
    }

    /**
     * Restores the most recently saved state of the RuntimeScalar instance.
     *
     * <p>This method pops the most recent state from the static stack and restores
     * the type and value to the current scalar. If no state is saved, it does nothing.
     */
    @Override
    public void dynamicRestoreState() {
        if (!dynamicStateStack.isEmpty()) {
            // Pop the most recent saved state from the stack
            RuntimeScalar previousState = dynamicStateStack.pop();
            if (previousState == null) {
                parent.elements.remove(key);
                this.lvalue = null;
                this.type = RuntimeScalarType.UNDEF;
                this.value = null;
            } else {
                // Restore the type, value from the saved state
                this.set(previousState);
                this.lvalue.blessId = previousState.blessId;
                this.blessId = previousState.blessId;
            }
        }
    }
}
