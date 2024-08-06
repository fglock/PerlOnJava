import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The RuntimeHash class simulates Perl hashes.
 *
 * <p>In Perl, a hash is an associative array, meaning it is a collection of key-value pairs. This
 * class tries to mimic this behavior using a map of string keys to Runtime objects, which can hold
 * any type of Perl scalar value.
 */
public class RuntimeHash implements ContextProvider {
    private Map<String, Runtime> elements;

    // Constructor
    public RuntimeHash() {
        this.elements = new HashMap<>();
    }

    // Add a key-value pair to the hash
    public void put(String key, Runtime value) {
        elements.put(key, value);
    }

    // Get a value by key
    public Runtime get(String key) {
        return elements.getOrDefault(key, new Runtime()); // Return undefined if key is not present
    }

    // Check if a key exists in the hash
    public boolean containsKey(String key) {
        return elements.containsKey(key);
    }

    // Remove a key-value pair by key
    public void remove(String key) {
        elements.remove(key);
    }

    // Get the size of the hash
    public int size() {
        return elements.size();
    }

    // Clear all key-value pairs in the hash
    public void clear() {
        elements.clear();
    }

    // Get all keys in the hash as a RuntimeArray
    public RuntimeArray keys() {
        RuntimeArray array = new RuntimeArray();
        for (String key : elements.keySet()) {
            array.push(new Runtime(key));
        }
        return array;
    }

    // Get all values in the hash as a RuntimeArray
    public RuntimeArray values() {
        RuntimeArray array = new RuntimeArray();
        for (Runtime value : elements.values()) {
            array.push(value);
        }
        return array;
    }

    // Merge another RuntimeHash into this one
    public void merge(RuntimeHash other) {
        elements.putAll(other.elements);
    }

    // Check if the hash is empty
    public boolean isEmpty() {
        return elements.isEmpty();
    }

    // Get a list of key-value pairs as a RuntimeArray
    public RuntimeArray entryArray() {
        RuntimeArray array = new RuntimeArray();
        for (Map.Entry<String, Runtime> entry : elements.entrySet()) {
            array.push(new Runtime(entry.getKey()));
            array.push(entry.getValue());
        }
        return array;
    }

    // Convert a RuntimeArray to a RuntimeHash
    public static RuntimeHash fromArray(RuntimeArray array) {
        RuntimeHash hash = new RuntimeHash();
        for (int i = 0; i < array.size(); i += 2) {
            if (i + 1 < array.size()) {
                String key = array.get(i).toString();
                Runtime value = array.get(i + 1);
                hash.put(key, value);
            }
        }
        return hash;
    }

    // Get the list value of the hash
    public RuntimeList getList() {
        return new RuntimeList(this);
    }

    // Get the scalar value of the hash
    public Runtime getScalar() {
        return new Runtime(1);  // XXX check this
    }

    // Convert the hash to a string (for debugging purposes)
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Runtime> entry : elements.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(entry.getKey()).append(": ").append(entry.getValue().toString());
        }
        sb.append("}");
        return sb.toString();
    }
}
