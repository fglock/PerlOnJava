package org.perlonjava.runtime;

/**
 * Represents a substring of a RuntimeScalar that can be used as an lvalue (left-hand value).
 * This class allows for modification of a specific portion of a string within a RuntimeScalar.
 */
public class RuntimeSubstrLvalue extends RuntimeBaseProxy {
    /**
     * The starting position of the substring within the parent string.
     */
    private final int offset;

    /**
     * The length of the substring.
     */
    private final int length;

    /**
     * Constructs a new RuntimeSubstrLvalue.
     *
     * @param parent The parent RuntimeScalar containing the original string.
     * @param str    The substring value.
     * @param offset The starting position of the substring within the parent string.
     * @param length The length of the substring.
     */
    public RuntimeSubstrLvalue(RuntimeScalar parent, String str, int offset, int length) {
        this.lvalue = parent;
        this.offset = offset;
        this.length = length;

        this.type = RuntimeScalarType.STRING;
        this.value = str;
    }

    /**
     * Vivification method (currently empty as substrings don't require vivification).
     */
    @Override
    void vivify() {
    }

    /**
     * Sets the value of this substring and updates the parent string accordingly.
     *
     * @param value The new value to set for this substring.
     * @return This RuntimeSubstrLvalue instance.
     * @throws RuntimeException if the substring is outside the bounds of the parent string.
     */
    @Override
    public RuntimeScalar set(RuntimeScalar value) {
        // Update the local type and value
        this.type = value.type;
        this.value = value.value;

        String parentValue = lvalue.toString();
        String newValue = this.toString();
        int strLength = parentValue.length();

        // Calculate the actual offset, handling negative offsets
        int actualOffset = offset < 0 ? strLength + offset : offset;

        // Ensure the offset is within bounds
        if (actualOffset < 0) {
            actualOffset = 0;
        }
        if (actualOffset > strLength) {
            throw new RuntimeException("substr outside of string");
        }

        // Calculate the actual length, handling negative lengths
        int actualLength = length;
        if (length < 0) {
            actualLength = strLength + length - actualOffset;
        }

        // Ensure the length is within bounds
        if (actualLength < 0) {
            actualLength = 0;
        }
        if (actualOffset + actualLength > strLength) {
            actualLength = strLength - actualOffset;
        }

        StringBuilder updatedValue = new StringBuilder(parentValue);

        // Handle the case where the offset is beyond the current string length
        if (actualOffset >= strLength) {
            // append the new value
            updatedValue.append(newValue);
        } else {
            // Replace the substring with the new value
            int endIndex = actualOffset + actualLength;
            updatedValue.replace(actualOffset, endIndex, newValue);
        }

        // Update the parent RuntimeScalar with the modified string
        lvalue.set(new RuntimeScalar(updatedValue.toString()));

        return this;
    }
}
