package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.runtime.operators.WarnDie;

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
     * Flag indicating the substr offset was out of bounds.
     * When true, assignment to this lvalue should die.
     */
    private boolean outOfBounds;

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
        this.outOfBounds = false;

        // Preserve BYTE_STRING type from parent so substr() on byte strings stays byte
        this.type = (parent.type == RuntimeScalarType.BYTE_STRING)
                ? RuntimeScalarType.BYTE_STRING : RuntimeScalarType.STRING;
        this.value = str;
    }

    /**
     * Marks this lvalue as out-of-bounds. Assignment will die.
     */
    public RuntimeSubstrLvalue setOutOfBounds() {
        this.outOfBounds = true;
        return this;
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
        // Die on assignment if the original substr was out of bounds
        if (outOfBounds) {
            WarnDie.die(new RuntimeScalar("substr outside of string"),
                    RuntimeScalarCache.scalarEmptyString);
            return this;
        }

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
            // Perl 5 dies (not just warns) for lvalue substr beyond string length
            WarnDie.die(new RuntimeScalar("substr outside of string"),
                    RuntimeScalarCache.scalarEmptyString);
            return this;
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

        // Update the parent RuntimeScalar with the modified string
        RuntimeScalar updated = new RuntimeScalar(updatedValue.toString());
        // Preserve BYTE_STRING type: if the parent was a byte string and the replacement
        // doesn't introduce UTF-8 characters, keep the result as BYTE_STRING.
        // In Perl, substr assignment on a byte string with a byte replacement stays bytes.
        if (lvalue.type == RuntimeScalarType.BYTE_STRING &&
                (value.type == RuntimeScalarType.BYTE_STRING ||
                 value.type != RuntimeScalarType.STRING)) {
            updated.type = RuntimeScalarType.BYTE_STRING;
        }
        lvalue.set(updated);

        return this;
    }
}
