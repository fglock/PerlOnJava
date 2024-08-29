package org.perlonjava.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * The RuntimeArray class simulates Perl arrays.
 *
 * <p>In Perl, an array is a dynamic list of scalar values. This class tries to mimic this behavior
 * using a list of RuntimeScalar objects, which can hold any type of Perl scalar value.
 */
public class RuntimeArray extends RuntimeBaseEntity implements RuntimeScalarReference {
    public List<RuntimeBaseEntity> elements;

    // Constructor
    public RuntimeArray() {
        this.elements = new ArrayList<>();
    }

    public RuntimeArray(RuntimeList a) {
        this.elements = a.elements;
    }

    public RuntimeArray(RuntimeScalar value) {
        this.elements = new ArrayList<>();
        this.elements.add(value);
    }

    // Add itself to a RuntimeArray.
    public void addToArray(RuntimeArray array) {
        List<RuntimeBaseEntity> elements = array.elements;
        for (RuntimeBaseEntity arrElem : this.elements) {
            elements.add(new RuntimeScalar((RuntimeScalar) arrElem));
        }
    }

    public int countElements() {
        return elements.size();
    }

    /**
     * Add itself to a RuntimeScalar.
     *
     * @param scalar The RuntimeScalar object
     */
    public RuntimeScalar addToScalar(RuntimeScalar scalar) {
        return scalar.set(this.size());
    }

    // Add values to the end of the array
    public RuntimeScalar push(RuntimeDataProvider value) {
        value.addToArray(this);
        return new RuntimeScalar(elements.size());
    }

    // Add values to the beginning of the array
    public RuntimeScalar unshift(RuntimeDataProvider value) {
        RuntimeArray arr = new RuntimeArray();
        arr.push(value);
        this.elements.addAll(0, arr.elements);
        return new RuntimeScalar(this.elements.size());
    }

    // Remove and return the last value of the array
    public RuntimeScalar pop() {
        if (elements.isEmpty()) {
            return new RuntimeScalar(); // Return undefined if empty
        }
        return (RuntimeScalar) elements.remove(elements.size() - 1);
    }

    // Remove and return the first value of the array
    public RuntimeScalar shift() {
        if (elements.isEmpty()) {
            return new RuntimeScalar(); // Return undefined if empty
        }
        return (RuntimeScalar) elements.remove(0);
    }

    // Get a value at a specific index
    public RuntimeScalar get(int index) {
        if (index < 0) {
            index = elements.size() + index; // Handle negative indices
        }
        if (index < 0 || index >= elements.size()) {
            // XXX TODO autovivification
            return new RuntimeScalar(); // Return undefined if out of bounds
        }
        return (RuntimeScalar) elements.get(index);
    }

    // Get a value at a specific index
    public RuntimeScalar get(RuntimeScalar value) {
        int index = value.getInt();
        if (index < 0) {
            index = elements.size() + index; // Handle negative indices
        }
        if (index < 0 || index >= elements.size()) {
            // XXX TODO autovivification
            return new RuntimeScalar(); // Return undefined if out of bounds
        }
        return (RuntimeScalar) elements.get(index);
    }

    // Set the whole array to a Scalar
    public RuntimeArray set(RuntimeScalar value) {
        this.elements.clear();
        this.elements.add(value);
        return this;
    }

    // Replace the whole array with the elements of a list
    public RuntimeList set(RuntimeList value) {
        this.elements.clear();
        value.addToArray(this);
        return new RuntimeList(this);
    }

    // Set a value at a specific index
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

    // Create a reference to the Array
    public RuntimeScalar createReference() {
        RuntimeScalar result = new RuntimeScalar();
        result.type = RuntimeScalarType.ARRAYREFERENCE;
        result.value = this;
        return result;
    }

    // Get the size of the array
    public int size() {
        return elements.size();
    }

    // Get the list value of the list
    public RuntimeList getList() {
        return new RuntimeList(this);
    }

