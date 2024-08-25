package org.perlonjava.runtime;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

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
    public RuntimeScalar getScalar() {
        if (elements.isEmpty()) {
            return new RuntimeScalar(); // Return undefined if empty
        }
        // XXX expand the last element
        return elements.get(elements.size() - 1).getScalar();
    }

    // Set the items in the list to the values in another list
    public RuntimeList set(RuntimeList value) {
        // flatten the right side
        RuntimeArray arr = new RuntimeArray();
        value.addToArray(arr);
        for (RuntimeBaseEntity elem : elements) {
            if (elem instanceof RuntimeScalar) {
                ((RuntimeScalar) elem).set(arr.shift());
            } else if (elem instanceof RuntimeArray) {
                ((RuntimeArray) elem).elements = arr.elements;
                arr = new RuntimeArray();
            } else if (elem instanceof RuntimeHash) {
                RuntimeHash hash = RuntimeHash.fromArray(arr);
                ((RuntimeHash) elem).elements = hash.elements;
                arr = new RuntimeArray();
            }
        }
        return new RuntimeList(value);
    }

    // Convert the list to a string (for debugging purposes)
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

    // Method to return an iterator
    public Iterator<RuntimeScalar> iterator() {
        return this.getArrayOfAlias().iterator();
    }

    // Operators

    // undefine the elements of the list
    public RuntimeList undefine() {
        for (RuntimeBaseEntity elem : elements) {
            elem.undefine();
        }
        return this;
    }

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
}
