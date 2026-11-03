package org.perlonjava.runtime.runtimetypes;

import java.util.Stack;

/**
 * RuntimeTiedHashProxyEntry acts as a proxy for accessing elements within a tied RuntimeHash.
 * It delegates all operations to the tied object's FETCH and STORE methods.
 */
public class RuntimeTiedHashProxyEntry extends TiedVariableBase {
    @SuppressWarnings("unchecked")
    private static Stack<SavedState> dynamicStateStack() {
        return (Stack<SavedState>) (Stack<?>)
                PerlRuntime.current().executionState().tiedHashProxyStates;
    }

    // Reference to the parent RuntimeHash (which is tied)
    private final RuntimeHash parent;
    // Preserve the magic view captured when this lvalue was formed. untie may
    // replace parent.elements while this proxy remains active in @_ .
    private final TieHash tieHandle;
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
        this.tieHandle = parent.elements instanceof TieHash tieHash ? tieHash : null;
        this.key = key;
    }

    @Override
    RuntimeBase argumentAliasContainer() {
        return parent;
    }

    @Override
    RuntimeScalar retainArgumentAliasHandlerOwner() {
        return tieHandle == null ? null
                : RuntimeScalar.retainTiedHandlerValue(tieHandle.getSelf());
    }

    /**
     * Vivifies the element by calling FETCH on the tied object.
     * This ensures lvalue is populated with the current value from the tied array.
     */
    @Override
    RuntimeScalar storageOwnerReference() {
        return parent.createAnonymousReference();
    }

    @Override
    void vivify() {
        // Always fetch the current value from the tied object
        RuntimeScalar fetchedValue = tiedFetchFromAlias();
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
        return tiedStoreFromAlias(value);
    }

    @Override
    public RuntimeScalar tiedFetch() {
        return tiedFetchFromAlias();
    }

    @Override
    public RuntimeArray arrayDeref() {
        RuntimeScalar fetched = tiedFetch();
        if (fetched.type == RuntimeScalarType.UNDEF) {
            RuntimeArray array = AutovivificationArray.createAutovivifiedArray(this);
            array.strictAutovivify = false;
            return array;
        }
        RuntimeArray array = fetched.arrayDeref();
        this.type = fetched.type;
        this.value = fetched.value;
        return array;
    }

    @Override
    public RuntimeArray arrayDerefNonStrict(String packageName) {
        RuntimeScalar fetched = tiedFetch();
        if (fetched.type == RuntimeScalarType.UNDEF) {
            RuntimeArray array = AutovivificationArray.createAutovivifiedArray(this);
            array.strictAutovivify = false;
            return array;
        }
        RuntimeArray array = fetched.arrayDerefNonStrict(packageName);
        this.type = fetched.type;
        this.value = fetched.value;
        return array;
    }

    @Override
    public void dynamicSaveState() {
        boolean existed = tiedExistsFromAlias().getBoolean();
        RuntimeScalar saved = existed ? new RuntimeScalar(tiedFetchFromAlias()) : null;
        dynamicStateStack().push(new SavedState(existed, saved));
    }

    @Override
    public void dynamicRestoreState() {
        Stack<SavedState> dynamicStateStack = dynamicStateStack();
        if (dynamicStateStack.isEmpty()) {
            return;
        }
        SavedState previous = dynamicStateStack.pop();
        if (previous.existed) {
            tiedStoreFromAlias(new RuntimeScalar(previous.value));
        } else {
            tiedDeleteFromAlias();
        }
    }

    private RuntimeScalar tiedFetchFromAlias() {
        return tieHandle == null ? TieHash.tiedFetch(parent, key)
                : TieHash.tiedFetch(tieHandle, key);
    }

    private RuntimeScalar tiedStoreFromAlias(RuntimeScalar value) {
        return tieHandle == null ? TieHash.tiedStore(parent, key, value)
                : TieHash.tiedStore(tieHandle, key, value);
    }

    private RuntimeScalar tiedExistsFromAlias() {
        return tieHandle == null ? TieHash.tiedExists(parent, key)
                : TieHash.tiedExists(tieHandle, key);
    }

    private RuntimeScalar tiedDeleteFromAlias() {
        return tieHandle == null ? TieHash.tiedDelete(parent, key)
                : TieHash.tiedDelete(tieHandle, key);
    }
}
