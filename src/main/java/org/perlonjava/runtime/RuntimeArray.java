package org.perlonjava.runtime;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Stack;

import static org.perlonjava.runtime.RuntimeScalarCache.*;
import static org.perlonjava.runtime.RuntimeScalarType.TIED_SCALAR;

/**
 * The RuntimeArray class simulates Perl arrays.
 *
 * <p>In Perl, an array is a dynamic list of scalar values. This class tries to mimic this behavior
 * using a list of RuntimeScalar objects, which can hold any type of Perl scalar value.
 */
public class RuntimeArray extends RuntimeBase implements RuntimeScalarReference, DynamicState {

    public static final int PLAIN_ARRAY = 0;
    public static final int AUTOVIVIFY_ARRAY = 1;
    public static final int TIED_ARRAY = 2;
    // Static stack to store saved "local" states of RuntimeArray instances
    private static final Stack<RuntimeArray> dynamicStateStack = new Stack<>();
    // Internal type of array - PLAIN_ARRAY, AUTOVIVIFY_ARRAY, or TIED_ARRAY
    public int type;
    // List to hold the elements of the array.
    public List<RuntimeScalar> elements;
    // Iterator for traversing the hash elements
    private Integer eachIteratorIndex;


    // Constructor
    public RuntimeArray() {
        type = PLAIN_ARRAY;
        elements = new ArrayList<>();
    }

    /**
     * Constructs a RuntimeArray from a list of RuntimeScalar elements.
     *
     * <p>This constructor initializes the array with the elements provided in the list.
     * It creates a new ArrayList to ensure the internal list is mutable.
     *
     * @param list The list of RuntimeScalar elements to initialize the array with.
     */
    public RuntimeArray(List<RuntimeScalar> list) {
        this.elements = new ArrayList<>(list);
    }

    public RuntimeArray(RuntimeBase... values) {
        this.elements = new ArrayList<>();
        for (RuntimeBase value : values) {
            for (RuntimeScalar runtimeScalar : value) {
                this.elements.add(runtimeScalar);
            }
        }
    }

    /**
     * Constructs a RuntimeArray from a RuntimeList.
     *
     * @param a The RuntimeList to initialize the array with.
     */
    public RuntimeArray(RuntimeList a) {
        this.elements = new ArrayList<>();
        for (RuntimeScalar runtimeScalar : a) {
            this.elements.add(runtimeScalar);
        }
    }

    /**
     * Constructs a RuntimeArray with a single scalar value.
     *
     * @param value The initial scalar value for the array.
     */
    public RuntimeArray(RuntimeScalar value) {
        this.elements = new ArrayList<>();
        this.elements.add(value);
    }

    /**
     * Removes and returns the last value of the array.
     *
     * @param runtimeArray The array to pop the last value from
     * @return The last value of the array, or undefined if empty.
     */
    public static RuntimeScalar pop(RuntimeArray runtimeArray) {
        return switch (runtimeArray.type) {
            case PLAIN_ARRAY -> {
                if (runtimeArray.isEmpty()) {
                    yield new RuntimeScalar(); // Return undefined if empty
                }
                yield runtimeArray.elements.removeLast();
            }
            case AUTOVIVIFY_ARRAY -> {
                AutovivificationArray.vivify(runtimeArray);
                yield pop(runtimeArray); // Recursive call after vivification
            }
            case TIED_ARRAY -> TieArray.tiedPop(runtimeArray);
            default -> throw new IllegalStateException("Unknown array type: " + runtimeArray.type);
        };
    }

    /**
     * Removes and returns the first value of the array.
     *
     * @param runtimeArray The array to shift the first value from
     * @return The first value of the array, or undefined if empty.
     */
    public static RuntimeScalar shift(RuntimeArray runtimeArray) {
        return switch (runtimeArray.type) {
            case PLAIN_ARRAY -> {
                if (runtimeArray.isEmpty()) {
                    yield new RuntimeScalar(); // Return undefined if empty
                }
                yield runtimeArray.elements.removeFirst();
            }
            case AUTOVIVIFY_ARRAY -> {
                AutovivificationArray.vivify(runtimeArray);
                yield shift(runtimeArray); // Recursive call after vivification
            }
            case TIED_ARRAY -> TieArray.tiedShift(runtimeArray);
            default -> throw new IllegalStateException("Unknown array type: " + runtimeArray.type);
        };
    }

