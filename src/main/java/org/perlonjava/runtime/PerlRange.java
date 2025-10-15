package org.perlonjava.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;

import static org.perlonjava.runtime.RuntimeScalarCache.scalarEmptyString;

/**
 * Represents a range of values in a Perl-like runtime environment.
 * This class supports both integer and string ranges, providing
 * an iterator to traverse through the range.
 */
public class PerlRange extends RuntimeBase implements Iterable<RuntimeScalar> {
    private final RuntimeScalar start;
    private final RuntimeScalar end;

    /**
     * Constructs a PerlRange with the specified start and end values.
     *
     * @param start The starting value of the range.
     * @param end   The ending value of the range.
     */
    public PerlRange(RuntimeScalar start, RuntimeScalar end) {
        // Special variables like $1, $2 are implemented as ScalarSpecialVariable (RuntimeBaseProxy)
        // which extends RuntimeScalar but has type=UNDEF until evaluated. We need to force
        // evaluation by accessing the value, which will call getValueAsScalar() internally.
        // We do this by checking if the scalar is a proxy and creating a proper copy.
        RuntimeScalar evalStart = start;
        RuntimeScalar evalEnd = end;
        
        // Force evaluation of special variables by creating new RuntimeScalar with the actual value
        // But only if they're defined - undef special variables should stay undef
        if (start instanceof RuntimeBaseProxy) {
            if (start.getDefinedBoolean()) {
                // Call toString() to force evaluation, then create a new RuntimeScalar
                evalStart = new RuntimeScalar(start.toString());
            } else {
                // Keep as undef
                evalStart = new RuntimeScalar();
            }
        }
        if (end instanceof RuntimeBaseProxy) {
            if (end.getDefinedBoolean()) {
                // Call toString() to force evaluation, then create a new RuntimeScalar
                evalEnd = new RuntimeScalar(end.toString());
            } else {
                // Keep as undef
                evalEnd = new RuntimeScalar();
            }
        }
        
        // Handle undef values: treat as 0 for numeric context or "" for string context
        // We'll determine context based on the other operand or default to numeric
        if (evalStart.type == RuntimeScalarType.UNDEF) {
            if (evalEnd.type != RuntimeScalarType.UNDEF && !ScalarUtils.looksLikeNumber(evalEnd)) {
                // If end is a string, treat undef as ""
                this.start = new RuntimeScalar("");
            } else {
                // Default to numeric context: treat undef as 0
                this.start = new RuntimeScalar(0);
            }
        } else {
            this.start = evalStart;
        }

        if (evalEnd.type == RuntimeScalarType.UNDEF) {
            if (this.start.type == RuntimeScalarType.STRING ||
                    (this.start.type != RuntimeScalarType.INTEGER && !ScalarUtils.looksLikeNumber(this.start))) {
                // If start is a string, treat undef as ""
                this.end = new RuntimeScalar("");
            } else {
                // Default to numeric context: treat undef as 0
                this.end = new RuntimeScalar(0);
            }
        } else {
            this.end = evalEnd;
        }
    }

    /**
     * Creates a new PerlRange instance with the given start and end values.
     *
     * @param start The starting value of the range.
     * @param end   The ending value of the range.
     * @return A new PerlRange object.
     */
    public static PerlRange createRange(RuntimeScalar start, RuntimeScalar end) {
        return new PerlRange(start, end);
    }

    /**
     * Returns an iterator over elements of type {@code RuntimeScalar}.
     * The iterator will traverse the range from start to end.
     *
     * @return An Iterator of RuntimeScalar objects.
     */
    @Override
    public Iterator<RuntimeScalar> iterator() {
        if (start.type == RuntimeScalarType.INTEGER) {
            // Use integer iterator for integer ranges
            return new PerlRangeIntegerIterator();
        }
        String startString = start.toString();
        if (ScalarUtils.looksLikeNumber(start) && ScalarUtils.looksLikeNumber(end)) {
            if (startString.length() > 1 && startString.startsWith("0")) {
                // "01" is String-like
            } else {
                return new PerlRangeIntegerIterator();
            }
        }
        // Handle string ranges with specific rules:
        // If left-hand string begins with 0 and is longer than one character,
        // If the initial value specified isn't part of a magical increment sequence (that is, a non-empty string matching /^[a-zA-Z]*[0-9]*\z/), only the initial value will be returned.
        // For example, "ax".."az" produces "ax", "ay", "az", but "*x".."az" produces only "*x".
        String endString = end.toString();
        if (startString.isEmpty()) {
            return scalarEmptyString.iterator();
        } else if (!startString.matches("^[a-zA-Z]*[0-9]*\\z")) {
            return start.iterator();
        } else if (endString.isEmpty() && !startString.isEmpty()) {
            // If end is empty string and start is not, return empty
            // Example: "B" .. "" returns empty
            return Collections.emptyIterator();
        } else if (startString.length() > endString.length()) {
            // If start is longer than end:
            // - If start > end lexicographically: return empty (e.g., "aaa" .. "--")
            // - If start <= end lexicographically: return start only (e.g., "abc" .. "z")
            if (startString.compareTo(endString) > 0) {
                return Collections.emptyIterator();
            }
            return start.iterator();
        }
        // For all other cases, use the string iterator which will handle:
        // - Normal ranges: "a" .. "z"
        // - Wraparound ranges: "09" .. "08" (continues to "99")
        // - Equal start/end: "a" .. "a" (returns just "a")
        return new PerlRangeStringIterator();
    }

