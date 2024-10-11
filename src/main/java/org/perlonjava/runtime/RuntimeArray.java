package org.perlonjava.runtime;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.perlonjava.runtime.RuntimeScalarCache.getScalarInt;

/**
 * The RuntimeArray class simulates Perl arrays.
 *
 * <p>In Perl, an array is a dynamic list of scalar values. This class tries to mimic this behavior
 * using a list of RuntimeScalar objects, which can hold any type of Perl scalar value.
 */
public class RuntimeArray extends RuntimeBaseEntity implements RuntimeScalarReference {
    public List<RuntimeScalar> elements;

    // Constructor
    public RuntimeArray() {
        this.elements = new ArrayList<>();
    }

    public RuntimeArray(RuntimeList a) {
        this.elements = new ArrayList<>();
        Iterator<RuntimeScalar> iterator = a.iterator();
        while (iterator.hasNext()) {
            this.elements.add(iterator.next());
        }
    }

    public RuntimeArray(RuntimeScalar value) {
        this.elements = new ArrayList<>();
        this.elements.add(value);
    }

    // Add itself to a RuntimeArray.
    public void addToArray(RuntimeArray array) {
        List<RuntimeScalar> elements = array.elements;
        for (RuntimeScalar arrElem : this.elements) {
            elements.add(new RuntimeScalar(arrElem));
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
        return getScalarInt(elements.size());
    }

    // Add values to the beginning of the array
    public RuntimeScalar unshift(RuntimeDataProvider value) {
        RuntimeArray arr = new RuntimeArray();
        arr.push(value);
        this.elements.addAll(0, arr.elements);
        return getScalarInt(this.elements.size());
    }

    // Remove and return the last value of the array
    public RuntimeScalar pop() {
        if (elements.isEmpty()) {
            return new RuntimeScalar(); // Return undefined if empty
        }
        return elements.remove(elements.size() - 1);
    }

    // Remove and return the first value of the array
    public RuntimeScalar shift() {
        if (elements.isEmpty()) {
            return new RuntimeScalar(); // Return undefined if empty
        }
        return elements.remove(0);
    }

    // Get a value at a specific index
    public RuntimeScalar get(int index) {
        if (index < 0) {
            index = elements.size() + index; // Handle negative indices
        }
        if (index < 0 || index >= elements.size()) {
            // lazy autovivification
            return new RuntimeArrayProxy(this, index);
        }
        return elements.get(index);
    }

    // Get a value at a specific index
    public RuntimeScalar get(RuntimeScalar value) {
        int index = value.getInt();
        if (index < 0) {
            index = elements.size() + index; // Handle negative indices
        }
        if (index < 0 || index >= elements.size()) {
            // lazy autovivification
            return new RuntimeArrayProxy(this, index);
        }
        return elements.get(index);
    }

    // Set the whole array to a Scalar
    public RuntimeArray set(RuntimeScalar value) {
        this.elements.clear();
        this.elements.add(value);
        return this;
    }

    // Replace the whole array with the elements of a list
    public RuntimeArray setFromList(RuntimeList value) {
        this.elements.clear();
        value.addToArray(this);
        return this;
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

    public boolean getBoolean() {
        return !elements.isEmpty();
    }

    public boolean getDefinedBoolean() {
        return true;
    }

    // Get the list value of the list
    public RuntimeList getList() {
        return new RuntimeList(this);
    }

    // Get the index of the last element
    public RuntimeScalar indexLastElem() {
        return getScalarInt(elements.size() - 1);
    }

    // Get the scalar value of the list
    public RuntimeScalar scalar() {
        return getScalarInt(elements.size());
    }

    // Slice the array:  @x[10, 20]
    public RuntimeList getSlice(RuntimeList value) {
        RuntimeList result = new RuntimeList();
        List<RuntimeBaseEntity> outElements = result.elements;
        Iterator<RuntimeScalar> iterator = value.iterator();
        while (iterator.hasNext()) {
            outElements.add(this.get(iterator.next()));
        }
        return result;
    }

    // keys() operator
    public RuntimeArray keys() {
        return new PerlRange(getScalarInt(0), getScalarInt(this.countElements())).getArrayOfAlias();
    }

    // values() operator
    public RuntimeArray values() {
        return this;
    }

    public RuntimeList each() {
        throw new RuntimeException("each not implemented for Array");
    }

    // Get Array aliases into an Array
    public RuntimeArray setArrayOfAlias(RuntimeArray arr) {
        List<RuntimeScalar> arrElements = arr.elements;
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

    public RuntimeScalar chop() {
        return this.getList().chop();
    }

    public RuntimeScalar chomp() {
        return this.getList().chomp();
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
