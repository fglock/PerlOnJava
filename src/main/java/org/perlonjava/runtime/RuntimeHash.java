package org.perlonjava.runtime;

import java.util.*;

import static org.perlonjava.runtime.RuntimeScalarCache.getScalarInt;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarFalse;
import static org.perlonjava.runtime.RuntimeScalarType.HASHREFERENCE;
import static org.perlonjava.runtime.RuntimeScalarType.TIED_SCALAR;

/**
 * The RuntimeHash class simulates Perl hashes.
 *
 * <p>In Perl, a hash is an associative array, meaning it is a collection of key-value pairs. This
 * class tries to mimic this behavior using a map of string keys to RuntimeScalar objects, which can hold
 * any type of Perl scalar value.
 */
public class RuntimeHash extends RuntimeBase implements RuntimeScalarReference, DynamicState {
    public static final int PLAIN_HASH = 0;
    public static final int AUTOVIVIFY_HASH = 1;
    public static final int TIED_HASH = 2;
    // Internal type of array - PLAIN_HASH, AUTOVIVIFY_HASH, or TIED_HASH
    public int type;

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
        type = PLAIN_HASH;
        elements = new HashMap<>();
    }

    /**
     * Creates a hash with the elements of a list.
     *
     * @param value The RuntimeBase containing the elements to populate the hash.
     * @return A new RuntimeHash populated with the elements from the list.
     */
    public static RuntimeHash createHash(RuntimeBase value) {
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
     * @param value The RuntimeBase containing the elements to populate the hash.
     * @return A RuntimeScalar representing the hash reference.
     */
    public static RuntimeScalar createHashRef(RuntimeBase value) {
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

        if (this.type == AUTOVIVIFY_HASH) {
            throw new PerlCompilerException("Can't use an undefined value as an HASH reference");
        }

        List<RuntimeScalar> elements = array.elements;
        Iterator<RuntimeScalar> iterator = iterator();
        while (iterator.hasNext()) {
            RuntimeScalar key = iterator.next();
            RuntimeScalar val = iterator.hasNext() ? new RuntimeScalar(iterator.next()) : new RuntimeScalar();
            elements.add(key);
            elements.add(val);
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
        return switch (type) {
            case PLAIN_HASH -> {
                // Create a new hash from the provided list and replace our elements
                RuntimeHash hash = createHash(value);
                this.elements = hash.elements;
                yield new RuntimeArray(this);
            }
            case AUTOVIVIFY_HASH -> {
                AutovivificationHash.vivify(this);
                yield  this.setFromList(value);
            }
            case TIED_HASH -> {
                TieHash.tiedClear(this);
                Iterator<RuntimeScalar> iterator = value.iterator();
                RuntimeArray result = new RuntimeArray();
                while (iterator.hasNext()) {
                    RuntimeScalar key = iterator.next();
                    RuntimeScalar val = iterator.hasNext() ? new RuntimeScalar(iterator.next()) : new RuntimeScalar();
                    TieHash.tiedStore(this, key, val);
                    RuntimeArray.push(result, key);
                    RuntimeArray.push(result, val);
                }
                yield result;
            }
            default -> throw new IllegalStateException("Unknown array type: " + type);
        };
    }

    /**
     * Adds a key-value pair to the hash.
     *
     * @param key   The key for the hash entry.
     * @param value The value for the hash entry.
     */
    public void put(String key, RuntimeScalar value) {
        switch (type) {
            case PLAIN_HASH -> {
                elements.put(key, value);
            }
            case AUTOVIVIFY_HASH -> {
                AutovivificationHash.vivify(this);
                elements.put(key, value);
            }
            case TIED_HASH -> {
                TieHash.tiedStore(this, new RuntimeScalar(key), value);
            }
            default -> throw new IllegalStateException("Unknown array type: " + type);
        };
    }

    /**
     * Retrieves a value by key.
     *
     * @param key The key for the hash entry.
     * @return The value associated with the key, or a proxy for lazy autovivification if the key does not exist.
     */
    public RuntimeScalar get(String key) {

        if (type == TIED_HASH) {
            return get(new RuntimeScalar(key));
        }

        var value = elements.get(key);
        if (value != null) {
            return value;
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
        return switch (this.type) {
            case PLAIN_HASH, AUTOVIVIFY_HASH -> {
                // Note: get() does not autovivify the hash, so we don't call AutovivificationHash.vivify()
                String key = keyScalar.toString();
                var value = elements.get(key);
                if (value != null) {
                    yield value;
                }
                // Lazy element autovivification
                yield new RuntimeHashProxyEntry(this, key);
            }

            case TIED_HASH -> {
                    RuntimeScalar v = new RuntimeScalar();
                    v.type = TIED_SCALAR;
                    v.value = new RuntimeTiedHashProxyEntry(this, keyScalar);
                    yield  v;
            }

            default -> throw new IllegalStateException("Unknown hash type: " + this.type);
        };
    }

    /**
     * Checks if a key exists in the hash.
     *
     * @param key The RuntimeScalar representing the key to check.
     * @return A RuntimeScalar indicating whether the key exists.
     */
    public RuntimeScalar exists(RuntimeScalar key) {
        return switch (type) {
            case PLAIN_HASH -> new RuntimeScalar(elements.containsKey(key.toString()));
            case AUTOVIVIFY_HASH -> scalarFalse;
            case TIED_HASH -> TieHash.tiedExists(this, key);
            default -> throw new IllegalStateException("Unknown array type: " + type);
        };
    }

    public RuntimeScalar exists(String key) {
        return switch (type) {
            case PLAIN_HASH -> new RuntimeScalar(elements.containsKey(key));
            case AUTOVIVIFY_HASH -> scalarFalse;
            case TIED_HASH -> TieHash.tiedExists(this, new RuntimeScalar(key));
            default -> throw new IllegalStateException("Unknown array type: " + type);
        };
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
        return switch (type) {
            case PLAIN_HASH ->  {
                String k = key.toString();
                var value = elements.remove(k);
                if (value != null) {
                    yield new RuntimeScalar(value);
                }
                yield new RuntimeScalar();
            }
            case AUTOVIVIFY_HASH -> {
                AutovivificationHash.vivify(this);
                yield delete(key);
            }
            case TIED_HASH -> TieHash.tiedDelete(this, key);
            default -> throw new IllegalStateException("Unknown array type: " + type);
        };
    }

    public RuntimeScalar delete(String key) {
        return switch (type) {
            case PLAIN_HASH ->  {
                var value = elements.remove(key);
                if (value != null) {
                    yield new RuntimeScalar(value);
                }
                yield new RuntimeScalar();
            }
            case AUTOVIVIFY_HASH -> {
                AutovivificationHash.vivify(this);
                yield delete(key);
            }
            case TIED_HASH -> TieHash.tiedDelete(this, new RuntimeScalar(key));
            default -> throw new IllegalStateException("Unknown array type: " + type);
        };
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
        return switch (type) {
            case PLAIN_HASH -> {
                yield elements.size();
            }
            case AUTOVIVIFY_HASH -> {
                yield 0;
            }
            case TIED_HASH -> {
                yield TieHash.tiedScalar(this).getInt();
            }
            default -> throw new IllegalStateException("Unknown array type: " + type);
        };
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

        if (this.type == AUTOVIVIFY_HASH) {
            AutovivificationHash.vivify(this);
        }

        RuntimeList result = new RuntimeList();
        List<RuntimeBase> outElements = result.elements;
        for (RuntimeScalar runtimeScalar : value) {
            outElements.add(this.get(runtimeScalar));
        }
        return result;
    }

    /**
     * Key-value slice of the hash: %x{"a", "b"}
     *
     * @param value The RuntimeList containing the keys to slice.
     * @return A RuntimeList containing alternating keys and values.
     */
    public RuntimeList getKeyValueSlice(RuntimeList value) {
        if (this.type == AUTOVIVIFY_HASH) {
            AutovivificationHash.vivify(this);
        }

        RuntimeList result = new RuntimeList();
        List<RuntimeBase> outElements = result.elements;
        for (RuntimeScalar keyScalar : value) {
            outElements.add(keyScalar);                    // Add the key
            outElements.add(this.get(keyScalar));         // Add the value
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
        List<RuntimeBase> outElements = result.elements;
        for (RuntimeScalar runtimeScalar : value) {
            outElements.add(this.delete(runtimeScalar));
        }
        return result;
    }

    /**
     * The keys() operator for hashes.
     *
     * @return A RuntimeArray containing the keys of the hash.
     */
    public RuntimeArray keys() {
        if (this.type == TIED_HASH) {
            RuntimeArray list = new RuntimeArray();
            boolean isKey = true;
            for (RuntimeScalar item : this) {
                if (isKey) {
                    RuntimeArray.push(list, item);
                }
                isKey = !isKey;
            }
            hashIterator = null; // keys resets the iterator
            return list;
        }

        if (this.type == AUTOVIVIFY_HASH) {
            AutovivificationHash.vivify(this);
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
        if (this.type == TIED_HASH) {
            RuntimeArray list = new RuntimeArray();
            boolean isKey = false;
            for (RuntimeScalar item : this) {
                if (isKey) {
                    RuntimeArray.push(list, item);
                }
                isKey = !isKey;
            }
            hashIterator = null; // keys resets the iterator
            return list;
        }

        if (this.type == AUTOVIVIFY_HASH) {
            AutovivificationHash.vivify(this);
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
    public RuntimeList each(int ctx) {

        if (this.type == AUTOVIVIFY_HASH) {
            AutovivificationHash.vivify(this);
        }

        if (hashIterator == null) {
            hashIterator = iterator();
        }
        if (hashIterator.hasNext()) {
            if (ctx == RuntimeContextType.SCALAR) {
                RuntimeScalar key = hashIterator.next();
                hashIterator.next();
                return key.getList();
            }
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
     * Converts the hash to a string (for debugging purposes).
     *
     * @return A string representation of the hash.
     */
    public String dump() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        Iterator<RuntimeScalar> iterator = iterator();
        while (iterator.hasNext()) {
            RuntimeScalar key = iterator.next();
            RuntimeScalar val = iterator.hasNext() ? new RuntimeScalar(iterator.next()) : new RuntimeScalar();
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(key).append(": ").append(val);
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
        Iterator<RuntimeScalar> iterator = iterator();
        while (iterator.hasNext()) {
            RuntimeScalar key = iterator.next();
            RuntimeScalar val = iterator.hasNext() ? new RuntimeScalar(iterator.next()) : new RuntimeScalar();
            sb.append(key).append(val);
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
        Iterator<RuntimeScalar> iterator = iterator();
        while (iterator.hasNext()) {
            RuntimeScalar key = iterator.next();
            RuntimeScalar val = iterator.hasNext() ? new RuntimeScalar(iterator.next()) : new RuntimeScalar();
            arrElements.add(key);
            arrElements.add(val);
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
     * Returns an iterator over the elements of type RuntimeScalar.
     *
     * @return An Iterator<RuntimeScalar> for iterating over the elements.
     */
    public Iterator<RuntimeScalar> iterator() {
        return switch (type) {
            case PLAIN_HASH, AUTOVIVIFY_HASH -> new RuntimeHashIterator();
            case TIED_HASH -> new RuntimeTiedHashIterator();
            default -> throw new IllegalStateException("Unknown hash type: " + type);
        };
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

    /**
     * Inner class implementing the Iterator interface for iterating over tied hash elements.
     * Uses Perl tie methods FIRSTKEY and NEXTKEY for iteration.
     */
    private class RuntimeTiedHashIterator implements Iterator<RuntimeScalar> {
        private RuntimeScalar currentKey;
        private RuntimeScalar nextKey;
        private boolean returnKey;
        private boolean initialized;

        /**
         * Constructs a RuntimeTiedHashIterator for iterating over tied hash elements.
         */
        public RuntimeTiedHashIterator() {
            this.returnKey = true;
            this.initialized = false;
            this.currentKey = null;
            this.nextKey = null;
        }

        /**
         * Initializes the iterator by calling FIRSTKEY if not already initialized.
         */
        private void initialize() {
            if (!initialized) {
                nextKey = TieHash.tiedFirstKey(RuntimeHash.this);
                initialized = true;
            }
        }

        /**
         * Checks if there are more elements to iterate over.
         *
         * @return True if there are more elements, false otherwise.
         */
        @Override
        public boolean hasNext() {
            initialize();

            // If we're about to return a value and have a current key, we have a next element
            if (currentKey != null && !returnKey) {
                return true;
            }

            // If we're about to return a key, check if nextKey is defined (not undef)
            if (returnKey && nextKey != null && nextKey.getDefinedBoolean()) {
                return true;
            }

            return false;
        }

        /**
         * Retrieves the next element in the iteration.
         *
         * @return The next RuntimeScalar element (key or value).
         */
        @Override
        public RuntimeScalar next() {
            if (!hasNext()) {
                throw new NoSuchElementException("No more elements in tied hash iteration");
            }

            if (returnKey) {
                // Return the key and prepare to return its value next
                currentKey = nextKey;
                returnKey = false;
                return new RuntimeScalar(currentKey);
            } else {
                // Return the value and prepare for the next key
                RuntimeScalar value = RuntimeHash.this.get(currentKey);

                // Get the next key for the next iteration
                nextKey = TieHash.tiedNextKey(RuntimeHash.this, currentKey);
                returnKey = true;

                return value;
            }
        }

        /**
         * Remove operation is not supported for this iterator.
         *
         * @throws UnsupportedOperationException if called
         */
        @Override
        public void remove() {
            throw new UnsupportedOperationException("Remove not supported for tied hash iterator");
        }
    }
}
