package org.perlonjava.runtime;

/**
 * Represents a proxy for an element in a RuntimeHash.
 * This proxy is used when accessing potentially non-existent hash elements,
 * allowing for lazy evaluation and autovivification in a Perl-like manner.
 */
public class RuntimeHashProxy {
    private final RuntimeHash parent;
    private final String key;

    /**
     * Constructs a new RuntimeHashProxy.
     *
     * @param parent The RuntimeHash this proxy belongs to.
     * @param key The key of the element in the RuntimeHash.
     */
    public RuntimeHashProxy(RuntimeHash parent, String key) {
        this.parent = parent;
        this.key = key;
    }

    public RuntimeScalar defined() {
        if (parent.elements.containsKey(key)) {
            return parent.elements.get(key).defined();
        }
        return new RuntimeScalar();
    }

    /**
     * Gets the parent RuntimeHash.
     *
     * @return The parent RuntimeHash.
     */
    public RuntimeHash getParent() {
        return parent;
    }

    /**
     * Gets the key of the element in the RuntimeHash.
     *
     * @return The key.
     */
    public String getKey() {
        return key;
    }
}

