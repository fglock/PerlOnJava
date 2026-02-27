package org.perlonjava.runtime.runtimetypes;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarTrue;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarUndef;

/**
 * The RuntimeList class simulates a Perl list.
 * It provides methods to manipulate and access a dynamic list of Perl values.
 */
public class RuntimeList extends RuntimeBase {
    // List to hold the elements of the list.
    public List<RuntimeBase> elements;

    // Constructor
    public RuntimeList() {
        this.elements = new ArrayList<>();
    }

    public RuntimeList(List<RuntimeScalar> list) {
        this.elements = new ArrayList<>(list);
    }

    public RuntimeList(RuntimeBase... values) {
        this.elements = new ArrayList<>();
        for (RuntimeBase value : values) {
            Iterator<RuntimeScalar> iterator = value.iterator();
            while (iterator.hasNext()) {
                this.elements.add(iterator.next());
            }
        }
    }

    /**
     * Constructs a RuntimeList with a single scalar value.
     *
     * @param value The initial scalar value for the list.
     */
    public RuntimeList(RuntimeScalar value) {
        this.elements = new ArrayList<>();
        this.elements.add(value);
    }

    /**
     * Constructs a RuntimeList from another RuntimeList.
     *
     * @param value The RuntimeList to initialize this list with.
     */
    public RuntimeList(RuntimeList value) {
        this.elements = value.elements;
    }

    /**
     * Constructs a RuntimeList from a RuntimeArray.
     *
     * @param value The RuntimeArray to initialize this list with.
     */
    public RuntimeList(RuntimeArray value) {
        this.elements = new ArrayList<>();
        this.elements.add(value);
    }

    /**
     * Constructs a RuntimeList from a RuntimeHash.
     *
     * @param value The RuntimeHash to initialize this list with.
     */
    public RuntimeList(RuntimeHash value) {
        this.elements = new ArrayList<>();
        this.elements.add(value);
    }

    /**
     * Adds the elements of this list to another RuntimeList.
     *
     * @param list The RuntimeList to which elements will be added.
     */
    public void addToList(RuntimeList list) {
        for (RuntimeBase elem : elements) {
            list.add(elem);
        }
        this.elements.clear(); // Consume the list
    }

    /**
     * Adds the elements of this list to a RuntimeArray.
     *
     * @param array The RuntimeArray to which elements will be added.
     */
    public void addToArray(RuntimeArray array) {
        int size = this.size();
        for (int i = 0; i < size; i++) {
            this.elements.get(i).addToArray(array);
        }
        this.elements.clear(); // Consume the list
    }

    /**
     * Adds the scalar value of this list to a RuntimeScalar.
     *
     * @param scalar The RuntimeScalar object.
     * @return The scalar with the list's scalar value set.
     */
    public RuntimeScalar addToScalar(RuntimeScalar scalar) {
        return scalar.set(this.scalar());
    }

    /**
     * Adds an element to the list.
     *
     * @param value The value to add.
     */
    public void add(RuntimeBase value) {
        // CRITICAL: RuntimeControlFlowList must NOT be flattened!
        // It's a control flow marker that should propagate, not a normal list
        if (value instanceof RuntimeControlFlowList) {
            // This is a control flow marker - should not happen in normal code
            // If we reach here, it means a control flow marker wasn't caught properly
            // For now, just add it as-is to avoid corrupting the marker
            this.elements.add(value);
        } else if (value instanceof RuntimeList list) {
            this.elements.addAll(list.elements);
        } else {
            this.elements.add(value);
        }
    }

    public void add(RuntimeScalar value) {
        this.elements.add(value);
    }

    public void add(RuntimeArray value) {
        this.elements.add(value);
    }

    public void add(RuntimeHash value) {
        this.elements.add(value);
    }

    /**
     * Adds an integer value to the list.
     *
     * @param value The integer value to add.
     */
    public void add(int value) {
        this.elements.add(new RuntimeScalar(value));
    }

    /**
     * Adds a string value to the list.
     *
     * @param value The string value to add.
     */
    public void add(String value) {
        this.elements.add(new RuntimeScalar(value));
    }

    /**
     * Adds a double value to the list.
     *
     * @param value The double value to add.
     */
    public void add(Double value) {
        this.elements.add(new RuntimeScalar(value));
    }

    /**
     * Merges another RuntimeList into this list.
     *
     * @param value The RuntimeList to merge.
     */
    public void add(RuntimeList value) {
        int size = value.size();
        for (int i = 0; i < size; i++) {
            this.elements.add(value.elements.get(i));
        }
    }

    /**
     * Gets the size of the list.
     *
     * @return The number of elements in the list.
     */
    public int size() {
        return elements.size();
    }

