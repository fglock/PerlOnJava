package org.perlonjava.runtime;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Stack;

import static org.perlonjava.runtime.RuntimeScalarCache.*;
import static org.perlonjava.runtime.RuntimeScalarType.ARRAYREFERENCE;
import static org.perlonjava.runtime.RuntimeScalarType.HASHREFERENCE;

/**
 * The RuntimeArray class simulates Perl arrays.
 *
 * <p>In Perl, an array is a dynamic list of scalar values. This class tries to mimic this behavior
 * using a list of RuntimeScalar objects, which can hold any type of Perl scalar value.
 */
public class RuntimeArray extends RuntimeBaseEntity implements RuntimeScalarReference, DynamicState {
    // Static stack to store saved "local" states of RuntimeArray instances
    private static final Stack<RuntimeArray> dynamicStateStack = new Stack<>();
    // List to hold the elements of the array.
    public List<RuntimeScalar> elements;

    // Constructor
    public RuntimeArray() {
        this.elements = new ArrayList<>();
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

    public RuntimeArray(RuntimeBaseEntity... values) {
        this.elements = new ArrayList<>();
        for (RuntimeBaseEntity value : values) {
            Iterator<RuntimeScalar> iterator = value.iterator();
            while (iterator.hasNext()) {
                this.elements.add(iterator.next());
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
    public static RuntimeScalar shift(RuntimeArray runtimeArray) {

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
     * @return A RuntimeScalar lvalue containing the integer index of the last element, or -1 if the array is empty
     */
    public static RuntimeScalar indexLastElem(RuntimeArray runtimeArray) {

        if (runtimeArray.elements instanceof AutovivificationArray arrayProxy) {
            arrayProxy.vivify(runtimeArray);
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
    public static RuntimeScalar push(RuntimeArray runtimeArray, RuntimeDataProvider value) {

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
    public static RuntimeScalar unshift(RuntimeArray runtimeArray, RuntimeDataProvider value) {

        if (runtimeArray.elements instanceof AutovivificationArray arrayProxy) {
            arrayProxy.vivify(runtimeArray);
        }

        RuntimeArray arr = new RuntimeArray();
        RuntimeArray.push(arr, value);
        runtimeArray.elements.addAll(0, arr.elements);
        return getScalarInt(runtimeArray.elements.size());
    }

    /**
     * Adds the elements of this array to another RuntimeArray.
     *
     * @param array The RuntimeArray to which elements will be added.
     */
    public void addToArray(RuntimeArray array) {

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
     * Checks if a specific index exists.
     *
     * @param index The index of the value to retrieve.
     * @return Perl value True or False.
     */
    public RuntimeScalar exists(int index) {
        if (index < 0) {
            index = elements.size() + index; // Handle negative indices
        }
        return (index < 0 || index >= elements.size()) ? scalarFalse : scalarTrue;
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
        if (index < 0) {
            index = elements.size() + index; // Handle negative indices
        }
        if (index < 0 || index >= elements.size()) {
            return scalarUndef;
        };
        if (index == elements.size() - 1) {
            return pop(this);
        }
        RuntimeScalar previous = this.get(index);
        this.set(index, scalarUndef);
        return previous;
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
    public RuntimeArray setArrayOfAlias(RuntimeArray arr) {

        if (this.elements instanceof AutovivificationArray arrayProxy) {
            arrayProxy.vivify(this);
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

        if (this.elements instanceof AutovivificationArray arrayProxy) {
            arrayProxy.vivify(this);
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
