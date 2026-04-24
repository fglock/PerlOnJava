package org.perlonjava.runtime.runtimetypes;

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
    // Whether the key was originally a BYTE_STRING (for preserving key type in keys())
    private final boolean byteKey;

    /**
     * Constructs a RuntimeHashProxyEntry for a given key in the specified parent hash.
     *
     * @param parent the parent RuntimeHash containing the elements
     * @param key    the key in the hash for which this proxy is created
     */
    public RuntimeHashProxyEntry(RuntimeHash parent, String key) {
        this(parent, key, false);
    }

    /**
     * Constructs a RuntimeHashProxyEntry with key type tracking.
     *
     * @param parent  the parent RuntimeHash containing the elements
     * @param key     the key in the hash for which this proxy is created
     * @param byteKey true if the key was from a BYTE_STRING scalar
     */
    public RuntimeHashProxyEntry(RuntimeHash parent, String key, boolean byteKey) {
        super();
        this.parent = parent;
        this.key = key;
        this.byteKey = byteKey;
        // Note: this.type is RuntimeScalarType.UNDEF
    }

    /**
     * Pre-initializes the lvalue pointer. Used by {@code RuntimeHash.getForLocal()}
     * when the key already exists in the hash, so that {@code dynamicSaveState()}
     * correctly sees the existing value rather than treating it as a new key.
     */
    void initLvalue(RuntimeScalar existing) {
        this.lvalue = existing;
        this.type = existing.type;
        this.value = existing.value;
    }

    /**
     * Creates a reference to the underlying lvalue, vivifying it first.
     * In Perl, \$hash{key} auto-vivifies the hash entry so that the reference
     * points to the actual hash element, not a temporary.
     */
    @Override
    public RuntimeScalar createReference() {
        vivify();
        return lvalue.createReference();
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
                // Track the key's byte/UTF-8 type
                parent.markKeyByte(key, byteKey);
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
                // Key didn't exist before — remove it from the parent hash.
                // Re-fetch from parent in case hash was reassigned (setFromList clears elements).
                RuntimeScalar current = parent.elements.remove(key);
                if (current != null
                        && (current.type & RuntimeScalarType.REFERENCE_BIT) != 0
                        && current.value instanceof RuntimeBase displacedBase
                        && displacedBase.refCount > 0 && --displacedBase.refCount == 0) {
                    displacedBase.refCount = Integer.MIN_VALUE;
                    DestroyDispatch.callDestroy(displacedBase);
                }
                this.lvalue = null;
                this.type = RuntimeScalarType.UNDEF;
                this.value = null;
            } else {
                // Re-fetch or create the entry in the parent hash by key.
                // This handles the case where %hash was reassigned between save and restore
                // (setFromList does elements.clear() which orphans the old lvalue).
                RuntimeScalar target = parent.elements.get(key);
                if (target == null) {
                    target = new RuntimeScalar();
                    parent.elements.put(key, target);
                }
                this.lvalue = target;
                // Restore the saved value into the current hash entry
                // lvalue.set() goes through setLarge() which handles refCount
                this.lvalue.set(previousState);
                this.lvalue.blessId = previousState.blessId;
                // Sync proxy state
                this.type = this.lvalue.type;
                this.value = this.lvalue.value;
                this.blessId = previousState.blessId;
            }
        }
    }
}
