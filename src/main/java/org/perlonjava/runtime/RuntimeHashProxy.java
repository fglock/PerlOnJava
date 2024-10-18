package org.perlonjava.runtime;

/**
 * RuntimeHashProxy acts as a proxy for accessing elements within a RuntimeHash.
 * It provides a mechanism to lazily initialize (vivify) elements in the hash
 * when they are accessed.
 */
public class RuntimeHashProxy extends RuntimeBaseProxy {
    // Reference to the parent RuntimeHash
    private final RuntimeHash parent;
    // Key associated with this proxy in the parent hash
    private final String key;

    /**
     * Constructs a RuntimeHashProxy for a given key in the specified parent hash.
     *
     * @param parent the parent RuntimeHash containing the elements
     * @param key    the key in the hash for which this proxy is created
     */
    public RuntimeHashProxy(RuntimeHash parent, String key) {
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
                parent.elements.put(key, new RuntimeScalar());
            }
            // Retrieve the element associated with the key
            lvalue = parent.elements.get(key);
        }
    }
}
