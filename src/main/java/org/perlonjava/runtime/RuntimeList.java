package org.perlonjava.runtime;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * The RuntimeList class simulates a Perl list.
 */
public class RuntimeList extends RuntimeBaseEntity implements RuntimeDataProvider {
    public List<RuntimeBaseEntity> elements;

    // Constructor
    public RuntimeList() {
        this.elements = new ArrayList<>();
    }

    public RuntimeList(RuntimeScalar value) {
        this.elements = new ArrayList<>();
        this.elements.add(value);
    }

    public RuntimeList(RuntimeList value) {
        this.elements = value.elements;
    }

    public RuntimeList(RuntimeArray value) {
        this.elements = value.elements;
    }

    public RuntimeList(RuntimeHash value) {
        this.elements = value.entryArray().elements;
    }

    // Method to generate a list of RuntimeScalar objects
    public static RuntimeList generateList(RuntimeScalar startValue, RuntimeScalar endValue) {
        RuntimeList list = new RuntimeList();
        int start = startValue.getInt();
        int end = endValue.getInt();
        for (int i = start; i <= end; i++) {
            list.add(new RuntimeScalar(i));
        }
        return list;
    }

    // Add itself to a RuntimeList.
    public void addToList(RuntimeList list) {
        int size = this.size();
        for (int i = 0; i < size; i++) {
            list.add(this.elements.get(i));
        }
        this.elements.clear();    // consume the list
    }

    // Add itself to a RuntimeArray.
    public void addToArray(RuntimeArray array) {
        int size = this.size();
        for (int i = 0; i < size; i++) {
            this.elements.get(i).addToArray(array);
        }
        this.elements.clear();    // consume the list
    }

    /**
     * Add itself to a RuntimeScalar.
     *
     * @param scalar The RuntimeScalar object
     */
    public RuntimeScalar addToScalar(RuntimeScalar scalar) {
        return scalar.set(this.scalar());
    }

    // Add an element to the list
    public void add(RuntimeBaseEntity value) {
        this.elements.add(value);
    }

    public void add(int value) {
        this.elements.add(new RuntimeScalar(value));
    }

    public void add(String value) {
        this.elements.add(new RuntimeScalar(value));
    }

    // When adding a List into a List they are merged
    public void add(RuntimeList value) {
        int size = value.size();
        for (int i = 0; i < size; i++) {
            this.elements.add(value.elements.get(i));
        }
    }

    // Get the size of the list
    public int size() {
        return elements.size();
    }

    public int countElements() {
        int count = 0;
        for (RuntimeBaseEntity elem : elements) {
            count = count + elem.countElements();
        }
        return count;
    }

    // Get the array value of the List as aliases into an Array
    public RuntimeArray setArrayOfAlias(RuntimeArray arr) {
        for (RuntimeBaseEntity elem : elements) {
            elem.setArrayOfAlias(arr);
        }
        return arr;
    }

    // Get the list value of the list
    public RuntimeList getList() {
        return this;
    }

    public boolean getBoolean() {
        return scalar().getBoolean();
    }

    // keys() operator
    public RuntimeArray keys() {
        throw new IllegalStateException("Type of arg 1 to values must be hash or array");
    }

    // values() operator
    public RuntimeArray values() {
        throw new IllegalStateException("Type of arg 1 to values must be hash or array");
    }

    public RuntimeList each() {
        throw new IllegalStateException("Type of arg 1 to each must be hash or array");
    }

    public RuntimeScalar chop() {
        RuntimeScalar result = new RuntimeScalar("");
        Iterator<RuntimeScalar> iterator = this.iterator();
        while (iterator.hasNext()) {
            result = iterator.next().chop();
        }
        return result;
    }

    public RuntimeScalar chomp() {
        int count = 0;
        Iterator<RuntimeScalar> iterator = this.iterator();
        while (iterator.hasNext()) {
            count = count + iterator.next().chomp().getInt();
        }
        return new RuntimeScalar(count);
    }

    // Get the scalar value of the list
    public RuntimeScalar scalar() {
        if (elements.isEmpty()) {
            return new RuntimeScalar(); // Return undefined if empty
        }
        // XXX expand the last element
        return elements.get(elements.size() - 1).scalar();
    }

    public RuntimeScalar createReference() {
        // TODO
        throw new IllegalStateException("TODO - create reference of list not implemented");
    }

    public RuntimeList createListReference() {
        RuntimeList result = new RuntimeList();
        List<RuntimeBaseEntity> resultList = result.elements;
        Iterator<RuntimeScalar> iterator = this.iterator();
        while (iterator.hasNext()) {
            resultList.add(iterator.next().createReference());
        }
        return result;
    }

    // Set the items in the list to the values in another list
    // (THIS LIST) = (ARG LIST)
    //
    // In LIST context returns the ARG LIST
    // In SCALAR context returns the number of elements in ARG LIST
    //
    public RuntimeArray set(RuntimeList value) {

        // flatten the right side
        RuntimeArray original = new RuntimeArray();
        value.addToArray(original);

        // retrieve the list
        RuntimeArray arr = new RuntimeArray();
        original.addToArray(arr);

        for (RuntimeBaseEntity elem : elements) {
            if (elem instanceof RuntimeScalar) {
                ((RuntimeScalar) elem).set(arr.shift());
            } else if (elem instanceof RuntimeArray) {
                ((RuntimeArray) elem).elements = arr.elements;
                arr.elements = new ArrayList<>();
            } else if (elem instanceof RuntimeHash) {
                RuntimeHash hash = RuntimeHash.createHash(arr);
                ((RuntimeHash) elem).elements = hash.elements;
                arr.elements = new ArrayList<>();
            }
        }
        return original;
    }

    // Convert the list to a string
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (RuntimeBaseEntity element : elements) {
            sb.append(element.toString());
        }
        return sb.toString();
    }

    // undefine the elements of the list
    public RuntimeList undefine() {
        for (RuntimeBaseEntity elem : elements) {
            elem.undefine();
        }
        return this;
    }

    // Method to return an iterator
    public Iterator<RuntimeScalar> iterator() {
        return new RuntimeListIterator(elements);
    }

    private static class RuntimeListIterator implements Iterator<RuntimeScalar> {
        private final List<RuntimeBaseEntity> elements;
        private int currentIndex = 0;
        private Iterator<RuntimeScalar> currentIterator;

        public RuntimeListIterator(List<RuntimeBaseEntity> elements) {
            this.elements = elements;
            if (!elements.isEmpty()) {
                currentIterator = elements.get(0).iterator();
            }
        }

        @Override
        public boolean hasNext() {
            while (currentIterator != null) {
                if (currentIterator.hasNext()) {
                    return true;
                }
                // Move to the next element's iterator
                currentIndex++;
                if (currentIndex < elements.size()) {
                    currentIterator = elements.get(currentIndex).iterator();
                } else {
                    currentIterator = null; // No more elements
                }
            }
            return false;
        }

        @Override
        public RuntimeScalar next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return currentIterator.next();
        }
    }
}
