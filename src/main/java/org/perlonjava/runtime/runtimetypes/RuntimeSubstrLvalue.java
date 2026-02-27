package org.perlonjava.runtime.runtimetypes;

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

        this.type = (parent.type == RuntimeScalarType.BYTE_STRING) ? RuntimeScalarType.BYTE_STRING : RuntimeScalarType.STRING;
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
        int strLength = parentValue.codePointCount(0, parentValue.length());

        // Calculate the actual offset, handling negative offsets
        int actualOffset = offset < 0 ? strLength + offset : offset;

        // Ensure the offset is within bounds
        if (actualOffset < 0) {
            actualOffset = 0;
        }
        if (actualOffset > strLength) {
            throw new PerlCompilerException("substr outside of string");
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

        // Convert code point offsets to UTF-16 indices for StringBuilder operations
        int startIndex = parentValue.offsetByCodePoints(0, actualOffset);
        int endIndex = parentValue.offsetByCodePoints(startIndex, actualLength);

        // Handle the case where the offset is beyond the current string length
        if (actualOffset >= strLength) {
            // append the new value
            updatedValue.append(newValue);
        } else {
            // Replace the substring with the new value
            updatedValue.replace(startIndex, endIndex, newValue);
        }

        RuntimeScalar newVal = new RuntimeScalar(updatedValue.toString());
        if (lvalue.type == RuntimeScalarType.BYTE_STRING) {
            newVal.type = RuntimeScalarType.BYTE_STRING;
        }
        lvalue.set(newVal);

        return this;
    }
}
