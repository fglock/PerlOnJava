package org.perlonjava.runtime;

/**
 * Represents a proxy for an element in a RuntimeArray.
 * This proxy is used when accessing potentially non-existent array elements,
 * allowing for lazy evaluation and autovivification in a Perl-like manner.
 */
public class RuntimeArrayProxy {
    private final RuntimeArray parent;
    private final int index;

    /**
     * Constructs a new RuntimeArrayProxy.
     *
     * @param parent The RuntimeArray this proxy belongs to.
     * @param index The index of the element in the RuntimeArray.
     */
    public RuntimeArrayProxy(RuntimeArray parent, int index) {
        this.parent = parent;
        this.index = index;
    }

    public RuntimeScalar defined() {
        if (index < parent.elements.size())) {
            return ((RuntimeScalar) parent.elements.get(index)).defined();
        }
        return new RuntimeScalar();
    }

    /**
     * Gets the parent RuntimeArray.
     *
     * @return The parent RuntimeArray.
     */
    public RuntimeArray getParent() {
        return parent;
    }

    /**
     * Gets the index of the element in the RuntimeArray.
     *
     * @return The index.
     */
    public int getIndex() {
        return index;
    }
}

