import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The RuntimeList class simulates a Perl list.
 *
 */
public class RuntimeList {
    public List<Runtime> elements;

    // Constructor
    public RuntimeList() {
        this.elements = new ArrayList<>();
    }

    public RuntimeList(Runtime value) {
        this.elements = new ArrayList<>();
        this.elements.add(value);
    }

    public RuntimeList(RuntimeList value) {
        this.elements = value.elements;
    }

    // Get the size of the list
    public int size() {
        return elements.size();
    }

    // Get the list value of the list
    public RuntimeList getList() {
        return this;
    }

    // Get the scalar value of the list
    public Runtime getScalar() {
        if (elements.isEmpty()) {
            return new Runtime(); // Return undefined if empty
        }
        return elements.get(elements.size() - 1);
    }

    // Join the list into a string
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
}
