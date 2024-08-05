import java.util.ArrayList;
import java.util.List;

/**
 * The Array class simulates Perl arrays.
 *
 * <p>In Perl, an array is a dynamic list of scalar values. This class tries to mimic this behavior
 * using a list of Runtime objects, which can hold any type of Perl scalar value.
 */
public class RuntimeArray {
    private List<Runtime> elements;

    // Constructor
    public RuntimeArray() {
        this.elements = new ArrayList<>();
    }

    // Add a value to the array
    public void add(Runtime value) {
        elements.add(value);
    }

    // Get a value at a specific index
    public Runtime get(int index) {
        if (index < 0 || index >= elements.size()) {
            return new Runtime(); // Return undefined if out of bounds
        }
        return elements.get(index);
    }

    // Set a value at a specific index
    public void set(int index, Runtime value) {
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