    /**
     * Gets the index of the last element in the array.
     *
     * @param runtimeArray The array to get the last index from
     * @return A RuntimeScalar lvalue containing the integer index of the last element, or -1 if the array is empty
     */
    public static RuntimeScalar indexLastElem(RuntimeArray runtimeArray) {
        if (runtimeArray.type == AUTOVIVIFY_ARRAY) {
            AutovivificationArray.vivify(runtimeArray);
        }
        return new RuntimeArraySizeLvalue(runtimeArray);
    }

    /**
     * Adds values to the end of the array.
     *
     * @param runtimeArray The array to add values to.
     * @param value        The values to add.
     * @return A scalar representing the new size of the array.
     */
    public static RuntimeScalar push(RuntimeArray runtimeArray, RuntimeBase value) {
        return switch (runtimeArray.type) {
            case PLAIN_ARRAY -> {
                value.addToArray(runtimeArray);
                yield getScalarInt(runtimeArray.elements.size());
            }
            case AUTOVIVIFY_ARRAY -> {
                AutovivificationArray.vivify(runtimeArray);
                yield push(runtimeArray, value);
            }
            case TIED_ARRAY -> TieArray.tiedPush(runtimeArray, value);
            default -> throw new IllegalStateException("Unknown array type: " + runtimeArray.type);
        };
    }

    /**
     * Adds values to the beginning of the array.
     *
     * @param runtimeArray The array to add values to.
     * @param value        The values to add.
     * @return A scalar representing the new size of the array.
     */
    public static RuntimeScalar unshift(RuntimeArray runtimeArray, RuntimeBase value) {
        return switch (runtimeArray.type) {
            case PLAIN_ARRAY -> {
                RuntimeArray arr = new RuntimeArray();
                RuntimeArray.push(arr, value);
                runtimeArray.elements.addAll(0, arr.elements);
                yield getScalarInt(runtimeArray.elements.size());
            }
            case AUTOVIVIFY_ARRAY -> {
                AutovivificationArray.vivify(runtimeArray);
                yield unshift(runtimeArray, value);
            }
            case TIED_ARRAY -> TieArray.tiedUnshift(runtimeArray, value);
            default -> throw new IllegalStateException("Unknown array type: " + runtimeArray.type);
        };
    }

    public RuntimeScalar shift() {
        return shift(this);
    }

    public RuntimeScalar push(RuntimeBase value) {
        return push(this, value);
    }

    /**
     * Adds the elements of this array to another RuntimeArray.
     *
     * @param array The RuntimeArray to which elements will be added.
     */
    public void addToArray(RuntimeArray array) {
        if (this.type == AUTOVIVIFY_ARRAY) {
            throw new PerlCompilerException("Can't use an undefined value as an ARRAY reference");
        }

        // Create a defensive copy to avoid ConcurrentModificationException
        List<RuntimeScalar> elementsCopy = new ArrayList<>(this.elements);
        List<RuntimeScalar> targetElements = array.elements;

        for (RuntimeScalar arrElem : elementsCopy) {
            // targetElements.add(new RuntimeScalar(arrElem));
            RuntimeScalar v = new RuntimeScalar();
            arrElem.addToScalar(v);
            targetElements.add(v);
        }
    }

    // Methods used by array literal constructor
    public void add(RuntimeBase value) {
        value.addToArray(this);
    }

    public void add(RuntimeScalar value) {
        elements.add(new RuntimeScalar(value));
    }

    public void add(RuntimeArray value) {
        value.addToArray(this);
    }

    public void add(RuntimeHash value) {
        value.addToArray(this);
    }

    public void add(RuntimeList value) {
        value.addToArray(this);
    }

    /**
     * Adds this array to a RuntimeList.
     *
     * @param list The RuntimeList to which elements will be added.
     */
    public void addToList(RuntimeList list) {
        list.add(this);
    }

