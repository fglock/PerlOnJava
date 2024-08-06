import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The RuntimeArray class simulates Perl arrays.
 *
 * <p>In Perl, an array is a dynamic list of scalar values. This class tries to mimic this behavior
 * using a list of Runtime objects, which can hold any type of Perl scalar value.
 */
public class RuntimeArray implements ContextProvider {
    public List<Runtime> elements;

    // Constructor
    public RuntimeArray() {
        this.elements = new ArrayList<>();
    }

    public RuntimeArray(RuntimeList a) {
        this.elements = a.elements;
    }

    public RuntimeArray(Runtime value) {
        this.elements = new ArrayList<>();
        this.elements.add(value);
    }

    // Add a value to the end of the array
    public void push(Runtime value) {
        elements.add(value);
    }

    // Add a value to the beginning of the array
    public void unshift(Runtime value) {
        elements.add(0, value);
    }

    // Remove and return the last value of the array
    public Runtime pop() {
        if (elements.isEmpty()) {
            return new Runtime(); // Return undefined if empty
        }
        return elements.remove(elements.size() - 1);
    }

    // Remove and return the first value of the array
    public Runtime shift() {
        if (elements.isEmpty()) {
            return new Runtime(); // Return undefined if empty
        }
        return elements.remove(0);
    }

    // Get a value at a specific index
    public Runtime get(int index) {
        if (index < 0) {
            index = elements.size() + index; // Handle negative indices
        }
        if (index < 0 || index >= elements.size()) {
            return new Runtime(); // Return undefined if out of bounds
        }
        return elements.get(index);
    }

    // Set a value at a specific index
    public void set(int index, Runtime value) {
        if (index < 0) {
            index = elements.size() + index; // Handle negative indices
        }
        if (index < 0 || index >= elements.size()) {
            for (int i = elements.size(); i <= index; i++) {
                elements.add(new Runtime()); // Fill with undefined values if necessary
            }
        }
        elements.set(index, value);
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
    public Runtime getScalar() {
        return new Runtime(elements.size());
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
    public RuntimeArray splice(int offset, int length, Runtime... list) {
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
        return splice(offset, length, new Runtime[0]);
    }

    public RuntimeArray splice(int offset) {
        return splice(offset, elements.size() - offset);
    }

    public RuntimeArray splice() {
        return splice(0, elements.size());
    }

    // Sort the array
    public void sort(Comparator<Runtime> comparator) {
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
