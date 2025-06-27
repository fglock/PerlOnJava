package org.perlonjava.runtime;

import java.util.*;

import static org.perlonjava.runtime.RuntimeScalarType.HASHREFERENCE;

/**
 * The RuntimeHash class simulates Perl hashes.
 *
 * <p>In Perl, a hash is an associative array, meaning it is a collection of key-value pairs. This
 * class tries to mimic this behavior using a map of string keys to RuntimeScalar objects, which can hold
 * any type of Perl scalar value.
 */
public class RuntimeHash extends RuntimeBaseEntity implements RuntimeScalarReference, DynamicState {
    // Static stack to store saved "local" states of RuntimeHash instances
    private static final Stack<RuntimeHash> dynamicStateStack = new Stack<>();
    // Map to store the elements of the hash
    public Map<String, RuntimeScalar> elements;
    // Iterator for traversing the hash elements
    Iterator<RuntimeScalar> hashIterator;

    /**
     * Constructor for RuntimeHash.
     * Initializes an empty hash map to store elements.
     */
    public RuntimeHash() {
        this.elements = new HashMap<>();
    }

    /**
     * Creates a hash with the elements of a list.
     *
     * @param value The RuntimeDataProvider containing the elements to populate the hash.
     * @return A new RuntimeHash populated with the elements from the list.
     */
    public static RuntimeHash createHash(RuntimeDataProvider value) {
        RuntimeHash result = new RuntimeHash();
        Map<String, RuntimeScalar> resultHash = result.elements;
        Iterator<RuntimeScalar> iterator = value.iterator();
        while (iterator.hasNext()) {
            String key = iterator.next().toString();
            RuntimeScalar val = iterator.hasNext() ? new RuntimeScalar(iterator.next()) : new RuntimeScalar();
            resultHash.put(key, val);
        }
        return result;
    }

    /**
     * Creates a hash reference with the elements of a list.
     *
     * @param value The RuntimeDataProvider containing the elements to populate the hash.
     * @return A RuntimeScalar representing the hash reference.
     */
    public static RuntimeScalar createHashRef(RuntimeDataProvider value) {
        return createHash(value).createReference();
    }

    /**
     * Counts the number of elements in the hash.
     *
     * @return The number of elements in the hash.
     */
    public int countElements() {
        return size();
    }

    /**
     * Adds itself to a RuntimeArray.
     *
     * @param array The RuntimeArray to which this hash will be added.
     */
    public void addToArray(RuntimeArray array) {

        if (this.elements instanceof AutovivificationHash) {
            throw new PerlCompilerException("Can't use an undefined value as an HASH reference");
        }

        List<RuntimeScalar> elements = array.elements;
        for (Map.Entry<String, RuntimeScalar> entry : this.elements.entrySet()) {
            elements.add(new RuntimeScalar(entry.getKey()));
            elements.add(new RuntimeScalar(entry.getValue()));
        }
    }

    /**
     * Adds itself to a RuntimeScalar.
     *
     * @param scalar The RuntimeScalar to which this hash will be added.
     * @return The updated RuntimeScalar.
     */
    public RuntimeScalar addToScalar(RuntimeScalar scalar) {
        return scalar.set(this.scalar());
    }

    /**
     * Replaces the entire hash contents with key-value pairs from a list.
     * This method implements Perl's list assignment to a hash: %hash = (key1, val1, key2, val2, ...)
     *
     * @param value The RuntimeList containing alternating keys and values to populate the hash.
     * @return A RuntimeArray containing this hash (for method chaining or further operations).
     */
    public RuntimeArray setFromList(RuntimeList value) {

        if (this.elements instanceof AutovivificationHash hashProxy) {
            hashProxy.vivify(this);
        }

        // Create a new hash from the provided list and replace our elements
        RuntimeHash hash = createHash(value);
        this.elements = hash.elements;
        return new RuntimeArray(this);
    }

    /**
     * Adds a key-value pair to the hash.
     *
     * @param key   The key for the hash entry.
     * @param value The value for the hash entry.
     */
    public void put(String key, RuntimeScalar value) {
        elements.put(key, value);
    }

    /**
     * Retrieves a value by key.
     *
     * @param key The key for the hash entry.
     * @return The value associated with the key, or a proxy for lazy autovivification if the key does not exist.
     */
    public RuntimeScalar get(String key) {
        if (elements.containsKey(key)) {
            return elements.get(key);
        }
        // Lazy autovivification
        return new RuntimeHashProxyEntry(this, key);
    }

    /**
     * Retrieves a value by key.
     *
     * @param keyScalar The RuntimeScalar representing the key for the hash entry.
     * @return The value associated with the key, or a proxy for lazy autovivification if the key does not exist.
     */
    public RuntimeScalar get(RuntimeScalar keyScalar) {
        String key = keyScalar.toString();
        if (elements.containsKey(key)) {
            return elements.get(key);
        }
        // Lazy autovivification
        return new RuntimeHashProxyEntry(this, key);
    }

