package org.perlonjava.runtime;

import java.util.List;
import java.util.Stack;

/**
 * RuntimeArrayProxyEntry acts as a proxy for accessing elements within a RuntimeArray.
 * It provides a mechanism to lazily initialize (vivify) elements in the array
 * when they are accessed.
 */
public class RuntimeArrayProxyEntry extends RuntimeBaseProxy {
    private static final Stack<Integer> dynamicStateStackInt = new Stack<>();
    private static final Stack<RuntimeScalar> dynamicStateStack = new Stack<>();

    // Reference to the parent RuntimeArray
    private final RuntimeArray parent;
    // Index associated with this proxy in the parent array
    private final int key;

    /**
     * Constructs a RuntimeArrayProxyEntry for a given index in the specified parent array.
     *
     * @param parent the parent RuntimeArray containing the elements
     * @param key    the index in the array for which this proxy is created
     */
    public RuntimeArrayProxyEntry(RuntimeArray parent, int key) {
        super();
        this.parent = parent;
        this.key = key;
        // Note: this.type is RuntimeScalarType.UNDEF
    }

    /**
     * Vivifies (initializes) the element in the parent array if it does not exist.
     * If the element at the specified index is not present, it creates new
     * RuntimeScalar instances up to that index and assigns them in the parent array.
     */
    void vivify() {
        if (lvalue == null) {
            if (parent.type == RuntimeArray.READONLY_ARRAY) {
                throw new PerlCompilerException("Modification of a read-only value attempted");
            }
            lvalue = new RuntimeScalar();

            if (parent.type == RuntimeArray.AUTOVIVIFY_ARRAY) {
                // If the array is auto-vivified, vivify the parent array
                AutovivificationArray.vivify(parent);
            }

            List<RuntimeScalar> elements = parent.elements;

            // Expand the array if needed
            while (key >= elements.size()) {
                elements.add(null); // Add null placeholders
            }

            // Set the element at the index
            elements.set(key, lvalue);
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
        dynamicStateStackInt.push(parent.elements.size());
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
                this.lvalue = null;
                this.type = RuntimeScalarType.UNDEF;
                this.value = null;
            } else {
                // Restore the type, value from the saved state
                this.set(previousState);
                this.lvalue.blessId = previousState.blessId;
                this.blessId = previousState.blessId;
            }
            int previousSize = dynamicStateStackInt.pop();
            while (parent.elements.size() > previousSize) {
                parent.elements.removeLast();
            }
        }
    }
}