    public boolean isEmpty() {
        return elements.isEmpty();
    }

    /**
     * Counts the total number of elements in the list, including nested elements.
     *
     * @return The total number of elements.
     */
    public int countElements() {
        int count = 0;
        for (RuntimeBase elem : elements) {
            count = count + elem.countElements();
        }
        return count;
    }

    public RuntimeList clone() {
        RuntimeList newList = new RuntimeList();
        for (RuntimeBase elem : elements) {
            for (RuntimeScalar scalar : elem) {
                newList.add(scalar.clone());
            }
        }
        return newList;
    }

    /**
     * Gets the array value of the list as aliases into an array.
     *
     * @param arr The array to set aliases into.
     * @return The updated array with aliases.
     */
    public RuntimeArray setArrayOfAlias(RuntimeArray arr) {
        for (RuntimeBase elem : elements) {
            elem.setArrayOfAlias(arr);
        }
        return arr;
    }

    /**
     * Gets the list value of the list.
     *
     * @return This RuntimeList.
     */
    public RuntimeList getList() {
        return this;
    }

    public static void resolveMatchProxies(RuntimeList list) {
        for (int i = 0; i < list.elements.size(); i++) {
            RuntimeBase elem = list.elements.get(i);
            if (elem instanceof ScalarSpecialVariable ssv) {
                list.elements.set(i, ssv.getValueAsScalar());
            }
        }
    }

    /**
     * Evaluates the boolean representation of the list.
     *
     * @return True if the scalar value of the list is true.
     */
    public boolean getBoolean() {
        return scalar().getBoolean();
    }

    /**
     * Checks if the list is defined.
     *
     * @return True if the scalar value of the list is defined.
     */
    public boolean getDefinedBoolean() {
        return scalar().getDefinedBoolean();
    }

    /**
     * Throws an exception as the 'keys' operation is not implemented for lists.
     *
     * @throws PerlCompilerException Always thrown as 'keys' is not implemented.
     */
    public RuntimeArray keys() {
        throw new PerlCompilerException("Type of arg 1 to keys must be hash or array");
    }

    /**
     * Throws an exception as the 'values' operation is not implemented for lists.
     *
     * @throws PerlCompilerException Always thrown as 'values' is not implemented.
     */
    public RuntimeArray values() {
        throw new PerlCompilerException("Type of arg 1 to values must be hash or array");
    }

    /**
     * Throws an exception as the 'each' operation is not implemented for lists.
     *
     * @throws PerlCompilerException Always thrown as 'each' is not implemented.
     */
    public RuntimeList each(int ctx) {
        throw new PerlCompilerException("Type of arg 1 to each must be hash or array");
    }

    /**
     * Removes the last character from each element in the list.
     *
     * @return A scalar representing the result of the chop operation.
     */
    public RuntimeScalar chop() {
        if (this.isEmpty()) {
            this.elements.add(GlobalVariable.getGlobalVariable("main::_"));
        }
        RuntimeScalar result = new RuntimeScalar("");
        Iterator<RuntimeScalar> iterator = this.iterator();
        while (iterator.hasNext()) {
            result = iterator.next().chop();
        }
        return result;
    }

    /**
     * Removes the trailing newline from each element in the list.
     *
     * @return A scalar representing the result of the chomp operation.
     */
    public RuntimeScalar chomp() {
        if (this.isEmpty()) {
            this.elements.add(GlobalVariable.getGlobalVariable("main::_"));
        }
        int count = 0;
        Iterator<RuntimeScalar> iterator = this.iterator();
        while (iterator.hasNext()) {
            count = count + iterator.next().chomp().getInt();
        }
        return new RuntimeScalar(count);
    }

    /**
     * Gets the scalar value of the list.
     *
     * @return The scalar value of the last element in the list.
     */
    public RuntimeScalar scalar() {
        if (isEmpty()) {
            return scalarUndef; // Return undefined if empty
        }
        // XXX expand the last element
        return elements.getLast().scalar();
    }

    /**
     * Special scalar conversion for stat/lstat operators.
     * In Perl, stat in scalar context returns:
     * - "" (empty string) if stat fails (empty list)
     * - 1 (true) if stat succeeds (non-empty list)
     */
    public RuntimeScalar statScalar() {
        if (isEmpty()) {
            return new RuntimeScalar(""); // Empty string, not undef
        }
        return scalarTrue; // Return 1 for success
    }