    /**
     * Checks if a key exists in the hash.
     *
     * @param key The RuntimeScalar representing the key to check.
     * @return A RuntimeScalar indicating whether the key exists.
     */
    public RuntimeScalar exists(RuntimeScalar key) {

        if (this.elements instanceof AutovivificationHash hashProxy) {
            hashProxy.vivify(this);
        }

        return new RuntimeScalar(elements.containsKey(key.toString()));
    }

    public RuntimeScalar exists(String key) {

        if (this.elements instanceof AutovivificationHash hashProxy) {
            hashProxy.vivify(this);
        }

        return new RuntimeScalar(elements.containsKey(key));
    }

    public boolean containsKey(String key) {
        return elements.containsKey(key);
    }

    /**
     * Deletes a key-value pair from the hash.
     *
     * @param key The RuntimeScalar representing the key to delete.
     * @return The value associated with the deleted key, or an empty RuntimeScalar if the key did not exist.
     */
    public RuntimeScalar delete(RuntimeScalar key) {

        if (this.elements instanceof AutovivificationHash hashProxy) {
            hashProxy.vivify(this);
        }

        String k = key.toString();
        if (elements.containsKey(k)) {
            return new RuntimeScalar(elements.remove(k));
        }
        return new RuntimeScalar();
    }

    /**
     * Creates a reference to the hash.
     *
     * @return A RuntimeScalar representing the hash reference.
     */
    public RuntimeScalar createReference() {
        RuntimeScalar result = new RuntimeScalar();
        result.type = HASHREFERENCE;
        result.value = this;
        return result;
    }

    /**
     * Gets the size of the hash.
     *
     * @return The number of key-value pairs in the hash.
     */
    public int size() {
        return elements.size();
    }

    /**
     * Retrieves the boolean value of the hash.
     *
     * @return True if the hash is not empty, false otherwise.
     */
    public boolean getBoolean() {
        return !elements.isEmpty();
    }

    /**
     * Retrieves the defined boolean value of the hash.
     *
     * @return Always true, as hashes are always considered defined.
     */
    public boolean getDefinedBoolean() {
        return true;
    }

    /**
     * Gets the list value of the hash.
     *
     * @return A RuntimeList containing the elements of the hash.
     */
    public RuntimeList getList() {
        return new RuntimeList(this);
    }

    /**
     * Gets the scalar value of the hash.
     *
     * @return A RuntimeScalar representing the size of the hash.
     */
    public RuntimeScalar scalar() {
        return new RuntimeScalar(this.size());
    }

    /**
     * Slices the hash: @x{"a", "b"}
     *
     * @param value The RuntimeList containing the keys to slice.
     * @return A RuntimeList containing the values associated with the specified keys.
     */
    public RuntimeList getSlice(RuntimeList value) {

        if (this.elements instanceof AutovivificationHash hashProxy) {
            hashProxy.vivify(this);
        }

        RuntimeList result = new RuntimeList();
        List<RuntimeBaseEntity> outElements = result.elements;
        Iterator<RuntimeScalar> iterator = value.iterator();
        while (iterator.hasNext()) {
            outElements.add(this.get(iterator.next()));
        }
        return result;
    }

    /**
     * Deletes a slice of the hash.
     *
     * @param value The RuntimeList containing the keys to delete.
     * @return A RuntimeList containing the values associated with the deleted keys.
     */
    public RuntimeList deleteSlice(RuntimeList value) {
        RuntimeList result = new RuntimeList();
        List<RuntimeBaseEntity> outElements = result.elements;
        Iterator<RuntimeScalar> iterator = value.iterator();
        while (iterator.hasNext()) {
            outElements.add(this.delete(iterator.next()));
        }
        return result;
    }

    /**
     * The keys() operator for hashes.
     *
     * @return A RuntimeArray containing the keys of the hash.
     */
    public RuntimeArray keys() {

        if (this.elements instanceof AutovivificationHash hashProxy) {
            hashProxy.vivify(this);
        }

        RuntimeArray list = new RuntimeArray();
        for (String key : elements.keySet()) {
            RuntimeArray.push(list, new RuntimeScalar(key));
        }
        hashIterator = null; // keys resets the iterator
        return list;
    }

    /**
     * The values() operator for hashes.
     *
     * @return A RuntimeArray containing the values of the hash.
     */
    public RuntimeArray values() {

        if (this.elements instanceof AutovivificationHash hashProxy) {
            hashProxy.vivify(this);
        }

        RuntimeArray list = new RuntimeArray();
        for (RuntimeScalar value : elements.values()) {
            RuntimeArray.push(list, value); // push an alias to the value
        }
        hashIterator = null; // values resets the iterator
        return list;
    }

    /**
     * The each() operator for hashes.
     *
     * @return A RuntimeList containing the next key-value pair, or an empty list if the iterator is exhausted.
     */
    public RuntimeList each() {

        if (this.elements instanceof AutovivificationHash hashProxy) {
            hashProxy.vivify(this);
        }

        if (hashIterator == null) {
            hashIterator = iterator();
        }
        if (hashIterator.hasNext()) {
            return new RuntimeList(hashIterator.next(), hashIterator.next());
        }
        hashIterator = null;
        return new RuntimeList();
    }

