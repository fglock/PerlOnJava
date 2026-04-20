package org.perlonjava.runtime.runtimetypes;

/**
 * RuntimeTiedArrayProxyEntry acts as a proxy for accessing elements within a tied RuntimeArray.
 * It delegates all operations to the tied object's FETCH and STORE methods.
 */
public class RuntimeTiedArrayProxyEntry extends TiedVariableBase {
    // Reference to the parent RuntimeArray (which is tied)
    private final RuntimeArray parent;
    // Index associated with this proxy in the parent array (already normalized)
    private final RuntimeScalar key;
    // Original (unnormalized) negative index, when the normalized key would still be
    // negative. In that case FETCH is not dispatched (read yields undef) and STORE
    // throws "Modification of non-creatable array value attempted", matching Perl.
    private final Integer outOfRangeOriginalIndex;

    /**
     * Constructs a RuntimeTiedArrayProxyEntry for a given index in the specified tied array.
     *
     * @param parent the parent RuntimeArray that is tied
     * @param key    the (already normalized) index in the array for which this proxy is created
     */
    public RuntimeTiedArrayProxyEntry(RuntimeArray parent, RuntimeScalar key) {
        this(parent, key, null);
    }

    /**
     * Constructs a proxy that represents an out-of-range (still-negative after
     * normalization) subscript. Reads return undef without touching the tie
     * handler; writes throw the usual Perl "non-creatable" error.
     */
    public RuntimeTiedArrayProxyEntry(RuntimeArray parent, RuntimeScalar key, Integer outOfRangeOriginalIndex) {
        super(null, null);
        this.parent = parent;
        this.key = key;
        this.outOfRangeOriginalIndex = outOfRangeOriginalIndex;
    }

    /**
     * Vivifies the element by calling FETCH on the tied object.
     * This ensures lvalue is populated with the current value from the tied array.
     */
    @Override
    void vivify() {
        if (outOfRangeOriginalIndex != null) {
            // Negative subscript that normalizes to a still-negative index: Perl
            // does not dispatch FETCH; the value is simply undef.
            this.type = RuntimeScalarType.UNDEF;
            this.value = null;
            return;
        }
        // Always fetch the current value from the tied object
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
    public RuntimeScalar tiedStore(RuntimeScalar value) {
        if (outOfRangeOriginalIndex != null) {
            throw new PerlCompilerException(
                    "Modification of non-creatable array value attempted, subscript "
                            + outOfRangeOriginalIndex);
        }
        return TieArray.tiedStore(parent, key, value);
    }

    @Override
    public RuntimeScalar tiedFetch() {
        if (outOfRangeOriginalIndex != null) {
            return new RuntimeScalar();
        }
        return TieArray.tiedFetch(parent, key);
    }
}