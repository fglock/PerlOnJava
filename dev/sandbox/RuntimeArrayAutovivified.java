package org.perlonjava.runtime;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Stack;

import static org.perlonjava.runtime.RuntimeScalarCache.getScalarInt;

/**
 * The RuntimeArray class simulates Perl arrays that are autovivified by dereferencing an undefined scalar.
 */
public class RuntimeArrayAutovivified extends RuntimeArray {

    /**
     * The RuntimeScalar that should be autovivified when this array is modified.
     * This refers to the scalar variable that holds the reference to this array.
     */
    public RuntimeScalar scalarToAutovivify;

    public void vivify(RuntimeArray array) {
        // Trigger autovivification: Convert the undefined scalar to an array reference.
        // This happens when code like @$undef_scalar = (...) is executed.
        // The AutovivificationArray was created when the undefined scalar was first
        // dereferenced as an array, and now we complete the autovivification by
        // setting the scalar's type to ARRAYREFERENCE and its value to this array.
        scalarToAutovivify.value = array;
        scalarToAutovivify.type = RuntimeScalarType.ARRAYREFERENCE;
    }

    /**
     * Constructs a RuntimeArray from a RuntimeList.
     *
     * @param a The RuntimeList to initialize the array with.
     */
    public RuntimeArrayAutovivified(RuntimeList a) {
        this.elements = new ArrayList<>();
        Iterator<RuntimeScalar> iterator = a.iterator();
        while (iterator.hasNext()) {
            this.elements.add(iterator.next());
        }
    }

    /**
     * Constructs a RuntimeArray with a single scalar value.
     *
     * @param value The initial scalar value for the array.
     */
    public RuntimeArrayAutovivified(RuntimeScalar value) {
        this.elements = new ArrayList<>();
        this.elements.add(value);
    }

    /**
     * Removes and returns the last value of the array.
     *
     * @param runtimeArray The array to pop the last value from
     * @return The last value of the array, or undefined if empty.
     */
    public static RuntimeScalar pop(RuntimeArrayAutovivified runtimeArray) {

        if (runtimeArray.elements instanceof AutovivificationArray arrayProxy) {
            arrayProxy.vivify(runtimeArray);
        }

        if (runtimeArray.isEmpty()) {
            return new RuntimeScalar(); // Return undefined if empty
        }
        return runtimeArray.elements.removeLast();
    }

    /**
     * Removes and returns the first value of the array.
     *
     * @param runtimeArray The array to shift the first value from
     * @return The first value of the array, or undefined if empty.
     */
    public static RuntimeScalar shift(RuntimeArrayAutovivified runtimeArray) {

        if (runtimeArray.elements instanceof AutovivificationArray arrayProxy) {
            arrayProxy.vivify(runtimeArray);
        }

        if (runtimeArray.isEmpty()) {
            return new RuntimeScalar(); // Return undefined if empty
        }
        return runtimeArray.elements.removeFirst();
    }

    /**
     * Gets the index of the last element in the array.
     *
     * @param runtimeArray The array to get the last index from
     * @return A RuntimeScalar containing the integer index of the last element, or -1 if the array is empty
     */
    public static RuntimeScalar indexLastElem(RuntimeArrayAutovivified runtimeArray) {
        return getScalarInt(runtimeArray.elements.size() - 1);
    }

    /**
     * Adds values to the end of the array.
     *
     * @param runtimeArray The array to add values to.
     * @param value        The values to add.
     * @return A scalar representing the new size of the array.
     */
    public static RuntimeScalar push(RuntimeArrayAutovivified runtimeArray, RuntimeDataProvider value) {

        if (runtimeArray.elements instanceof AutovivificationArray arrayProxy) {
            arrayProxy.vivify(runtimeArray);
        }

        value.addToArray(runtimeArray);
        return getScalarInt(runtimeArray.elements.size());
    }

    /**
     * Adds values to the beginning of the array.
     *
     * @param runtimeArray The array to add values to.
     * @param value        The values to add.
     * @return A scalar representing the new size of the array.
     */
    public static RuntimeScalar unshift(RuntimeArrayAutovivified runtimeArray, RuntimeDataProvider value) {
        arrayProxy.vivify(runtimeArray);
        return super.unshift(runtimeArray);
    }

