package org.perlonjava.runtime;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
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
    Iterator<RuntimeScalar> hashIterator;

    // Constructor
    public RuntimeHash() {
        this.elements = new HashMap<>();
    }

    // Create hash with the elements of a list
    public static RuntimeHash createHash(RuntimeDataProvider value) {
        RuntimeHash result = new RuntimeHash();
        Map<String, RuntimeScalar> resultHash = result.elements;
        Iterator<RuntimeScalar> iterator = value.iterator();
        while (iterator.hasNext()) {
            String key = iterator.next().toString();
            RuntimeScalar val = iterator.hasNext() ? iterator.next() : new RuntimeScalar();
            resultHash.put(key, val);
        }
        return result;
    }

    // Create hash reference with the elements of a list
    public static RuntimeScalar createHashRef(RuntimeDataProvider value) {
        return createHash(value).createReference();
    }

    public int countElements() {
        return size();
    }

    // Add itself to a RuntimeArray.
    public void addToArray(RuntimeArray array) {
        List<RuntimeBaseEntity> elements = array.elements;
        for (Map.Entry<String, RuntimeScalar> entry : this.elements.entrySet()) {
            elements.add(new RuntimeScalar(entry.getKey()));
            elements.add(new RuntimeScalar(entry.getValue()));
        }
    }

    /**
     * Add itself to a RuntimeScalar.
     *
     * @param scalar The RuntimeScalar object
     */
    public RuntimeScalar addToScalar(RuntimeScalar scalar) {
        return scalar.set(this.scalar());
    }

    // Replace the whole hash with the elements of a list
    public RuntimeArray set(RuntimeList value) {
        RuntimeHash hash = createHash(value);
        this.elements = hash.elements;
        return new RuntimeArray(new RuntimeList(this));
    }

    // Add a key-value pair to the hash
    public void put(String key, RuntimeScalar value) {
        elements.put(key, value);
    }

    // Get a value by key
    public RuntimeScalar get(String key) {
        if (elements.containsKey(key)) {
            return elements.get(key);
        }
        // lazy autovivification
        return new RuntimeHashProxy(this, key);
    }

    // Get a value by key
    public RuntimeScalar get(RuntimeScalar keyScalar) {
        String key = keyScalar.toString();
        if (elements.containsKey(key)) {
            return elements.get(key);
        }
        // lazy autovivification
        return new RuntimeHashProxy(this, key);
    }

    public RuntimeScalar exists(RuntimeScalar key) {
        return new RuntimeScalar(elements.containsKey(key.toString()));
    }

    public RuntimeScalar delete(RuntimeScalar key) {
        String k = key.toString();
        if (elements.containsKey(k)) {
            return new RuntimeScalar(elements.remove(k));
        }
        return new RuntimeScalar();
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

    public boolean getBoolean() {
        return !elements.isEmpty();
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

    // Get the list value of the hash
    public RuntimeList getList() {
        return new RuntimeList(this);
    }

    // Get the scalar value of the hash
    public RuntimeScalar scalar() {
        return new RuntimeScalar(this.size());
    }

    // Slice the hash:  @x{"a", "b"}
    public RuntimeList getSlice(RuntimeList value) {
        RuntimeList result = new RuntimeList();
        List<RuntimeBaseEntity> outElements = result.elements;
        Iterator<RuntimeScalar> iterator = value.iterator();
        while (iterator.hasNext()) {
            outElements.add(this.get(iterator.next()));
        }
        return result;
    }

    public RuntimeList deleteSlice(RuntimeList value) {
        RuntimeList result = new RuntimeList();
        List<RuntimeBaseEntity> outElements = result.elements;
        Iterator<RuntimeScalar> iterator = value.iterator();
        while (iterator.hasNext()) {
            outElements.add(this.delete(iterator.next()));
        }
        return result;
    }

    // keys() operator
    public RuntimeArray keys() {
        RuntimeArray list = new RuntimeArray();
        for (String key : elements.keySet()) {
            list.push(new RuntimeScalar(key));
        }
        hashIterator = null;    // keys resets the iterator
        return list;
    }

    // values() operator
    public RuntimeArray values() {
        RuntimeArray list = new RuntimeArray();
        for (RuntimeScalar value : elements.values()) {
            list.push(new RuntimeScalar(value));
        }
        hashIterator = null;    // values resets the iterator
        return list;
    }

    public RuntimeList each() {
        if (hashIterator == null) {
            hashIterator = iterator();
        }
        if (hashIterator.hasNext()) {
            RuntimeList list = new RuntimeList();
            list.elements.add(hashIterator.next());
            list.elements.add(hashIterator.next());
            return list;
        }
        hashIterator = null;
        return new RuntimeList();
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

    // Get Hash aliases into an Array
    public RuntimeArray setArrayOfAlias(RuntimeArray arr) {
        List<RuntimeBaseEntity> arrElements = arr.elements;
        for (Map.Entry<String, RuntimeScalar> entry : this.elements.entrySet()) {
            arrElements.add(new RuntimeScalar(entry.getKey()));
            arrElements.add(entry.getValue());
        }
        return arr;
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
