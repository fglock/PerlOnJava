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
    public static RuntimeList generateList(int start, int end) {
        RuntimeList list = new RuntimeList();
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

    // keys() operator
    public RuntimeArray keys() {
        throw new IllegalStateException("Type of arg 1 to values must be hash or array");
    }

    // values() operator
    public RuntimeArray values() {
        throw new IllegalStateException("Type of arg 1 to values must be hash or array");
    }

    // Get the scalar value of the list
    public RuntimeScalar scalar() {
        if (elements.isEmpty()) {
            return new RuntimeScalar(); // Return undefined if empty
        }
        // XXX expand the last element
        return elements.get(elements.size() - 1).scalar();
    }

    // Set the items in the list to the values in another list
    // (THIS LIST) = (ARG LIST)
    //
    // In LIST context returns the ARG LIST
    // In SCALAR context returns the number of elements in ARG LIST
    //
    public RuntimeList set(RuntimeList value) {

        // preserve the right side as return value from the method
        RuntimeList original = new RuntimeList();
        for (RuntimeBaseEntity elem : value.elements) {
            original.add(elem);
        }

        // flatten the right side
        RuntimeArray arr = new RuntimeArray();
        value.addToArray(arr);

        for (RuntimeBaseEntity elem : elements) {
            if (elem instanceof RuntimeScalar) {
                ((RuntimeScalar) elem).set(arr.shift());
            } else if (elem instanceof RuntimeArray) {
                ((RuntimeArray) elem).elements = arr.elements;
                arr.elements = new ArrayList<>();
            } else if (elem instanceof RuntimeHash) {
                RuntimeHash hash = RuntimeHash.fromArray(arr);
                ((RuntimeHash) elem).elements = hash.elements;
                arr.elements = new ArrayList<>();
            }
        }
        return new RuntimeList(original);
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

    // Method to return an iterator
    public Iterator<RuntimeScalar> iterator() {
        return new RuntimeListIterator(elements);
    }

    // undefine the elements of the list
    public RuntimeList undefine() {
        for (RuntimeBaseEntity elem : elements) {
            elem.undefine();
        }
        return this;
    }

    // Operators

    public RuntimeScalar print() {
        StringBuilder sb = new StringBuilder();
        for (RuntimeBaseEntity element : elements) {
            sb.append(element.toString());
        }
        System.out.print(sb);
        return new RuntimeScalar(1);
    }

    public RuntimeScalar say() {
        StringBuilder sb = new StringBuilder();
        for (RuntimeBaseEntity element : elements) {
            sb.append(element.toString());
        }
        System.out.println(sb);
        return new RuntimeScalar(1);
    }

    /**
     * Sorts the elements of this RuntimeArray using a Perl comparator subroutine.
     *
     * @param perlComparatorClosure A RuntimeScalar representing the Perl comparator subroutine.
     * @return A new RuntimeList with the elements sorted according to the Perl comparator.
     * @throws RuntimeException If the Perl comparator subroutine throws an exception.
     */
    public RuntimeList sort(RuntimeScalar perlComparatorClosure) {
        // Create a new list from the elements of this RuntimeArray
        RuntimeArray array = new RuntimeArray();
        this.setArrayOfAlias(array);

        RuntimeScalar varA = GlobalContext.getGlobalVariable("main::a");
        RuntimeScalar varB = GlobalContext.getGlobalVariable("main::b");
        RuntimeArray comparatorArgs = new RuntimeArray();

        // Sort the new array using the Perl comparator subroutine
        array.elements.sort((a, b) -> {
            try {
                // Create $a, $b arguments for the comparator
                varA.set((RuntimeScalar) a);
                varB.set((RuntimeScalar) b);

                // Apply the Perl comparator subroutine with the arguments
                RuntimeList result = perlComparatorClosure.apply(comparatorArgs, RuntimeContextType.SCALAR);

                // Retrieve the comparison result and return it as an integer
                return result.elements.get(0).scalar().getInt();
            } catch (Exception e) {
                // Wrap any exceptions thrown by the comparator in a RuntimeException
                throw new RuntimeException(e);
            }
        });

        // Create a new RuntimeList to hold the sorted elements
        RuntimeList sortedList = new RuntimeList();
        sortedList.elements = array.elements;

        // Return the sorted RuntimeList
        return sortedList;
    }

    /**
     * Filters the elements of this RuntimeArray using a Perl subroutine.
     *
     * @param perlFilterClosure A RuntimeScalar representing the Perl filter subroutine.
     * @return A new RuntimeList with the elements that match the filter criteria.
     * @throws RuntimeException If the Perl filter subroutine throws an exception.
     */
    public RuntimeList grep(RuntimeScalar perlFilterClosure) {
        RuntimeArray array = new RuntimeArray();
        this.setArrayOfAlias(array);

        // Create a new list to hold the filtered elements
        List<RuntimeBaseEntity> filteredElements = new ArrayList<>();

        RuntimeScalar var_ = GlobalContext.getGlobalVariable("main::_");
        RuntimeArray filterArgs = new RuntimeArray();

        // Iterate over each element in the current RuntimeArray
        for (RuntimeBaseEntity element : array.elements) {
            try {
                // Create $_ argument for the filter subroutine
                var_.set((RuntimeScalar) element);

                // Apply the Perl filter subroutine with the argument
                RuntimeList result = perlFilterClosure.apply(filterArgs, RuntimeContextType.SCALAR);

                // Check the result of the filter subroutine
                if (result.elements.get(0).scalar().getBoolean()) {
                    // If the result is non-zero, add the element to the filtered list
                    // We need to clone, otherwise we would be adding an alias to the original element
                    filteredElements.add(((RuntimeScalar) element).clone());
                }
            } catch (Exception e) {
                // Wrap any exceptions thrown by the filter subroutine in a RuntimeException
                throw new RuntimeException(e);
            }
        }

        // Create a new RuntimeList to hold the filtered elements
        RuntimeList filteredList = new RuntimeList();
        filteredList.elements = filteredElements;

        // Return the filtered RuntimeList
        return filteredList;
    }

    /**
     * Transforms the elements of this RuntimeArray using a Perl subroutine.
     *
     * @param perlMapClosure A RuntimeScalar representing the Perl map subroutine.
     * @return A new RuntimeList with the transformed elements.
     * @throws RuntimeException If the Perl map subroutine throws an exception.
     */
    public RuntimeList map(RuntimeScalar perlMapClosure) {
        RuntimeArray array = new RuntimeArray();
        this.setArrayOfAlias(array);

        // Create a new list to hold the transformed elements
        List<RuntimeBaseEntity> transformedElements = new ArrayList<>();

        RuntimeScalar var_ = GlobalContext.getGlobalVariable("main::_");
        RuntimeArray mapArgs = new RuntimeArray();

        // Iterate over each element in the current RuntimeArray
        for (RuntimeBaseEntity element : array.elements) {
            try {
                // Create $_ argument for the map subroutine
                var_.set((RuntimeScalar) element);

                // Apply the Perl map subroutine with the argument
                RuntimeList result = perlMapClosure.apply(mapArgs, RuntimeContextType.LIST);

                // `result` list contains aliases to the original array;
                // We need to make copies of the result elements
                RuntimeArray arr = new RuntimeArray();
                result.addToArray(arr);

                // Add all elements of the result list to the transformed list
                transformedElements.addAll(arr.elements);
            } catch (Exception e) {
                // Wrap any exceptions thrown by the map subroutine in a RuntimeException
                throw new RuntimeException(e);
            }
        }

        // Create a new RuntimeList to hold the transformed elements
        RuntimeList transformedList = new RuntimeList();
        transformedList.elements = transformedElements;

        // Return the transformed RuntimeList
        return transformedList;
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