    /**
     * Adds the elements of this array to another RuntimeArray.
     *
     * @param array The RuntimeArray to which elements will be added.
     */
    public void addToArray(RuntimeArrayAutovivified array) {

        if (this.elements instanceof AutovivificationArray) {
            throw new PerlCompilerException("Can't use an undefined value as an ARRAY reference");
        }

        List<RuntimeScalar> elements = array.elements;
        for (RuntimeScalar arrElem : this.elements) {
            elements.add(new RuntimeScalar(arrElem));
        }
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
     * Gets a value at a specific index.
     *
     * @param index The index of the value to retrieve.
     * @return The value at the specified index, or a proxy if out of bounds.
     */
    public RuntimeScalar get(int index) {
        if (index < 0) {
            index = elements.size() + index; // Handle negative indices
        }
        if (index < 0 || index >= elements.size()) {
            // Lazy autovivification
            return new RuntimeArrayProxyEntry(this, index);
        }
        return elements.get(index);
    }

    /**
     * Gets a value at a specific index using a scalar.
     *
     * @param value The scalar representing the index.
     * @return The value at the specified index, or a proxy if out of bounds.
     */
    public RuntimeScalar get(RuntimeScalar value) {
        int index = value.getInt();
        if (index < 0) {
            index = elements.size() + index; // Handle negative indices
        }
        if (index < 0 || index >= elements.size()) {
            // Lazy autovivification
            return new RuntimeArrayProxyEntry(this, index);
        }
        return elements.get(index);
    }

    /**
     * Sets the whole array to a single scalar value.
     *
     * @param value The scalar value to set.
     * @return The updated RuntimeArray.
     */
    public RuntimeArrayAutovivified set(RuntimeScalar value) {
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
    public RuntimeArrayAutovivified setFromList(RuntimeList value) {

        if (this.elements instanceof AutovivificationArray arrayProxy) {
            arrayProxy.vivify(this);
        }

        this.elements = new ArrayList<>();
        value.addToArray(this);
        return this;
    }

    /**
     * Sets a value at a specific index.
     *
     * @param index The index to set the value at.
     * @param value The value to set.
     */
    public void set(int index, RuntimeScalar value) {
        if (index < 0) {
            index = elements.size() + index; // Handle negative indices
        }
        if (index < 0 || index >= elements.size()) {
            for (int i = elements.size(); i <= index; i++) {
                elements.add(new RuntimeScalar()); // Fill with undefined values if necessary
            }
        }
        elements.set(index, value);
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

        if (this.elements instanceof AutovivificationArray) {
            throw new PerlCompilerException("Can't use an undefined value as an ARRAY reference");
        }

        return getScalarInt(elements.size());
    }

    /**
     * Slices the array using a list of indices.
     *
     * @param value The list of indices to slice.
     * @return A RuntimeList representing the sliced elements.
     */
    public RuntimeList getSlice(RuntimeList value) {

        if (this.elements instanceof AutovivificationArray arrayProxy) {
            arrayProxy.vivify(this);
        }

        RuntimeList result = new RuntimeList();
        List<RuntimeBaseEntity> outElements = result.elements;
        Iterator<RuntimeScalar> iterator = value.iterator();
        while (iterator.hasNext()) {
            outElements.add(this.get(iterator.next()));
        }
        return result;
    }

    /**
     * Gets the keys of the array.
     *
     * @return A RuntimeArray representing the keys.
     */
    public RuntimeArrayAutovivified keys() {
        return new PerlRange(getScalarInt(0), getScalarInt(this.countElements())).getArrayOfAlias();
    }

    /**
     * Gets the values of the array.
     *
     * @return This RuntimeArray.
     */
    public RuntimeArrayAutovivified values() {
        return this;
    }

    /**
     * Throws an exception as the 'each' operation is not implemented for arrays.
     *
     * @throws RuntimeException Always thrown as 'each' is not implemented.
     */
    public RuntimeList each() {
        throw new RuntimeException("each not implemented for Array");
    }

    /**
     * Sets array aliases into another array.
     *
     * @param arr The array to set aliases into.
     * @return The updated array with aliases.
     */
    public RuntimeArrayAutovivified setArrayOfAlias(RuntimeArrayAutovivified arr) {
        arr.elements.addAll(this.elements);
        return arr;
    }

    /**
     * Returns an iterator for the array.
     *
     * @return An iterator over the elements of the array.
     */
    public Iterator<RuntimeScalar> iterator() {
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
    public RuntimeArrayAutovivified undefine() {
        this.elements.clear();
        return this;
    }

    /**
     * Removes the last character from each element in the list.
     *
     * @return A scalar representing the result of the chop operation.
     */
    public RuntimeScalar chop() {
        return this.getList().chop();
    }

    /**
     * Removes the trailing newline from each element in the list.
     *
     * @return A scalar representing the result of the chomp operation.
     */
    public RuntimeScalar chomp() {
        return this.getList().chomp();
    }

    /**
     * Converts the array to a string, concatenating all elements without separators.
     *
     * @return A string representation of the array.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (RuntimeBaseEntity element : elements) {
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
        RuntimeArrayAutovivified currentState = new RuntimeArrayAutovivified();
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
            RuntimeArrayAutovivified previousState = dynamicStateStack.pop();
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
        private int currentIndex = 0;

        @Override
        public boolean hasNext() {
            return currentIndex < elements.size();
        }

        @Override
        public RuntimeScalar next() {
            if (!hasNext()) {
                throw new IllegalStateException("No such element in iterator.next()");
            }
            return elements.get(currentIndex++);
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