    // Get the index of the last element
    public RuntimeScalar indexLastElem() {
        return new RuntimeScalar(elements.size() - 1);
    }

    // Get the scalar value of the list
    public RuntimeScalar scalar() {
        return new RuntimeScalar(elements.size());
    }

    // Slice the array
    public RuntimeArray slice(int start, int end) {
        RuntimeArray result = new RuntimeArray();
        for (int i = start; i < end && i < elements.size(); i++) {
            result.push(elements.get(i));
        }
        return result;
    }

    /**
     * Splices the array based on the parameters provided in the RuntimeList.
     * The RuntimeList should contain the following elements in order:
     * - OFFSET: The starting position for the splice operation (int).
     * - LENGTH: The number of elements to be removed (int).
     * - LIST: The list of elements to be inserted at the splice position (RuntimeList).
     * <p>
     * If OFFSET is not provided, it defaults to 0.
     * If LENGTH is not provided, it defaults to the size of the array.
     * If LIST is not provided, no elements are inserted.
     *
     * @param list the RuntimeList containing the splice parameters and elements
     * @return a RuntimeList containing the elements that were removed
     */
    public RuntimeList splice(RuntimeList list) {
        RuntimeList removedElements = new RuntimeList();

        int size = this.elements.size();

        int offset;
        if (!list.elements.isEmpty()) {
            RuntimeDataProvider value = list.elements.remove(0);
            offset = value.scalar().getInt();
        } else {
            offset = 0;
        }

        int length;
        if (!list.elements.isEmpty()) {
            RuntimeDataProvider value = list.elements.remove(0);
            length = value.scalar().getInt();
        } else {
            length = size;
        }

        // Handle negative offset
        if (offset < 0) {
            offset = size + offset;
        }

        // Ensure offset is within bounds
        if (offset > size) {
            offset = size;
        }

        // Handle negative length
        if (length < 0) {
            length = size - offset + length;
        }

        // Ensure length is within bounds
        length = Math.min(length, size - offset);

        // Remove elements
        for (int i = 0; i < length && offset < elements.size(); i++) {
            removedElements.elements.add(elements.remove(offset));
        }

        // Add new elements
        if (!list.elements.isEmpty()) {
            RuntimeArray arr = new RuntimeArray();
            arr.push(list);
            this.elements.addAll(offset, arr.elements);
        }

        return removedElements;
    }

    // Reverse the array
    public void reverse() {
        Collections.reverse(elements);
    }

    // Join the array into a string
    public String join(String delimiter) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < elements.size(); i++) {
            if (i > 0) {
                sb.append(delimiter);
            }
            sb.append(elements.get(i).toString());
        }
        return sb.toString();
    }

    // keys() operator
    public RuntimeArray keys() {
        return RuntimeList.generateList(0, this.size() - 1).getArrayOfAlias();
    }

    // values() operator
    public RuntimeArray values() {
        return this;
    }

    // Get Array aliases into an Array
    public RuntimeArray setArrayOfAlias(RuntimeArray arr) {
        List<RuntimeBaseEntity> arrElements = arr.elements;
        arrElements.addAll(this.elements);
        return arr;
    }

    // Method to return an iterator
    public Iterator<RuntimeScalar> iterator() {
        return new RuntimeArrayIterator();
    }

    public String toStringRef() {
        return "ARRAY(" + this.hashCode() + ")";
    }

    public int getIntRef() {
        return this.hashCode();
    }

    public double getDoubleRef() {
        return this.hashCode();
    }

    public boolean getBooleanRef() {
        return true;
    }

    public RuntimeArray undefine() {
        this.elements.clear();
        return this;
    }

    // Convert the array to a string, without separators
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (RuntimeBaseEntity element : elements) {
            sb.append(element.toString());
        }
        return sb.toString();
    }

    // Inner class implementing the Iterator interface
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
            return (RuntimeScalar) elements.get(currentIndex++);
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