    /**
     * Returns the number of elements in the array.
     *
     * @return The size of the array.
     */
    public int countElements() {
        return elements.size();
    }

    public boolean isEmpty() {
        return elements.isEmpty();
    }

    /**
     * Adds the size of the array to a RuntimeScalar.
     *
     * @param scalar The RuntimeScalar object.
     * @return The scalar with the size of the array set.
     */
    public RuntimeScalar addToScalar(RuntimeScalar scalar) {
        return scalar.set(this.size());
    }

    /**
     * Checks if a specific index exists.
     *
     * @param index The index of the value to retrieve.
     * @return Perl value True or False.
     */
    public RuntimeScalar exists(int index) {
        return switch (type) {
            case PLAIN_ARRAY -> {
                if (index < 0) {
                    index = elements.size() + index; // Handle negative indices
                }
                if (index < 0 || index >= elements.size()) {
                    yield scalarFalse;
                }
                // Check if the element at index is null
                RuntimeScalar element = elements.get(index);
                yield (element == null) ? scalarFalse : scalarTrue;
            }
            case AUTOVIVIFY_ARRAY -> scalarFalse;
            case TIED_ARRAY -> TieArray.tiedExists(this, getScalarInt(index));
            default -> throw new IllegalStateException("Unknown array type: " + type);
        };
    }

    public RuntimeScalar exists(RuntimeScalar index) {
        return this.exists(index.getInt());
    }

    /**
     * Delete an array element.
     *
     * @param index The index of the value to delete.
     * @return The value deleted.
     */
    public RuntimeScalar delete(int index) {
        return switch (type) {
            case PLAIN_ARRAY -> {
                if (index < 0) {
                    index = elements.size() + index; // Handle negative indices
                }
                if (index < 0 || index >= elements.size()) {
                    yield scalarUndef;
                }
                if (index == elements.size() - 1) {
                    yield pop(this);
                }
                RuntimeScalar previous = this.get(index);
                this.elements.set(index, null);
                yield previous;
            }
            case AUTOVIVIFY_ARRAY -> scalarUndef;
            case TIED_ARRAY -> TieArray.tiedDelete(this, getScalarInt(index));
            default -> throw new IllegalStateException("Unknown array type: " + type);
        };
    }

    /**
     * Deletes multiple array elements at the specified indices.
     *
     * @param indices The list of indices to delete.
     * @return A RuntimeList containing the deleted values.
     */
    public RuntimeList deleteSlice(RuntimeList indices) {
        // Collect all indices and their values first (to preserve order)
        List<Integer> indexList = new ArrayList<>();
        for (RuntimeScalar indexScalar : indices) {
            indexList.add(indexScalar.getInt());
        }

        // Create result list to store deleted values in original order
        RuntimeList result = new RuntimeList();

        // First pass: collect values in original order
        for (Integer index : indexList) {
            RuntimeScalar value = this.get(index);
            result.elements.add(value.getDefinedBoolean() ? new RuntimeScalar(value) : scalarUndef);
        }

        // Sort indices in descending order for deletion
        List<Integer> sortedIndices = new ArrayList<>(indexList);
        sortedIndices.sort((a, b) -> b.compareTo(a)); // Sort descending

        // Second pass: delete elements starting from highest index
        for (Integer index : sortedIndices) {
            this.delete(index);
        }

        return result;
    }

    public RuntimeScalar delete(RuntimeScalar index) {
        return this.delete(index.getInt());
    }

    /**
     * Gets a value at a specific index.
     *
     * @param index The index of the value to retrieve.
     * @return The value at the specified index, or a proxy if out of bounds.
     */
    public RuntimeScalar get(int index) {

        if (this.type == TIED_ARRAY) {
            return get(new RuntimeScalar(index));
        }

        if (index < 0) {
            index = elements.size() + index; // Handle negative indices
        }
        if (index < 0 || index >= elements.size()) {
            // Lazy autovivification
            return new RuntimeArrayProxyEntry(this, index);
        }

        // Check if the element is null and return proxy if it is
        RuntimeScalar element = elements.get(index);
        if (element == null) {
            // Lazy autovivification for null elements
            return new RuntimeArrayProxyEntry(this, index);
        }

        return element;
    }

