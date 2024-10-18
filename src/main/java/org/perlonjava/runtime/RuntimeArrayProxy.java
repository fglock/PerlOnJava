package org.perlonjava.runtime;

/**
 * RuntimeArrayProxy acts as a proxy for accessing elements within a RuntimeArray.
 * It provides a mechanism to lazily initialize (vivify) elements in the array
 * when they are accessed.
 */
public class RuntimeArrayProxy extends RuntimeBaseProxy {
    // Reference to the parent RuntimeArray
    private final RuntimeArray parent;
    // Index associated with this proxy in the parent array
    private final int key;

    /**
     * Constructs a RuntimeArrayProxy for a given index in the specified parent array.
     *
     * @param parent the parent RuntimeArray containing the elements
     * @param key    the index in the array for which this proxy is created
     */
    public RuntimeArrayProxy(RuntimeArray parent, int key) {
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
            // Check if the index is beyond the current size of the array
            if (key >= parent.elements.size()) {
                // Add new RuntimeScalar instances up to the specified index
                for (int i = parent.elements.size(); i <= key; i++) {
                    parent.elements.add(i, new RuntimeScalar());
                }
            }
            // Retrieve the element at the specified index
            lvalue = parent.elements.get(key);
        }
    }
}
