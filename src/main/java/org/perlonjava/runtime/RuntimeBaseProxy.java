package org.perlonjava.runtime;

import java.util.Stack;

/**
 * An abstract class that serves as a proxy for a RuntimeScalar object.
 * It provides methods to manipulate and access the underlying scalar value.
 * The class ensures that the underlying scalar is properly initialized
 * before any operations are performed on it.
 * <p>
 * Note: The value is created with the value `undef`.
 */
public abstract class RuntimeBaseProxy extends RuntimeScalar {
    private static final Stack<RuntimeScalar> dynamicStateStack = new Stack<>();

    // The underlying scalar value that this proxy represents.
    RuntimeScalar lvalue;

    /**
     * Ensures that the underlying scalar value is initialized.
     * This method must be implemented by subclasses to provide
     * the specific initialization logic.
     */
    abstract void vivify();

    /**
     * Sets the value of the underlying scalar.
     *
     * @param value The new value to set.
     * @return The updated underlying scalar.
     */
    @Override
    public RuntimeScalar set(RuntimeScalar value) {
        vivify(); // Ensure the scalar is initialized.
        lvalue.set(value);
        this.type = lvalue.type;
        this.value = lvalue.value;
        return lvalue;
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
        RuntimeScalar currentState = new RuntimeScalar();
        // Copy the current type and value to the new state
        currentState.type = this.type;
        currentState.value = this.value;
        currentState.blessId = this.blessId;
        // Push the current state onto the stack
        dynamicStateStack.push(currentState);
        // Clear the current type and value
        this.type = RuntimeScalarType.UNDEF;
        this.value = null;
        this.blessId = 0;
        if (this.value != null) {
            this.lvalue.undefine();
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
            // Restore the type, value from the saved state
            this.set(previousState);
            this.blessId = previousState.blessId;
        }
    }

    /**
     * Undefines the underlying scalar value.
     *
     * @return The updated underlying scalar after undefining.
     */
    public RuntimeScalar undefine() {
        vivify();
        RuntimeScalar ret = lvalue.undefine();
        this.type = lvalue.type;
        this.value = lvalue.value;
        return ret;
    }

    /**
     * Removes the last character from the underlying scalar value.
     *
     * @return The updated underlying scalar after chopping.
     */
    public RuntimeScalar chop() {
        vivify();
        RuntimeScalar ret = lvalue.chop();
        this.type = lvalue.type;
        this.value = lvalue.value;
        return ret;
    }

    /**
     * Removes the trailing newline from the underlying scalar value.
     *
     * @return The updated underlying scalar after chomp.
     */
    public RuntimeScalar chomp() {
        vivify();
        RuntimeScalar ret = lvalue.chomp();
        this.type = lvalue.type;
        this.value = lvalue.value;
        return ret;
    }

    /**
     * Retrieves a value from a hash using the given index.
     * Implements `$v->{key}`
     *
     * @param index The index to use for retrieval.
     * @return The value retrieved from the hash.
     */
    @Override
    public RuntimeScalar hashDerefGet(RuntimeScalar index) {
        vivify();
        RuntimeScalar ret = lvalue.hashDerefGet(index);
        this.type = lvalue.type;
        this.value = lvalue.value;
        return ret;
    }

    /**
     * Retrieves a value from an array using the given index.
     * Implements `$v->[key]`
     *
     * @param index The index to use for retrieval.
     * @return The value retrieved from the array.
     */
    @Override
    public RuntimeScalar arrayDerefGet(RuntimeScalar index) {
        vivify();
        RuntimeScalar ret = lvalue.arrayDerefGet(index);
        this.type = lvalue.type;
        this.value = lvalue.value;
        return ret;
    }

    /**
     * Performs a pre-increment operation on the underlying scalar.
     *
     * @return The updated underlying scalar after pre-increment.
     */
    @Override
    public RuntimeScalar preAutoIncrement() {
        vivify();
        RuntimeScalar ret = lvalue.preAutoIncrement();
        this.type = lvalue.type;
        this.value = lvalue.value;
        return ret;
    }

    /**
     * Performs a post-increment operation on the underlying scalar.
     *
     * @return The updated underlying scalar after post-increment.
     */
    public RuntimeScalar postAutoIncrement() {
        vivify();
        RuntimeScalar ret = lvalue.postAutoIncrement();
        this.type = lvalue.type;
        this.value = lvalue.value;
        return ret;
    }

    /**
     * Performs a pre-decrement operation on the underlying scalar.
     *
     * @return The updated underlying scalar after pre-decrement.
     */
    @Override
    public RuntimeScalar preAutoDecrement() {
        vivify();
        RuntimeScalar ret = lvalue.preAutoDecrement();
        this.type = lvalue.type;
        this.value = lvalue.value;
        return ret;
    }

    /**
     * Performs a post-decrement operation on the underlying scalar.
     *
     * @return The updated underlying scalar after post-decrement.
     */
    public RuntimeScalar postAutoDecrement() {
        vivify();
        RuntimeScalar ret = lvalue.postAutoDecrement();
        this.type = lvalue.type;
        this.value = lvalue.value;
        return ret;
    }
}