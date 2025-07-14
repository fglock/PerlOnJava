package org.perlonjava.runtime;

public abstract class RuntimeBase implements RuntimeDataProvider, DynamicState {
    public int blessId;

    // Mask for the highest bit of an int (assuming 32-bit integer)
    private static final int HIGHEST_BIT_MASK = 0x80000000;

    /**
     * Sets the highest bit of blessId to indicate overloading.
     */
    public void setOverloading() {
        blessId |= HIGHEST_BIT_MASK;
    }

    /**
     * Clears the highest bit of blessId.
     */
    public void clearOverloading() {
        blessId &= ~HIGHEST_BIT_MASK;
    }

    /**
     * Checks if the highest bit of blessId is set, indicating overloading.
     *
     * @return true if the highest bit is set, false otherwise
     */
    public boolean isOverloading() {
        return (blessId & HIGHEST_BIT_MASK) != 0;
    }

    // Other methods and abstract methods...
}