    /**
     * Gets a value at a specific index using a scalar.
     *
     * @param value The scalar representing the index.
     * @return The value at the specified index, or a proxy if out of bounds.
     */
    public RuntimeScalar get(RuntimeScalar value) {

        if (this.type == TIED_ARRAY) {
            RuntimeScalar v = new RuntimeScalar();
            v.type = TIED_SCALAR;
            v.value = new RuntimeTiedArrayProxyEntry(this, value);
            return v;
        }

        int index = value.getInt();
        if (index < 0) {
            index = elements.size() + index; // Handle negative indices
        }
        if (index < 0 || index >= elements.size()) {
            // Lazy autovivification
            return new RuntimeArrayProxyEntry(this, index);
        }

        // Check if the element is null and return proxy if it is
        RuntimeScalar element = elements.get(index);
        if (element == null) {
            // Lazy autovivification for null elements
            return new RuntimeArrayProxyEntry(this, index);
        }

        return element;
    }

    /**
     * Sets the whole array to a single scalar value.
     *
     * @param value The scalar value to set.
     * @return The updated RuntimeArray.
     */
    public RuntimeArray set(RuntimeScalar value) {
        this.elements.clear();
        this.elements.add(value);
        return this;
    }

    /**
     * Replaces the whole array with the elements of a list.
     *
     * @param value The list to set.
     * @return The updated RuntimeArray.
     */
    public RuntimeArray setFromList(RuntimeList value) {
        return switch (type) {
            case PLAIN_ARRAY -> {
                this.elements.clear();
                value.addToArray(this);
                yield this;
            }
            case AUTOVIVIFY_ARRAY -> {
                AutovivificationArray.vivify(this);
                yield this.setFromList(value);
            }
            case TIED_ARRAY -> {
                TieArray.tiedClear(this);
                for (RuntimeScalar runtimeScalar : value) {
                    TieArray.tiedPush(this, runtimeScalar);
                }
                yield this;
            }
            default -> throw new IllegalStateException("Unknown array type: " + type);
        };
    }

    /**
     * Creates a reference to the array.
     *
     * @return A scalar representing the array reference.
     */
    public RuntimeScalar createReference() {
        RuntimeScalar result = new RuntimeScalar();
        result.type = RuntimeScalarType.ARRAYREFERENCE;
        result.value = this;
        return result;
    }

    /**
     * Gets the size of the array.
     *
     * @return The size of the array.
     */
    public int size() {
        return elements.size();
    }

    /**
     * Evaluates the boolean representation of the array.
     *
     * @return True if the array is not empty.
     */
    public boolean getBoolean() {
        return !elements.isEmpty();
    }

    /**
     * Checks if the array is defined.
     *
     * @return Always true for arrays.
     */
    public boolean getDefinedBoolean() {
        return true;
    }

    /**
     * Gets the list value of the array.
     *
     * @return A RuntimeList representing the array.
     */
    public RuntimeList getList() {
        return new RuntimeList(this);
    }

    /**
     * Gets the scalar value of the array.
     *
     * @return A scalar representing the size of the array.
     */
    public RuntimeScalar scalar() {
        return switch (type) {
            case PLAIN_ARRAY -> getScalarInt(elements.size());
            case AUTOVIVIFY_ARRAY ->
                    throw new PerlCompilerException("Can't use an undefined value as an ARRAY reference");
            case TIED_ARRAY -> TieArray.tiedFetchSize(this);
            default -> throw new IllegalStateException("Unknown array type: " + type);
        };
    }

    // implement `$#array`
    public int lastElementIndex() {
        return switch (type) {
            case PLAIN_ARRAY -> elements.size() - 1;
            case AUTOVIVIFY_ARRAY ->
                    throw new PerlCompilerException("Can't use an undefined value as an ARRAY reference");
            case TIED_ARRAY -> TieArray.tiedFetchSize(this).getInt() - 1;
            default -> throw new IllegalStateException("Unknown array type: " + type);
        };
    }

