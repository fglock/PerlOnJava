package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.runtime.mro.InheritanceResolver;

import java.util.*;

/**
 * The RuntimeStash class simulates Perl stash hashes.
 */
public class RuntimeStash extends RuntimeHash {
    // Static stack to store saved "local" states of RuntimeStash instances
    private static final Stack<RuntimeStash> dynamicStateStack = new Stack<>();
    // Map to store the elements of the hash
    public Map<String, RuntimeScalar> elements;
    public String namespace;
    // Iterator for traversing the hash elements
    Iterator<RuntimeScalar> hashIterator;

    /**
     * Constructor for RuntimeStash.
     * Initializes an empty hash map to store elements.
     */
    public RuntimeStash(String namespace) {
        this.namespace = namespace;
        this.elements = new HashSpecialVariable(HashSpecialVariable.Id.STASH, namespace);
        // Keep the RuntimeHash.elements field in sync with this stash view.
        // RuntimeStash defines its own `elements` field (field hiding), but inherited
        // RuntimeHash operations (e.g. setFromList used by `%{Pkg::} = ()`) operate
        // on RuntimeHash.elements.
        super.elements = this.elements;
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
     * Counts the number of elements the stash contributes when flattened in list context.
     * Each key-value pair contributes 2 elements.
     *
     * @return The number of elements (2 * number of keys).
     */
    public int countElements() {
        return size() * 2;
    }

    /**
     * Adds itself to a RuntimeArray.
     *
     * @param array The RuntimeArray to which this hash will be added.
     */
    public void addToArray(RuntimeArray array) {
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
     * Adds a key-value pair to the hash.
     *
     * @param key   The key for the hash entry.
     * @param value The value for the hash entry.
     */
    public void put(String key, RuntimeScalar value) {
        new RuntimeStashEntry(namespace + key, true).set(value);
    }

    /**
     * Retrieves a value by key.
     *
     * @param key The key for the hash entry.
     * @return The value associated with the key, or a proxy for lazy autovivification if the key does not exist.
     */
    public RuntimeScalar get(String key) {
        if (!elements.containsKey(key)) {
            // Check if any slots exist for this glob name, without auto-creating entries
            String fullKey = namespace + key;

            // Check if the scalar slot exists AND is defined (don't auto-create)
            boolean hasScalarSlot = GlobalVariable.isGlobalVariableDefined(fullKey);

            boolean hasArraySlot = GlobalVariable.existsGlobalArray(fullKey);
            boolean hasHashSlot = GlobalVariable.existsGlobalHash(fullKey);

            // Check if the code slot exists AND is defined (don't auto-create)
            boolean hasCodeSlot = GlobalVariable.isGlobalCodeRefDefined(fullKey);

            // Check if the IO slot exists AND has a real handle (don't auto-create)
            boolean hasIOSlot = GlobalVariable.isGlobalIODefined(fullKey);

            // Check if the format slot exists AND is defined (don't auto-create)
            boolean hasFormatSlot = GlobalVariable.isGlobalFormatDefined(fullKey);

            boolean hasSlots = hasScalarSlot || hasArraySlot || hasHashSlot || hasCodeSlot || hasIOSlot || hasFormatSlot;

            return new RuntimeStashEntry(namespace + key, hasSlots);
        }
        return new RuntimeStashEntry(namespace + key, true);
    }

    /**
     * Retrieves a value by key.
     *
     * @param keyScalar The RuntimeScalar representing the key for the hash entry.
     * @return The value associated with the key, or a proxy for lazy autovivification if the key does not exist.
     */
    public RuntimeScalar get(RuntimeScalar keyScalar) {
        return get(keyScalar.toString());
    }

    /**
     * Checks if a key exists in the hash.
     *
     * @param key The RuntimeScalar representing the key to check.
     * @return A RuntimeScalar indicating whether the key exists.
     */
    public RuntimeScalar exists(RuntimeScalar key) {
        return new RuntimeScalar(elements.containsKey(key.toString()));
    }

    /**
     * Deletes a key-value pair from the hash.
     *
     * @param key The RuntimeScalar representing the key to delete.
     * @return The value associated with the deleted key, or an empty RuntimeScalar if the key did not exist.
     */
    @Override
    public RuntimeScalar delete(String key) {
        return deleteGlob(key);
    }

    @Override
    public RuntimeScalar delete(RuntimeScalar key) {
        return deleteGlob(key.toString());
    }

    private RuntimeScalar deleteGlob(String k) {
        // Special handling for namespace keys (ending with "::")
        // e.g., delete $::{"Foo::"} should remove all symbols in the Foo:: namespace
        if (k.endsWith("::")) {
            return deleteNamespace(k);
        }

        // For stash, we need to delete from GlobalVariable maps and return the glob
        String fullKey = namespace + k;

        // Check if the glob exists
        boolean exists = GlobalVariable.getGlobalCodeRefsMap().containsKey(fullKey) ||
                GlobalVariable.getGlobalVariablesMap().containsKey(fullKey) ||
                GlobalVariable.getGlobalArraysMap().containsKey(fullKey) ||
                GlobalVariable.getGlobalHashesMap().containsKey(fullKey) ||
                GlobalVariable.getGlobalIORefsMap().containsKey(fullKey) ||
                GlobalVariable.getGlobalFormatRefsMap().containsKey(fullKey);

        if (!exists) {
            return new RuntimeScalar();
        }

        // Save all slot values BEFORE deleting so they can be accessed
        // on the returned glob (e.g., *{$old}{SCALAR} in namespace::clean)
        RuntimeScalar savedScalar = GlobalVariable.getGlobalVariablesMap().get(fullKey);
        RuntimeArray savedArray = GlobalVariable.getGlobalArraysMap().get(fullKey);
        RuntimeHash savedHash = GlobalVariable.getGlobalHashesMap().get(fullKey);
        RuntimeGlob savedIO = GlobalVariable.getGlobalIORefsMap().get(fullKey);
        RuntimeScalar savedCode = GlobalVariable.getGlobalCodeRefsMap().get(fullKey);

        // Delete all slots from GlobalVariable
        // Only remove from globalCodeRefs, NOT pinnedCodeRefs, to allow compiled code
        // to continue calling the subroutine (Perl caches CVs at compile time)
        GlobalVariable.getGlobalCodeRefsMap().remove(fullKey);
        GlobalVariable.getGlobalVariablesMap().remove(fullKey);
        GlobalVariable.getGlobalArraysMap().remove(fullKey);
        GlobalVariable.getGlobalHashesMap().remove(fullKey);
        GlobalVariable.getGlobalIORefsMap().remove(fullKey);
        GlobalVariable.getGlobalFormatRefsMap().remove(fullKey);

        // Removing symbols from a stash can affect method lookup.
        InheritanceResolver.invalidateCache();

        // Create a detached glob that holds the saved slot values including CODE.
        // In Perl 5, delete $stash->{name} returns the glob with all its old slot values.
        // Use globName=null so getGlobSlot() uses local slots instead of GlobalVariable.
        RuntimeGlob detached = RuntimeGlob.createDetachedWithSlots(
                savedScalar, savedArray, savedHash, savedIO, savedCode);

        return detached;
    }

    /**
     * Deletes an entire namespace from the stash.
     * When Perl does delete $::{"Foo::"}, all symbols in the Foo:: namespace
     * should be removed, making Foo->can("bar") return false and preventing
     * spurious "Subroutine redefined" warnings when the namespace is re-populated.
     */
    private RuntimeScalar deleteNamespace(String k) {
        // Compute the prefix for child symbols.
        // For main:: stash: symbols are stored as "Foo::bar" (not "main::Foo::bar"),
        //   so the prefix is just k itself (e.g., "Foo::")
        // For other stashes: symbols are stored as "Outer::Inner::bar",
        //   so the prefix is namespace + k (e.g., "Outer::" + "Inner::" = "Outer::Inner::")
        String childPrefix = "main::".equals(namespace) ? k : namespace + k;

        // Remove all symbols with this prefix from all global maps (prefix-based removal)
        GlobalVariable.getGlobalCodeRefsMap().keySet().removeIf(key -> key.startsWith(childPrefix));
        GlobalVariable.getGlobalVariablesMap().keySet().removeIf(key -> key.startsWith(childPrefix));
        GlobalVariable.getGlobalArraysMap().keySet().removeIf(key -> key.startsWith(childPrefix));
        GlobalVariable.getGlobalHashesMap().keySet().removeIf(key -> key.startsWith(childPrefix));
        GlobalVariable.getGlobalIORefsMap().keySet().removeIf(key -> key.startsWith(childPrefix));
        GlobalVariable.getGlobalFormatRefsMap().keySet().removeIf(key -> key.startsWith(childPrefix));

        // Clear pinned code refs so deleted subs don't get resurrected
        // by getGlobalCodeRef() lookups (e.g., in SubroutineParser redefinition check)
        GlobalVariable.clearPinnedCodeRefsForNamespace(childPrefix);

        // Clear stash alias if any
        GlobalVariable.clearStashAlias(childPrefix);

        // Method resolution and package existence caches are now stale
        InheritanceResolver.invalidateCache();
        GlobalVariable.clearPackageCache();

        return new RuntimeScalar();
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
        RuntimeList result = new RuntimeList();
        List<RuntimeBase> outElements = result.elements;
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
        List<RuntimeBase> outElements = result.elements;
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
        RuntimeArray list = new RuntimeArray();
        for (String key : elements.keySet()) {
            RuntimeArray.push(list, new RuntimeScalar(key));
        }
        hashIterator = null; // keys resets the iterator
        // Set scalarContextSize so that keys() in scalar context returns the count
        list.scalarContextSize = list.elements.size();
        return list;
    }

    /**
     * The values() operator for hashes.
     *
     * @return A RuntimeArray containing the values of the hash.
     */
    public RuntimeArray values() {
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

    /**
     * Returns an iterator over the elements of type RuntimeScalar.
     *
     * @return An Iterator<RuntimeScalar> for iterating over the elements.
     */
    public Iterator<RuntimeScalar> iterator() {
        return new RuntimeStashIterator();
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
     * Undefines the elements of the hash.
     * This method clears all elements in the hash.
     *
     * @return The current RuntimeStash instance after undefining its elements.
     */
    public RuntimeStash undefine() {
        // Perl: undef %pkg:: clears the package symbol table and makes the stash anonymous.
        // We must remove all slots from the GlobalVariable maps, not just clear the view.
        String prefix = this.namespace;

        GlobalVariable.clearStashAlias(prefix);

        GlobalVariable.getGlobalVariablesMap().keySet().removeIf(k -> k.startsWith(prefix));
        GlobalVariable.getGlobalArraysMap().keySet().removeIf(k -> k.startsWith(prefix));
        GlobalVariable.getGlobalHashesMap().keySet().removeIf(k -> k.startsWith(prefix));
        GlobalVariable.getGlobalCodeRefsMap().keySet().removeIf(k -> k.startsWith(prefix));
        GlobalVariable.getGlobalIORefsMap().keySet().removeIf(k -> k.startsWith(prefix));
        GlobalVariable.getGlobalFormatRefsMap().keySet().removeIf(k -> k.startsWith(prefix));

        this.elements.clear();

        // Make existing blessed objects become anonymous (__ANON__).
        // namespace is stored with trailing "::".
        String className = prefix.endsWith("::") ? prefix.substring(0, prefix.length() - 2) : prefix;
        NameNormalizer.anonymizeBlessId(className);

        // Method resolution depends on the stash.
        InheritanceResolver.invalidateCache();
        GlobalVariable.clearPackageCache();
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
        // Create a new RuntimeStash to save the current state
        RuntimeStash currentState = new RuntimeStash(this.namespace);
        currentState.elements = new HashMap<>(this.elements);
        ((RuntimeHash) currentState).elements = currentState.elements;
        currentState.blessId = this.blessId;
        dynamicStateStack.push(currentState);
        // Clear the hash
        this.elements.clear();
        super.elements = this.elements;
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
            RuntimeStash previousState = dynamicStateStack.pop();
            this.elements = previousState.elements;
            super.elements = this.elements;
            this.blessId = previousState.blessId;
        }
    }

    /**
     * Inner class implementing the Iterator interface for iterating over hash elements.
     */
    private class RuntimeStashIterator implements Iterator<RuntimeScalar> {
        private final Iterator<Map.Entry<String, RuntimeScalar>> entryIterator;
        private Map.Entry<String, RuntimeScalar> currentEntry;
        private boolean returnKey;

        /**
         * Constructs a RuntimeStashIterator for iterating over hash elements.
         */
        public RuntimeStashIterator() {
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
