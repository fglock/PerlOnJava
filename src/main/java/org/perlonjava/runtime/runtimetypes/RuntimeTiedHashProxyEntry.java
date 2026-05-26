package org.perlonjava.runtime.runtimetypes;

import java.util.Stack;

/**
 * RuntimeTiedHashProxyEntry acts as a proxy for accessing elements within a tied RuntimeHash.
 * It delegates all operations to the tied object's FETCH and STORE methods.
 */
public class RuntimeTiedHashProxyEntry extends TiedVariableBase {
    private static final Stack<SavedState> dynamicStateStack = new Stack<>();

    // Reference to the parent RuntimeHash (which is tied)
    private final RuntimeHash parent;
    // Index associated with this proxy in the parent array
    private final RuntimeScalar key;

    private static final class SavedState {
        final boolean existed;
        final RuntimeScalar value;

        SavedState(boolean existed, RuntimeScalar value) {
            this.existed = existed;
            this.value = value;
        }
    }

    /**
     * Constructs a RuntimeTiedHashProxyEntry for a given index in the specified tied array.
     *
     * @param parent the parent RuntimeHash that is tied
     * @param key    the index in the array for which this proxy is created
     */
    public RuntimeTiedHashProxyEntry(RuntimeHash parent, RuntimeScalar key) {
        super(null, null);
        this.parent = parent;
        this.key = key;
    }

    /**
     * Vivifies the element by calling FETCH on the tied object.
     * This ensures lvalue is populated with the current value from the tied array.
     */
    @Override
    void vivify() {
        // Always fetch the current value from the tied object
        RuntimeScalar fetchedValue = TieHash.tiedFetch(parent, key);
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
    public RuntimeScalar tiedStore(RuntimeScalar value) {
        return TieHash.tiedStore(parent, key, value);
    }

    @Override
    public RuntimeScalar tiedFetch() {
        return TieHash.tiedFetch(parent, key);
    }

    @Override
    public void dynamicSaveState() {
        boolean existed = TieHash.tiedExists(parent, key).getBoolean();
        RuntimeScalar saved = existed ? new RuntimeScalar(TieHash.tiedFetch(parent, key)) : null;
        dynamicStateStack.push(new SavedState(existed, saved));
    }

    @Override
    public void dynamicRestoreState() {
        if (dynamicStateStack.isEmpty()) {
            return;
        }
        SavedState previous = dynamicStateStack.pop();
        if (previous.existed) {
            TieHash.tiedStore(parent, key, previous.value);
        } else {
            TieHash.tiedDelete(parent, key);
        }
    }
}