    /**
     * Creates a reference from a list.
     * For single-element lists (e.g., from constant subs), creates a reference to that element.
     * For empty or multi-element lists, this is an error in scalar context.
     *
     * @return A RuntimeScalar reference to the list element
     * @throws PerlCompilerException if the list doesn't contain exactly one element
     */
    public RuntimeScalar createReference() {
        if (elements.size() == 1) {
            // Single element list - create reference to that element
            return elements.get(0).scalar().createReference();
        }
        // Empty or multi-element list in reference context is an error
        throw new PerlCompilerException("Can't create reference to list with " + elements.size() + " elements");
    }

    /**
     * Creates a list reference.
     *
     * @return A new RuntimeList with references to the elements of this list.
     */
    public RuntimeList createListReference() {
        RuntimeList result = new RuntimeList();
        List<RuntimeBase> resultList = result.elements;
        for (RuntimeBase element : this.elements) {
            resultList.add(element.createReference());
        }
        return result;
    }

    /**
     * Sets the items in the list to the values in another list.
     * In list context, returns the argument list.
     * In scalar context, returns the number of elements in the argument list.
     *
     * @param value The list to set from.
     * @return The original list.
     */
    public RuntimeArray setFromList(RuntimeList value) {

        boolean hasUndefPlaceholderLhs = false;
        for (RuntimeBase elem : elements) {
            if (elem instanceof RuntimeScalarReadOnly runtimeScalarReadOnly
                    && !runtimeScalarReadOnly.getDefinedBoolean()) {
                hasUndefPlaceholderLhs = true;
                break;
            }
        }

        // Preserve RHS aliases before consuming `value`.
        // Only needed when the LHS contains `undef` placeholders.
        List<RuntimeScalar> rhsAliasElements = null;
        IdentityHashMap<RuntimeScalar, Boolean> lhsScalars = null;
        if (hasUndefPlaceholderLhs) {
            RuntimeArray rhsAliases = new RuntimeArray();
            value.setArrayOfAlias(rhsAliases);
            rhsAliasElements = rhsAliases.elements;

            lhsScalars = new IdentityHashMap<>();
            for (RuntimeBase elem : elements) {
                if (elem instanceof RuntimeScalar s && !(s instanceof RuntimeScalarReadOnly)) {
                    lhsScalars.put(s, Boolean.TRUE);
                }
            }
        }

        // Materialize the RHS once into a flat list.
        // Avoids O(n^2) from repeated RuntimeArray.shift() which does removeFirst() on ArrayList.
        RuntimeArray rhs = new RuntimeArray();
        value.addToArray(rhs);

        List<RuntimeScalar> rhsElements = rhs.elements;
        int rhsSize = rhsElements.size();
        int rhsIndex = 0;

        // Build result with assigned values (including undef for unassigned scalars)
        RuntimeArray result = new RuntimeArray();
        // Store original size for scalar context
        result.scalarContextSize = rhsSize;

        for (RuntimeBase elem : elements) {
            if (elem instanceof RuntimeScalarReadOnly runtimeScalarReadOnly && !runtimeScalarReadOnly.getDefinedBoolean()) {
                // `undef` placeholder on LHS: consume one RHS value, but return something for this position.
                // When safe, return the RHS element as an lvalue (alias). Otherwise return the pre-assignment value.
                RuntimeScalar rhsValue = (rhsIndex < rhsSize) ? rhsElements.get(rhsIndex) : new RuntimeScalar();
                RuntimeScalar rhsAlias = (rhsAliasElements != null && rhsIndex < rhsAliasElements.size())
                        ? rhsAliasElements.get(rhsIndex)
                        : null;

                // If the RHS element is also assigned on the LHS, it must not be returned as an alias
                // (it would reflect the post-assignment value rather than the pre-assignment value).
                boolean useAlias = rhsAlias != null && lhsScalars != null && !lhsScalars.containsKey(rhsAlias);

                result.elements.add(useAlias ? rhsAlias : rhsValue);

                if (rhsIndex < rhsSize) {
                    rhsIndex++;
                }
            } else if (elem instanceof RuntimeScalar runtimeScalar) {
                RuntimeScalar assigned = (rhsIndex < rhsSize) ? rhsElements.get(rhsIndex++) : new RuntimeScalar();
                runtimeScalar.set(assigned);
                result.elements.add(runtimeScalar);  // Add reference to the variable itself
            } else if (elem instanceof RuntimeArray runtimeArray) {
                List<RuntimeScalar> remaining = (rhsIndex < rhsSize)
                        ? new ArrayList<>(rhsElements.subList(rhsIndex, rhsSize))
                        : new ArrayList<>();

                // For tied arrays, we must assign through the tied interface.
                // Overwriting `elements` would discard the TieArray wrapper while leaving
                // `type == TIED_ARRAY`, leading to ClassCastException when tie operations
                // cast `array.elements` back to TieArray.
                if (runtimeArray.type == RuntimeArray.TIED_ARRAY) {
                    RuntimeList remainingList = new RuntimeList();
                    remainingList.elements.addAll(remaining);
                    runtimeArray.setFromList(remainingList);
                } else {
                    runtimeArray.elements = remaining;
                }
                result.elements.addAll(remaining);  // Use original references
                rhsIndex = rhsSize; // Consume the rest
            } else if (elem instanceof RuntimeHash runtimeHash) {
                RuntimeArray remainingArr = new RuntimeArray();
                remainingArr.elements = (rhsIndex < rhsSize)
                        ? new ArrayList<>(rhsElements.subList(rhsIndex, rhsSize))
                        : new ArrayList<>();

                RuntimeHash hash = RuntimeHash.createHashForAssignment(remainingArr);
                runtimeHash.elements = hash.elements;
                // Add lvalue references to hash elements for list assignment
                for (Map.Entry<String, RuntimeScalar> entry : hash.elements.entrySet()) {
                    result.elements.add(new RuntimeScalar(entry.getKey()));
                    result.elements.add(entry.getValue());  // Add reference to hash value
                }
                rhsIndex = rhsSize; // Consume the rest
            }
        }
        return result;
    }

