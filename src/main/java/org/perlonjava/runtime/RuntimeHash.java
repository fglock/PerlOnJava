package org.perlonjava.runtime;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The RuntimeHash class simulates Perl hashes.
 *
 * <p>In Perl, a hash is an associative array, meaning it is a collection of key-value pairs. This
 * class tries to mimic this behavior using a map of string keys to RuntimeScalar objects, which can hold
 * any type of Perl scalar value.
 */
public class RuntimeHash extends RuntimeBaseEntity implements RuntimeScalarReference {
    public Map<String, RuntimeScalar> elements;

    // Constructor
    public RuntimeHash() {
        this.elements = new HashMap<>();
    }

    // Create hash reference with the elements of a list
    public static RuntimeScalar createHashRef(RuntimeList value) {
        RuntimeArray arr = new RuntimeArray();
        value.addToArray(arr);
        if (arr.size() % 2 != 0) {  // add an undef if the array size is odd
            arr.push(new RuntimeScalar());
        }
        RuntimeScalar result = new RuntimeScalar();
        result.type = RuntimeScalarType.HASHREFERENCE;
        result.value = fromArray(arr);
        return result;
    }

    // Convert a RuntimeArray to a RuntimeHash
    public static RuntimeHash fromArray(RuntimeArray array) {
        RuntimeHash hash = new RuntimeHash();
        for (int i = 0; i < array.size(); i += 2) {
            if (i + 1 < array.size()) {
                String key = array.get(i).toString();
                RuntimeScalar value = array.get(i + 1);
                hash.put(key, value);
            }
        }
        return hash;
    }

    // Add itself to a RuntimeArray.
    public void addToArray(RuntimeArray array) {
        for (Map.Entry<String, RuntimeScalar> entry : elements.entrySet()) {
            array.push(new RuntimeScalar(entry.getKey()));
            array.push(new RuntimeScalar(entry.getValue()));
        }
    }

    // Replace the whole hash with the elements of a list
    public RuntimeList set(RuntimeList value) {
        RuntimeArray arr = new RuntimeArray();
        value.addToArray(arr);
        if (arr.size() % 2 != 0) {  // add an undef if the array size is odd
            arr.push(new RuntimeScalar());
        }
        RuntimeHash hash = fromArray(arr);
        this.elements = hash.elements;
        return new RuntimeList(this);
    }

    // Add a key-value pair to the hash
    public void put(String key, RuntimeScalar value) {
        elements.put(key, value);
    }

    // Get a value by key
    public RuntimeScalar get(String key) {
        // XXX TODO autovivification
        return elements.getOrDefault(key, new RuntimeScalar()); // Return undefined if key is not present
    }

    // Get a value by key
    public RuntimeScalar get(RuntimeScalar key) {
        // XXX TODO autovivification
        return elements.getOrDefault(key, new RuntimeScalar()); // Return undefined if key is not present
    }

    // Check if a key exists in the hash
    public boolean containsKey(String key) {
        return elements.containsKey(key);
    }

    // Remove a key-value pair by key
    public void remove(String key) {
        elements.remove(key);
    }

    // Create a reference to the Hash
    public RuntimeScalar createReference() {
        RuntimeScalar result = new RuntimeScalar();
        result.type = RuntimeScalarType.HASHREFERENCE;
        result.value = this;
        return result;
    }

    // Get the size of the hash
    public int size() {
        return elements.size();
    }

    // Clear all key-value pairs in the hash
    public void clear() {
        elements.clear();
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
        for (Map.Entry<String, RuntimeScalar> entry : elements.entrySet()) {
            array.push(new RuntimeScalar(entry.getKey()));
            array.push(entry.getValue());
        }
        return array;
    }

    // Get the array value of the Scalar
    public RuntimeArray getArrayOfAlias() {
        return this.entryArray();
    }

    // Get the list value of the hash
    public RuntimeList getList() {
        return new RuntimeList(this);
    }

    // Get the scalar value of the hash
    public RuntimeScalar getScalar() {
        return new RuntimeScalar(this.size());
    }

    // keys() operator
    public RuntimeArray keys() {
        RuntimeArray list = new RuntimeArray();
        for (String key : elements.keySet()) {
            list.push(new RuntimeScalar(key));
        }
        return list;
    }

    // values() operator
    public RuntimeArray values() {
        RuntimeArray list = new RuntimeArray();
        for (RuntimeScalar value : elements.values()) {
            list.push(new RuntimeScalar(value));
        }
        return list;
    }

    // Method to return an iterator
    public Iterator<RuntimeScalar> iterator() {
        return new RuntimeHashIterator();
    }

    // Convert the hash to a string (for debugging purposes)
    public String dump() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, RuntimeScalar> entry : elements.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(entry.getKey()).append(": ").append(entry.getValue().toString());
        }
        sb.append("}");
        return sb.toString();
    }

    public String toStringRef() {
        return "HASH(" + this.hashCode() + ")";
    }

    public int getIntRef() {
        return this.hashCode();
    }

    public double getDoubleRef() {
        return this.hashCode();
    }

    public boolean getBooleanRef() {
        return true;
    }

    public RuntimeHash undefine() {
        this.elements.clear();
        return this;
    }

    // Convert the hash to a string (for debugging purposes)
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, RuntimeScalar> entry : elements.entrySet()) {
            sb.append(entry.getKey()).append(entry.getValue());
        }
        return sb.toString();
    }

    // Inner class implementing the Iterator interface
    private class RuntimeHashIterator implements Iterator<RuntimeScalar> {
        private final Iterator<Map.Entry<String, RuntimeScalar>> entryIterator;
        private Map.Entry<String, RuntimeScalar> currentEntry;
        private boolean returnKey;

        public RuntimeHashIterator() {
            this.entryIterator = elements.entrySet().iterator();
            this.returnKey = true;
        }

        @Override
        public boolean hasNext() {
            return (currentEntry != null && !returnKey) || entryIterator.hasNext();
        }

        @Override
        public RuntimeScalar next() {
            if (returnKey) {
                currentEntry = entryIterator.next();
                returnKey = false;
                return new RuntimeScalar(currentEntry.getKey());
            } else {
                returnKey = true;
                return currentEntry.getValue();
            }
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("Remove not supported");
        }
    }
}
