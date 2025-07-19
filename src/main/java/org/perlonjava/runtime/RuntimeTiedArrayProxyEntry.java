package org.perlonjava.runtime;

import org.perlonjava.operators.MathOperators;

import java.util.Stack;

import static org.perlonjava.runtime.RuntimeScalarCache.getScalarBoolean;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarOne;

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
        // Always fetch the current value from the tied object
        // System.out.println("vivify");
        RuntimeScalar fetchedValue = TieArray.tiedFetch(parent, key);
        this.type = fetchedValue.type;
        this.value = fetchedValue.value;
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
        return TieArray.tiedStore(parent, key, value);
    }

    public RuntimeScalar defined() {
        vivify();
        return super.defined();
    }

    public boolean getDefinedBoolean() {
        vivify();
        return super.getDefinedBoolean();
    }

    public String toString() {
        vivify();
        return super.toString();
    }

    @Override
    public boolean getBoolean() {
        vivify();
        return super.getBoolean();
    }

    public RuntimeScalar scalar() {
        vivify();
        return this;
    }

    RuntimeScalar fetch() {
        vivify();
        return new RuntimeScalar(this);
    }

    @Override
    public RuntimeScalar preAutoIncrement() {
        return this.set(this.fetch().preAutoIncrement());
    }

    @Override
    public RuntimeScalar postAutoIncrement() {
        RuntimeScalar old = this.fetch().postAutoIncrement();
        this.set(this);
        return old;
    }

    @Override
    public RuntimeScalar preAutoDecrement() {
        return this.set(this.fetch().preAutoDecrement());
    }

    @Override
    public RuntimeScalar postAutoDecrement() {
        RuntimeScalar old = this.fetch().postAutoDecrement();
        this.set(this);
        return old;
    }

    public void addToArray(RuntimeArray array) {
        vivify();
        super.addToArray(array);
    }

    public RuntimeScalar addToScalar(RuntimeScalar v) {
        vivify();
        return super.addToScalar(v);
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