    public void setLastElementIndex(RuntimeScalar value) {
        switch (type) {
            case PLAIN_ARRAY -> {
                int newSize = value.getInt();
                if (newSize < -1) newSize = -1;
                int currentSize = elements.size() - 1;

                // Update the parent with the new size
                if (newSize > currentSize) {
                    for (int i = currentSize; i < newSize; i++) {
                        elements.add(null); // Fill with undefined values if necessary
                    }
                } else {
                    while (newSize < currentSize) {
                        currentSize--;
                        elements.removeLast();
                    }
                }
            }
            case AUTOVIVIFY_ARRAY -> {
                AutovivificationArray.vivify(this);
                setLastElementIndex(value);
            }
            case TIED_ARRAY -> TieArray.tiedStoreSize(this, new RuntimeScalar(value.getInt() + 1));
            default -> throw new IllegalStateException("Unknown array type: " + type);
        }
    }

    /**
     * Slices the array using a list of indices.
     *
     * @param value The list of indices to slice.
     * @return A RuntimeList representing the sliced elements.
     */
    public RuntimeList getSlice(RuntimeList value) {

        if (this.type == AUTOVIVIFY_ARRAY) {
            AutovivificationArray.vivify(this);
        }

        RuntimeList result = new RuntimeList();
        List<RuntimeBase> outElements = result.elements;
        for (RuntimeScalar runtimeScalar : value) {
            outElements.add(this.get(runtimeScalar));
        }
        return result;
    }

    /**
     * Gets the keys of the array.
     *
     * @return A RuntimeArray representing the keys.
     */
    public RuntimeArray keys() {
        return new PerlRange(getScalarInt(0), getScalarInt(this.countElements())).getArrayOfAlias();
    }

    /**
     * Gets the values of the array.
     *
     * @return This RuntimeArray.
     */
    public RuntimeArray values() {
        return this;
    }

    /**
     * The each() operator for arrays.
     *
     * @return A RuntimeList containing the next index-value pair, or an empty list if the iterator is exhausted.
     */
    public RuntimeList each(int ctx) {
        if (this.type == AUTOVIVIFY_ARRAY) {
            AutovivificationArray.vivify(this);
        }

        // Initialize iterator if needed
        if (eachIteratorIndex == null) {
            eachIteratorIndex = 0;
        }

        // Check if we have more elements
        if (eachIteratorIndex < elements.size()) {
            int currentIndex = eachIteratorIndex;
            RuntimeScalar indexScalar = getScalarInt(currentIndex);

            // Get the value at current index
            RuntimeScalar element = elements.get(currentIndex);
            RuntimeScalar value = (element == null)
                    ? new RuntimeArrayProxyEntry(this, currentIndex)
                    : element;

            // Move to next position
            eachIteratorIndex++;

            return new RuntimeList(indexScalar, value);
        }

        // Reset iterator when exhausted
        eachIteratorIndex = null;
        return new RuntimeList();
    }

    /**
     * Sets array aliases into another array.
     *
     * @param arr The array to set aliases into.
     * @return The updated array with aliases.
     */
    public RuntimeArray setArrayOfAlias(RuntimeArray arr) {
        if (this.type == TIED_ARRAY) {
            // For tied arrays, we need to fetch each element through the tied interface
            int size = this.size();  // This will call FETCHSIZE
            for (int i = 0; i < size; i++) {
                RuntimeScalar element = this.get(i);  // This will call FETCH
                arr.elements.add(element);
            }
            return arr;
        }

        if (this.type == AUTOVIVIFY_ARRAY) {
            AutovivificationArray.vivify(this);
        }

        arr.elements.addAll(this.elements);
        return arr;
    }

    /**
     * Returns an iterator for the array.
     *
     * @return An iterator over the elements of the array.
     */
    public Iterator<RuntimeScalar> iterator() {

        if (this.type == AUTOVIVIFY_ARRAY) {
            AutovivificationArray.vivify(this);
        }

        return new RuntimeArrayIterator();
    }

