package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.runtime.operators.PerlUtfString;
import org.perlonjava.runtime.operators.WarnDie;

/**
 * Represents a substring of a RuntimeScalar that can be used as an lvalue (left-hand value).
 * This class allows for modification of a specific portion of a string within a RuntimeScalar.
 */
public class RuntimeSubstrLvalue extends RuntimeBaseProxy {
    /**
     * The starting position of the substring within the parent string.
     */
    private int offset;

    /**
     * The length of the substring.
     */
    private int length;

    /** True when the original two-argument substr extended to string end. */
    private final boolean toEnd;

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
        this(parent, str, offset, length, false);
    }

    public RuntimeSubstrLvalue(
            RuntimeScalar parent, String str, int offset, int length, boolean toEnd) {
        this.lvalue = parent;
        this.offset = offset;
        this.length = length;
        this.toEnd = toEnd;
        this.outOfBounds = false;

        // Preserve BYTE_STRING type from parent so substr() on byte strings stays byte
        this.type = (parent.type == RuntimeScalarType.BYTE_STRING)
                ? RuntimeScalarType.BYTE_STRING : RuntimeScalarType.STRING;
        this.value = str;
        this.tainted = parent.isTainted();
        parent.registerSubstrLvalue(this);
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
        return setInternal(value, null);
    }

    /** Four-argument substr has already stringified its target once. */
    public RuntimeScalar setUsingParentSnapshot(RuntimeScalar value, String parentSnapshot) {
        return setInternal(value, parentSnapshot);
    }

    private RuntimeScalar setInternal(RuntimeScalar value, String parentSnapshot) {
        // Die on assignment if the original substr was out of bounds
        if (outOfBounds) {
            WarnDie.die(new RuntimeScalar("substr outside of string"),
                    RuntimeScalarCache.scalarEmptyString);
            return this;
        }

        // Update the local type and value
        String parentValue = parentSnapshot != null ? parentSnapshot : lvalue.toString();
        String newValue = value.toString();
        this.type = value.type;
        this.value = value.value;
        int strLength = PerlUtfString.codePointCountPerl(parentValue);

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
        int actualLength = toEnd ? strLength - actualOffset : length;
        if (!toEnd && length < 0) {
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

        // Convert Perl logical offsets to UTF-16 indices for StringBuilder operations
        int startIndex = PerlUtfString.offsetByPerlCodePoints(parentValue, 0, actualOffset);
        int endIndex = PerlUtfString.offsetByPerlCodePoints(parentValue, startIndex, actualLength);

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
        // Assignment through substr is an in-place mutation.  Perl preserves
        // existing taint on the target and also propagates taint from the
        // replacement value.
        if (GlobalContext.isTaintModeActive()) {
            // The proxy cached its parent's provenance when it was created;
            // consulting the tied parent again here would perform another
            // FETCH for the same lvalue operation.
            updated.tainted = this.tainted || value.isTainted();
        }
        // Preserve BYTE_STRING type: if the parent was a byte string and the replacement
        // doesn't introduce UTF-8 characters, keep the result as BYTE_STRING.
        // In Perl, substr assignment on a byte string with a byte replacement stays bytes.
        if (lvalue.type == RuntimeScalarType.BYTE_STRING &&
                (value.type == RuntimeScalarType.BYTE_STRING ||
                 value.type != RuntimeScalarType.STRING)) {
            updated.type = RuntimeScalarType.BYTE_STRING;
        }
        // This proxy already updates its own cached extent below. Refreshes
        // triggered by RuntimeScalar.set() would read a tied parent again,
        // adding spurious FETCH calls for a single substr assignment.
        if (lvalue instanceof RuntimeSubstrLvalue substrParent) {
            // Nested lvalue substrings must continue propagating writes through
            // the outer proxy to the original scalar.
            substrParent.set(updated);
        } else if (lvalue.type == RuntimeScalarType.TIED_SCALAR) {
            // Avoid refreshing this observer through FETCH after STORE. Perl
            // performs only the single FETCH used to construct the lvalue.
            lvalue.setFromSubstrLvalue(updated);
        } else {
            // Preserve virtual write-back for magical parents such as $#array
            // and lvalue subroutine results.
            lvalue.set(updated);
        }

        // The lvalue's extent follows the replacement.  A later assignment
        // through the same alias replaces the text inserted by this one, not
        // the original number of characters.
        int replacementLength = PerlUtfString.codePointCountPerl(newValue);
        if (this.offset < 0) {
            // Negative-offset aliases stay anchored to the same suffix of the
            // parent.  If the replacement changes width, shift the negative
            // start by the opposite delta so that suffix length remains fixed.
            this.offset += actualLength - replacementLength;
        }
        if (!toEnd && this.length >= 0) {
            this.length = replacementLength;
        }
        this.type = value.type;
        this.value = newValue;
        this.tainted = updated.tainted;

        return this;
    }

    /**
     * Reads through the live alias instead of returning the substring cached
     * when substr() was first called.
     */
    @Override
    public String toString() {
        if (outOfBounds || lvalue == null) {
            return super.toString();
        }

        return currentSubstring();
    }

    void refreshFromParent() {
        if (outOfBounds || lvalue == null) return;
        this.type = lvalue.type == RuntimeScalarType.BYTE_STRING
                ? RuntimeScalarType.BYTE_STRING : RuntimeScalarType.STRING;
        this.value = currentSubstring();
    }

    private String currentSubstring() {

        String parentValue = lvalue.toString();
        int strLength = PerlUtfString.codePointCountPerl(parentValue);
        int actualOffset = offset < 0 ? strLength + offset : offset;
        actualOffset = Math.max(0, Math.min(actualOffset, strLength));

        int actualLength = toEnd
                ? strLength - actualOffset
                : length < 0 ? strLength + length - actualOffset : length;
        actualLength = Math.max(0, Math.min(actualLength, strLength - actualOffset));

        int startIndex = PerlUtfString.offsetByPerlCodePoints(parentValue, 0, actualOffset);
        int endIndex = PerlUtfString.offsetByPerlCodePoints(parentValue, startIndex, actualLength);
        return parentValue.substring(startIndex, endIndex);
    }
}