    /**
     * Converts the range to an undefined state.
     *
     * @return A RuntimeBase representing the undefined state.
     */
    @Override
    public RuntimeBase undefine() {
        return toList().undefine();
    }

    /**
     * Returns a string representation of the range.
     *
     * @return A string representing the range.
     */
    @Override
    public String toString() {
        return toList().toString();
    }

    /**
     * Sets an array of aliases for the range.
     *
     * @param arr The RuntimeArray object to set aliases.
     * @return The updated RuntimeArray with aliases set.
     */
    @Override
    public RuntimeArray setArrayOfAlias(RuntimeArray arr) {
        return toList().setArrayOfAlias(arr);
    }

    /**
     * Converts the range to a list of RuntimeScalar objects.
     *
     * @return A RuntimeList representing the range.
     */
    @Override
    public RuntimeList getList() {
        return toList();
    }

    /**
     * Returns the scalar representation of the end value of the range.
     *
     * @return A RuntimeScalar representing the end value.
     */
    @Override
    public RuntimeScalar scalar() {
        return end;
    }

    /**
     * Evaluates the boolean representation of the range.
     *
     * @return A boolean indicating the truthiness of the range.
     */
    @Override
    public boolean getBoolean() {
        return this.scalar().getBoolean();
    }

    /**
     * Returns a defined boolean value for the range.
     *
     * @return Always returns true.
     */
    public boolean getDefinedBoolean() {
        return true;
    }

    /**
     * Creates a reference to the list representation of the range.
     *
     * @return A RuntimeScalar representing the reference.
     */
    @Override
    public RuntimeScalar createReference() {
        return toList().createReference();
    }

    /**
     * Adds the elements of the range to the specified array.
     *
     * @param array The RuntimeArray object to add elements to.
     */
    @Override
    public void addToArray(RuntimeArray array) {
        toList().addToArray(array);
    }

    /**
     * Counts the number of elements in the range.
     *
     * @return The number of elements in the range.
     */
    @Override
    public int countElements() {
        return toList().countElements();
    }

    /**
     * Adds the elements of the range to the specified scalar.
     *
     * @param scalar The RuntimeScalar object to add elements to.
     * @return The updated RuntimeScalar with elements added.
     */
    @Override
    public RuntimeScalar addToScalar(RuntimeScalar scalar) {
        return toList().addToScalar(scalar);
    }

    /**
     * Sets the elements of the range from the specified list.
     *
     * @param list The RuntimeList object to set elements from.
     * @return The updated RuntimeArray with elements set.
     */
    @Override
    public RuntimeArray setFromList(RuntimeList list) {
        return toList().setFromList(list);
    }

    /**
     * Retrieves the keys of the range as an array.
     *
     * @return A RuntimeArray representing the keys of the range.
     */
    @Override
    public RuntimeArray keys() {
        return toList().keys();
    }

    /**
     * Retrieves the values of the range as an array.
     *
     * @return A RuntimeArray representing the values of the range.
     */
    @Override
    public RuntimeArray values() {
        return toList().values();
    }

    /**
     * Retrieves each element of the range as a list.
     *
     * @return A RuntimeList representing each element of the range.
     */
    @Override
    public RuntimeList each(int ctx) {
        return toList().each(ctx);
    }

    /**
     * Removes the last character from each element in the range.
     *
     * @return A RuntimeScalar representing the modified range.
     */
    @Override
    public RuntimeScalar chop() {
        return toList().chop();
    }

    /**
     * Removes the newline character from the end of each element in the range.
     *
     * @return A RuntimeScalar representing the modified range.
     */
    @Override
    public RuntimeScalar chomp() {
        return toList().chomp();
    }

    /**
     * Converts the range to a list of RuntimeScalar objects.
     *
     * @return A RuntimeList representing the range.
     */
    public RuntimeList toList() {
        Iterator<RuntimeScalar> it = this.iterator();
        RuntimeList list = new RuntimeList();
        while (it.hasNext()) {
            list.add(it.next());
        }
        return list;
    }

    /**
     * Saves the current state of the instance.
     *
     * <p>This method creates a snapshot of the current value,
     * and pushes it onto a static stack for later restoration. After saving, it clears
     * the current elements and resets the value.
     *
     * @throws PerlCompilerException if the method is not implemented.
     */
    @Override
    public void dynamicSaveState() {
        throw new PerlCompilerException("not implemented: local RANGE");
    }