    /**
     * Performs the chop operation on the hash.
     *
     * @return A RuntimeScalar representing the result of the chop operation.
     */
    public RuntimeScalar chop() {
        return this.values().chop();
    }

    /**
     * Performs the chomp operation on the hash.
     *
     * @return A RuntimeScalar representing the result of the chomp operation.
     */
    public RuntimeScalar chomp() {
        return this.values().chop();
    }

    /**
     * Returns an iterator over the elements of type RuntimeScalar.
     *
     * @return An Iterator<RuntimeScalar> for iterating over the elements.
     */
    public Iterator<RuntimeScalar> iterator() {
        return new RuntimeHashIterator();
    }

    /**
     * Converts the hash to a string (for debugging purposes).
     *
     * @return A string representation of the hash.
     */
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

    /**
     * Returns a string representation of the hash reference.
     *
     * @return A string in the format "HASH(hashCode)".
     */
    public String toStringRef() {
        String ref = "HASH(0x" + Integer.toHexString(this.hashCode()) + ")";
        return (blessId == 0
                ? ref
                : NameNormalizer.getBlessStr(blessId) + "=" + ref);
    }

    /**
     * Returns an integer representation of the hash reference.
     *
     * @return The hash code of this instance.
     */
    public int getIntRef() {
        return this.hashCode();
    }

    /**
     * Returns a double representation of the hash reference.
     *
     * @return The hash code of this instance as a double.
     */
    public double getDoubleRef() {
        return this.hashCode();
    }

    /**
     * Returns a boolean representation of the hash reference.
     *
     * @return Always true, indicating the presence of the hash reference.
     */
    public boolean getBooleanRef() {
        return true;
    }

    /**
     * Undefines the elements of the hash.
     * This method clears all elements in the hash.
     *
     * @return The current RuntimeHash instance after undefining its elements.
     */
    public RuntimeHash undefine() {
        this.elements.clear();
        return this;
    }

    /**
     * Converts the hash to a string (for debugging purposes).
     *
     * @return A string representation of the hash.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, RuntimeScalar> entry : elements.entrySet()) {
            sb.append(entry.getKey()).append(entry.getValue());
        }
        return sb.toString();
    }

    /**
     * Gets hash aliases into an array.
     *
     * @param arr The RuntimeArray to which the aliases will be added.
     * @return The updated RuntimeArray.
     */
    public RuntimeArray setArrayOfAlias(RuntimeArray arr) {
        List<RuntimeScalar> arrElements = arr.elements;
        for (Map.Entry<String, RuntimeScalar> entry : this.elements.entrySet()) {
            arrElements.add(new RuntimeScalar(entry.getKey()));
            arrElements.add(entry.getValue());
        }
        return arr;
    }

    /**
     * Saves the current state of the hash.
     * This method pushes a deep copy of the current elements onto the dynamic state stack.
     */
    @Override
    public void dynamicSaveState() {
        // Create a new RuntimeHash to save the current state
        RuntimeHash currentState = new RuntimeHash();
        currentState.elements = new HashMap<>(this.elements);
        currentState.blessId = this.blessId;
        dynamicStateStack.push(currentState);
        // Clear the hash
        this.elements.clear();
        this.blessId = 0;
    }

    /**
     * Restores the hash to the last saved state.
     * This method pops the most recent state from the dynamic state stack and restores it.
     */
    @Override
    public void dynamicRestoreState() {
        if (!dynamicStateStack.isEmpty()) {
            // Restore the elements map and blessId from the most recent saved state
            RuntimeHash previousState = dynamicStateStack.pop();
            this.elements = previousState.elements;
            this.blessId = previousState.blessId;
        }
    }

    /**
     * Inner class implementing the Iterator interface for iterating over hash elements.
     */
    private class RuntimeHashIterator implements Iterator<RuntimeScalar> {
        private final Iterator<Map.Entry<String, RuntimeScalar>> entryIterator;
        private Map.Entry<String, RuntimeScalar> currentEntry;
        private boolean returnKey;

        /**
         * Constructs a RuntimeHashIterator for iterating over hash elements.
         */
        public RuntimeHashIterator() {
            this.entryIterator = elements.entrySet().iterator();
            this.returnKey = true;
        }

        /**
         * Checks if there are more elements to iterate over.
         *
         * @return True if there are more elements, false otherwise.
         */
        @Override
        public boolean hasNext() {
            return (currentEntry != null && !returnKey) || entryIterator.hasNext();
        }

        /**
         * Retrieves the next element in the iteration.
         *
         * @return The next RuntimeScalar element.
         */
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

        /**
         * Remove operation is not supported for this iterator.
         *
         * @throws UnsupportedOperationException if called
         */
        @Override
        public void remove() {
            throw new UnsupportedOperationException("Remove not supported");
        }
    }
}