    /**
     * Converts the list to a string, concatenating all elements without separators.
     *
     * @return A string representation of the list.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (RuntimeBase element : elements) {
            sb.append(element.toString());
        }
        return sb.toString();
    }

    /**
     * Undefines the elements of the list.
     *
     * @return The updated RuntimeList after undefining.
     */
    public RuntimeList undefine() {
        for (RuntimeBase elem : elements) {
            elem.undefine();
        }
        return this;
    }

    /**
     * Validates that all elements in this list are not AutovivificationArrays or AutovivificationHashes.
     * This should be called by operations that should NOT autovivify (like sort, reverse).
     *
     * @throws PerlCompilerException if any element contains an autovivification placeholder
     */
    public void validateNoAutovivification() {
        for (RuntimeBase element : elements) {
            switch (element) {
                case RuntimeArray array when array.elements instanceof AutovivificationArray ->
                        throw new PerlCompilerException("Can't use an undefined value as an ARRAY reference");

                case RuntimeHash hash when hash.elements instanceof AutovivificationHash ->
                        throw new PerlCompilerException("Can't use an undefined value as a HASH reference");

                default -> {
                    // Element is valid, continue checking
                }
            }
        }
    }

    /**
     * Saves the current state of the instance.
     *
     * <p>This method creates a snapshot of the current elements,
     * and pushes it onto a static stack for later restoration. After saving, it clears
     * the current elements and resets the values.
     */
    @Override
    public void dynamicSaveState() {
        for (RuntimeBase elem : elements) {
            elem.dynamicSaveState();
        }
    }

    /**
     * Restores the most recently saved state of the instance.
     *
     * <p>This method pops the most recent state from the static stack and restores
     * the elements. If no state is saved, it does nothing.
     */
    @Override
    public void dynamicRestoreState() {
        // Note: this method is probably not needed,
        // because the elements are handled by their respective classes.
        for (RuntimeBase elem : elements) {
            elem.dynamicRestoreState();
        }
    }

    /**
     * Returns an iterator for the list.
     *
     * @return An iterator over the elements of the list.
     */
    public Iterator<RuntimeScalar> iterator() {
        return new RuntimeListIterator(elements);
    }

    /**
     * Inner class implementing the Iterator interface for RuntimeList.
     */
    private static class RuntimeListIterator implements Iterator<RuntimeScalar> {
        private final List<RuntimeBase> elements;
        private int currentIndex = 0;
        private Iterator<RuntimeScalar> currentIterator;

        public RuntimeListIterator(List<RuntimeBase> elements) {
            this.elements = elements;
            if (!elements.isEmpty()) {
                currentIterator = elements.getFirst().iterator();
            }
        }

        @Override
        public boolean hasNext() {
            while (currentIterator != null) {
                if (currentIterator.hasNext()) {
                    return true;
                }
                // Move to the next element's iterator
                currentIndex++;
                if (currentIndex < elements.size()) {
                    currentIterator = elements.get(currentIndex).iterator();
                } else {
                    currentIterator = null; // No more elements
                }
            }
            return false;
        }

        @Override
        public RuntimeScalar next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return currentIterator.next();
        }
    }
    
    // ========== Control Flow Support ==========
    
    /**
     * Check if this RuntimeList represents non-local control flow.
     * Uses instanceof check which is optimized by JIT compiler.
     * 
     * @return true if this is a RuntimeControlFlowList, false otherwise
     */
    public boolean isNonLocalGoto() {
        return this instanceof RuntimeControlFlowList;
    }
}
