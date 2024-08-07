import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The RuntimeList class simulates a Perl list.
 *
 */
public class RuntimeList implements ContextProvider {
    public List<AbstractRuntimeObject> elements;

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

    public RuntimeList(RuntimeArray value) {
        this.elements = value.elements;
    }

    public RuntimeList(RuntimeHash value) {
        this.elements = value.entryArray().elements;
    }

    public void addToList(RuntimeList list) {
        list.add(this);
    }

    // Add an element to the list
    public void add(AbstractRuntimeObject value) {
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

    // Get the array value of the List
    public RuntimeArray getArray() {
      return new RuntimeArray(this);
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
        // XXX expand the last element
        return (Runtime) elements.get(elements.size() - 1);
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

    public Runtime say() {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < elements.size(); i++) {
          sb.append(elements.get(i).toString());
      }
      System.out.println(sb.toString());
      return new Runtime(1);
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
