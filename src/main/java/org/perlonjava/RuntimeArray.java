package org.perlonjava;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Iterator;

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
      int size = this.size();
      for (int i = 0; i < size; i++) {
          array.push(new RuntimeScalar((RuntimeScalar) this.elements.get(i)));
      }
    }

    // Add a value to the end of the array
    public void push(RuntimeBaseEntity value) {
        elements.add(value);
    }

    public void push(RuntimeScalar value) {
        elements.add(value);
    }

    // Add a value to the beginning of the array
    public void unshift(RuntimeScalar value) {
        elements.add(0, value);
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
        int index = (int) value.getInt();
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

    // Replace the the whole array with the elements of a list
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
    public RuntimeArray getArray() {
        return this;
    }

    // Get the list value of the list
    public RuntimeList getList() {
        return new RuntimeList(this);
    }

    // Get the scalar value of the list
    public RuntimeScalar getScalar() {
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

    // Splice the array
    public RuntimeArray splice(int offset, int length, RuntimeScalar... list) {
        RuntimeArray removedElements = new RuntimeArray();

        // Handle negative offset
        if (offset < 0) {
            offset = elements.size() + offset;
        }

        // Handle negative length
        if (length < 0) {
            length = elements.size() - offset + length;
        }

        // Remove elements
        for (int i = 0; i < length && offset < elements.size(); i++) {
            removedElements.push(elements.remove(offset));
        }

        // Add new elements
        for (int i = 0; i < list.length; i++) {
            elements.add(offset + i, list[i]);
        }

        return removedElements;
    }

    // Overloaded splice methods
    public RuntimeArray splice(int offset, int length) {
        return splice(offset, length, new RuntimeScalar[0]);
    }

    public RuntimeArray splice(int offset) {
        return splice(offset, elements.size() - offset);
    }

    public RuntimeArray splice() {
        return splice(0, elements.size());
    }

    // Sort the array
    // XXX move to RuntimeList
    public void sort(Comparator<RuntimeBaseEntity> comparator) {
        elements.sort(comparator);
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
        return RuntimeList.generateList(0, this.size() - 1).getArray();
    }

    // values() operator
    public RuntimeArray values() {
        return this;
    }

    // Method to return an iterator
    public Iterator<RuntimeScalar> iterator() {
        return new RuntimeListIterator();
    }

    // Inner class implementing the Iterator interface
    private class RuntimeListIterator implements Iterator<RuntimeScalar> {
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

    // Convert the array to a string (for debugging purposes)
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < elements.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(elements.get(i).toString());
        }
        sb.append("]");
        return sb.toString();
    }
}
