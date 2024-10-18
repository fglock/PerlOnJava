package org.perlonjava.runtime;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * The RuntimeList class simulates a Perl list.
 * It provides methods to manipulate and access a dynamic list of Perl values.
 */
public class RuntimeList extends RuntimeBaseEntity implements RuntimeDataProvider {
    // List to hold the elements of the list.
    public List<RuntimeBaseEntity> elements;

    // Constructor
    public RuntimeList() {
        this.elements = new ArrayList<>();
    }

    /**
     * Constructs a RuntimeList with a single scalar value.
     *
     * @param value The initial scalar value for the list.
     */
    public RuntimeList(RuntimeScalar value) {
        this.elements = new ArrayList<>();
        this.elements.add(value);
    }

    /**
     * Constructs a RuntimeList from another RuntimeList.
     *
     * @param value The RuntimeList to initialize this list with.
     */
    public RuntimeList(RuntimeList value) {
        this.elements = value.elements;
    }

    /**
     * Constructs a RuntimeList from a RuntimeArray.
     *
     * @param value The RuntimeArray to initialize this list with.
     */
    public RuntimeList(RuntimeArray value) {
        this.elements = new ArrayList<>();
        this.elements.add(value);
    }

    /**
     * Constructs a RuntimeList from a RuntimeHash.
     *
     * @param value The RuntimeHash to initialize this list with.
     */
    public RuntimeList(RuntimeHash value) {
        this.elements = new ArrayList<>();
        this.elements.add(value);
    }

    /**
     * Adds the elements of this list to another RuntimeList.
     *
     * @param list The RuntimeList to which elements will be added.
     */
    public void addToList(RuntimeList list) {
        for (RuntimeBaseEntity elem : elements) {
            list.add(elem);
        }
        this.elements.clear(); // Consume the list
    }

    /**
     * Adds the elements of this list to a RuntimeArray.
     *
     * @param array The RuntimeArray to which elements will be added.
     */
    public void addToArray(RuntimeArray array) {
        int size = this.size();
        for (int i = 0; i < size; i++) {
            this.elements.get(i).addToArray(array);
        }
        this.elements.clear(); // Consume the list
    }

    /**
     * Adds the scalar value of this list to a RuntimeScalar.
     *
     * @param scalar The RuntimeScalar object.
     * @return The scalar with the list's scalar value set.
     */
    public RuntimeScalar addToScalar(RuntimeScalar scalar) {
        return scalar.set(this.scalar());
    }

    /**
     * Adds an element to the list.
     *
     * @param value The value to add.
     */
    public void add(RuntimeBaseEntity value) {
        this.elements.add(value);
    }

    /**
     * Adds an integer value to the list.
     *
     * @param value The integer value to add.
     */
    public void add(int value) {
        this.elements.add(new RuntimeScalar(value));
    }

    /**
     * Adds a string value to the list.
     *
     * @param value The string value to add.
     */
    public void add(String value) {
        this.elements.add(new RuntimeScalar(value));
    }

    /**
     * Adds a double value to the list.
     *
     * @param value The double value to add.
     */
    public void add(Double value) {
        this.elements.add(new RuntimeScalar(value));
    }

    /**
     * Merges another RuntimeList into this list.
     *
     * @param value The RuntimeList to merge.
     */
    public void add(RuntimeList value) {
        int size = value.size();
        for (int i = 0; i < size; i++) {
            this.elements.add(value.elements.get(i));
        }
    }

    /**
     * Gets the size of the list.
     *
     * @return The number of elements in the list.
     */
    public int size() {
        return elements.size();
    }

    /**
     * Counts the total number of elements in the list, including nested elements.
     *
     * @return The total number of elements.
     */
    public int countElements() {
        int count = 0;
        for (RuntimeBaseEntity elem : elements) {
            count = count + elem.countElements();
        }
        return count;
    }