    /**
     * Returns a string representation of the array reference.
     *
     * @return A string representing the array reference.
     */
    public String toStringRef() {
        String ref = "ARRAY(0x" + Integer.toHexString(this.hashCode()) + ")";
        return (blessId == 0
                ? ref
                : NameNormalizer.getBlessStr(blessId) + "=" + ref);
    }

    /**
     * Returns the integer representation of the array reference.
     *
     * @return The hash code of the array.
     */
    public int getIntRef() {
        return this.hashCode();
    }

    /**
     * Returns the double representation of the array reference.
     *
     * @return The hash code of the array.
     */
    public double getDoubleRef() {
        return this.hashCode();
    }

    /**
     * Evaluates the boolean representation of the array reference.
     *
     * @return Always true for array references.
     */
    public boolean getBooleanRef() {
        return true;
    }

    /**
     * Undefines the array by clearing all elements.
     *
     * @return The updated RuntimeArray after undefining.
     */
    public RuntimeArray undefine() {
        this.elements.clear();
        return this;
    }

    /**
     * Removes the last character from each element in the list.
     *
     * @return A scalar representing the result of the chop operation.
     */
    public RuntimeScalar chop() {
        RuntimeScalar result = new RuntimeScalar("");
        Iterator<RuntimeScalar> iterator = this.iterator();
        while (iterator.hasNext()) {
            result = iterator.next().chop();
        }
        return result;
    }

    /**
     * Removes the trailing newline from each element in the list.
     *
     * @return A scalar representing the result of the chomp operation.
     */
    public RuntimeScalar chomp() {
        RuntimeScalar result = new RuntimeScalar("");
        Iterator<RuntimeScalar> iterator = this.iterator();
        while (iterator.hasNext()) {
            result = iterator.next().chomp();
        }
        return result;
    }

    /**
     * Converts the array to a string, concatenating all elements without separators.
     *
     * @return A string representation of the array.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (RuntimeBase element : elements) {
            sb.append(element.toString());
        }
        return sb.toString();
    }

    /**
     * Saves the current state of the RuntimeArray instance.
     *
     * <p>This method creates a snapshot of the current elements and blessId of the array,
     * and pushes it onto a static stack for later restoration. After saving, it clears
     * the current elements and resets the blessId.
     */
    @Override
    public void dynamicSaveState() {
        // Create a new RuntimeArray to save the current state
        RuntimeArray currentState = new RuntimeArray();
        // Copy the current elements to the new state
        currentState.elements = new ArrayList<>(this.elements);
        // Copy the current blessId to the new state
        currentState.blessId = this.blessId;
        // Push the current state onto the stack
        dynamicStateStack.push(currentState);
        // Clear the array elements
        this.elements.clear();
        // Reset the blessId
        this.blessId = 0;
    }

    /**
     * Restores the most recently saved state of the RuntimeArray instance.
     *
     * <p>This method pops the most recent state from the static stack and restores
     * the elements and blessId to the current array. If no state is saved, it does nothing.
     */
    @Override
    public void dynamicRestoreState() {
        if (!dynamicStateStack.isEmpty()) {
            // Pop the most recent saved state from the stack
            RuntimeArray previousState = dynamicStateStack.pop();
            // Restore the elements from the saved state
            this.elements = previousState.elements;
            // Restore the blessId from the saved state
            this.blessId = previousState.blessId;
        }
    }

    /**
     * Inner class implementing the Iterator interface for RuntimeArray.
     */
    private class RuntimeArrayIterator implements Iterator<RuntimeScalar> {
        private final int size = elements.size();
        private int currentIndex = 0;

        @Override
        public boolean hasNext() {
            return currentIndex < size;
        }

        @Override
        public RuntimeScalar next() {
            if (!hasNext()) {
                throw new IllegalStateException("No such element in iterator.next()");
            }
            RuntimeScalar element = elements.get(currentIndex);
            currentIndex++;

            // Return a proxy entry if the element is null
            if (element == null) {
                return new RuntimeArrayProxyEntry(RuntimeArray.this, currentIndex - 1);
            }

            return element;
        }

        @Override
        public void remove() {
            if (currentIndex <= 0) {
                throw new IllegalStateException("next() has not been called yet");
            }
            elements.remove(--currentIndex);
        }
    }
}
