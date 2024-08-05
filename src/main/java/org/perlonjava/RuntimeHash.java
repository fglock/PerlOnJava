import java.util.HashMap;
import java.util.Map;

/**
 * The Hash class simulates Perl hashes.
 *
 * <p>In Perl, a hash is an associative array, meaning it is a collection of key-value pairs. This
 * class tries to mimic this behavior using a map of string keys to Runtime objects, which can hold
 * any type of Perl scalar value.
 */
public class RuntimeHash {
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