    /**
     * Gets the array value of the list as aliases into an array.
     *
     * @param arr The array to set aliases into.
     * @return The updated array with aliases.
     */
    public RuntimeArray setArrayOfAlias(RuntimeArray arr) {
        for (RuntimeBaseEntity elem : elements) {
            elem.setArrayOfAlias(arr);
        }
        return arr;
    }

    /**
     * Gets the list value of the list.
     *
     * @return This RuntimeList.
     */
    public RuntimeList getList() {
        return this;
    }

    /**
     * Evaluates the boolean representation of the list.
     *
     * @return True if the scalar value of the list is true.
     */
    public boolean getBoolean() {
        return scalar().getBoolean();
    }

    /**
     * Checks if the list is defined.
     *
     * @return True if the scalar value of the list is defined.
     */
    public boolean getDefinedBoolean() {
        return scalar().getDefinedBoolean();
    }

    /**
     * Throws an exception as the 'keys' operation is not implemented for lists.
     *
     * @throws IllegalStateException Always thrown as 'keys' is not implemented.
     */
    public RuntimeArray keys() {
        throw new IllegalStateException("Type of arg 1 to values must be hash or array");
    }

    /**
     * Throws an exception as the 'values' operation is not implemented for lists.
     *
     * @throws IllegalStateException Always thrown as 'values' is not implemented.
     */
    public RuntimeArray values() {
        throw new IllegalStateException("Type of arg 1 to values must be hash or array");
    }

    /**
     * Throws an exception as the 'each' operation is not implemented for lists.
     *
     * @throws IllegalStateException Always thrown as 'each' is not implemented.
     */
    public RuntimeList each() {
        throw new IllegalStateException("Type of arg 1 to each must be hash or array");
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
        int count = 0;
        Iterator<RuntimeScalar> iterator = this.iterator();
        while (iterator.hasNext()) {
            count = count + iterator.next().chomp().getInt();
        }
        return new RuntimeScalar(count);
    }

    /**
     * Gets the scalar value of the list.
     *
     * @return The scalar value of the last element in the list.
     */
    public RuntimeScalar scalar() {
        if (elements.isEmpty()) {
            return new RuntimeScalar(); // Return undefined if empty
        }
        // XXX expand the last element
        return elements.get(elements.size() - 1).scalar();
    }

    /**
     * Throws an exception as creating a reference of a list is not implemented.
     *
     * @throws IllegalStateException Always thrown as creating a reference is not implemented.
     */
    public RuntimeScalar createReference() {
        // TODO
        throw new IllegalStateException("TODO - create reference of list not implemented");
    }

    /**
     * Creates a list reference.
     *
     * @return A new RuntimeList with references to the elements of this list.
     */
    public RuntimeList createListReference() {
        RuntimeList result = new RuntimeList();
        List<RuntimeBaseEntity> resultList = result.elements;
        Iterator<RuntimeScalar> iterator = this.iterator();
        while (iterator.hasNext()) {
            resultList.add(iterator.next().createReference());
        }
        return result;
    }

    /**
     * Sets the items in the list to the values in another list.
     * In list context, returns the argument list.
     * In scalar context, returns the number of elements in the argument list.
     *
     * @param value The list to set from.
     * @return The original list.
     */
    public RuntimeArray setFromList(RuntimeList value) {

        // Flatten the right side
        RuntimeArray original = new RuntimeArray();
        value.addToArray(original);

        // Retrieve the list
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

    /**
     * Converts the list to a string, concatenating all elements without separators.
     *
     * @return A string representation of the list.
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
     * Undefines the elements of the list.
     *
     * @return The updated RuntimeList after undefining.
     */
    public RuntimeList undefine() {
        for (RuntimeBaseEntity elem : elements) {
            elem.undefine();
        }
        return this;
    }

    /**
     * Returns an iterator for the list.
     *
     * @return An iterator over the elements of the list.
     */
    public Iterator<RuntimeScalar> iterator() {
        return new RuntimeListIterator(elements);
    }

    /**
     * Inner class implementing the Iterator interface for RuntimeList.
     */
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