    /**
     * Restores the most recently saved state of the instance.
     *
     * <p>This method pops the most recent state from the static stack and restores
     * the value. If no state is saved, it does nothing.
     *
     * @throws PerlCompilerException if the method is not implemented.
     */
    @Override
    public void dynamicRestoreState() {
        throw new PerlCompilerException("not implemented: local RANGE");
    }

    /**
     * Iterator for string-based ranges.
     */
    private class PerlRangeStringIterator implements Iterator<RuntimeScalar> {
        private final String endString;
        private String current;
        private boolean hasNext;

        /**
         * Constructs a PerlRangeStringIterator for the current range.
         */
        PerlRangeStringIterator() {
            current = start.toString();
            endString = end.toString();

            // For string ranges, we always have at least the start element
            // The range continues as long as:
            // 1. Current string is shorter than end string, OR
            // 2. Current string has same length and is <= end string, OR  
            // 3. Current string is > end string but will eventually wrap around
            //    (e.g., '09' .. '08' continues through '10', '11', ..., '99', '00', ..., '08')
            // For simplicity, we start with hasNext = true and let the next() method determine when to stop
            hasNext = true;
        }

        /**
         * Checks if there are more elements in the range.
         *
         * @return true if there are more elements, false otherwise.
         */
        @Override
        public boolean hasNext() {
            return hasNext;
        }

        /**
         * Returns the next element in the range.
         *
         * @return The next RuntimeScalar in the range.
         * @throws NoSuchElementException if there are no more elements.
         */
        @Override
        public RuntimeScalar next() {
            if (!hasNext) {
                throw new NoSuchElementException();
            }
            RuntimeScalar res = new RuntimeScalar(current);

            // Check if we've reached the end
            if (current.equals(endString)) {
                hasNext = false;
            } else {
                // Increment the current string to the next in the sequence
                String next = ScalarUtils.incrementPlainString(current);

                // Perl's range behavior: continue until the next increment would make
                // the string longer than the end string.
                // Examples:
                // - '09'..'08': continues to '99' because '99'++ = '100' (3 chars > 2 chars)
                // - 'a'..'\xFF': stops at 'z' because 'z'++ = 'aa' (2 chars > 1 char)
                // - 'az'..'ba': continues to 'ba' because 'az'++ = 'ba' (same length)
                if (next.length() > endString.length()) {
                    // Next increment would be too long, stop here
                    hasNext = false;
                } else {
                    // Continue with the incremented value
                    current = next;
                }
            }
            return res;
        }
    }

    /**
     * Iterator for integer-based ranges.
     */
    private class PerlRangeIntegerIterator implements Iterator<RuntimeScalar> {
        private final int endInt;
        private int current;
        private boolean hasNext;

        /**
         * Constructs a PerlRangeIntegerIterator for the current range.
         */
        PerlRangeIntegerIterator() {
            // Check for NaN or Inf before converting to int
            if (start.type == RuntimeScalarType.DOUBLE) {
                double startDouble = start.getDouble();
                if (Double.isNaN(startDouble) || Double.isInfinite(startDouble)) {
                    throw new PerlCompilerException("Range iterator outside integer range");
                }
                // Check if the double value exceeds integer range
                if (startDouble > Integer.MAX_VALUE || startDouble < Integer.MIN_VALUE) {
                    throw new PerlCompilerException("Range iterator outside integer range");
                }
            }
            if (end.type == RuntimeScalarType.DOUBLE) {
                double endDouble = end.getDouble();
                if (Double.isNaN(endDouble) || Double.isInfinite(endDouble)) {
                    throw new PerlCompilerException("Range iterator outside integer range");
                }
                // Check if the double value exceeds integer range
                if (endDouble > Integer.MAX_VALUE || endDouble < Integer.MIN_VALUE) {
                    throw new PerlCompilerException("Range iterator outside integer range");
                }
            }

            current = start.getInt();
            endInt = end.getInt();
            hasNext = current <= endInt;
        }

        /**
         * Checks if there are more elements in the range.
         *
         * @return true if there are more elements, false otherwise.
         */
        @Override
        public boolean hasNext() {
            return hasNext;
        }

        /**
         * Returns the next element in the range.
         *
         * @return The next RuntimeScalar in the range.
         * @throws NoSuchElementException if there are no more elements.
         */
        @Override
        public RuntimeScalar next() {
            if (!hasNext) {
                throw new NoSuchElementException();
            }
            // Perl allows the value to be modified in a for-loop: `for (1..1) { $_ = "aaa"; }`
            // so we need to return a lvalue,
            // and we can't do: `getScalarInt(current)`
            RuntimeScalar result = new RuntimeScalar(current);
            if (current < endInt) {
                // Increment the current integer to the next in the sequence
                current++;
            } else {
                hasNext = false;
            }
            return result;
        }
    }
}
