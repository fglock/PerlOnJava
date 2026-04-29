package org.perlonjava.runtime.runtimetypes;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Stack;

import org.perlonjava.runtime.operators.WarnDie;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.*;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.TIED_SCALAR;

/**
 * The RuntimeArray class simulates Perl arrays.
 *
 * <p>In Perl, an array is a dynamic list of scalar values. This class tries to mimic this behavior
 * using a list of RuntimeScalar objects, which can hold any type of Perl scalar value.
 */
public class RuntimeArray extends RuntimeBase implements RuntimeScalarReference, DynamicState {

    public static final int PLAIN_ARRAY = 0;
    public static final int AUTOVIVIFY_ARRAY = 1;
    public static final int TIED_ARRAY = 2;
    public static final int READONLY_ARRAY = 3;
    // Static stack to store saved "local" states of RuntimeArray instances
    private static final Stack<RuntimeArray> dynamicStateStack = new Stack<>();
    // Internal type of array - PLAIN_ARRAY, AUTOVIVIFY_ARRAY, TIED_ARRAY, or READONLY_ARRAY
    public int type;
    public boolean strictAutovivify;
    // List to hold the elements of the array.
    public List<RuntimeScalar> elements;
    // For hash assignment in scalar context: %h = (1,2,3,4) should return 4, not 2
    public Integer scalarContextSize;
    // True if elements have been stored with refCount tracking (via push/setFromList
    // calling incrementRefCountForContainerStore). False for @_ which uses aliasing
    // (setArrayOfAlias) without refCount increments. Checked by pop/shift to decide
    // whether to mortal-ize removed elements.
    public boolean elementsOwned;
    // Iterator for traversing the hash elements
    private Integer eachIteratorIndex;


    // Constructor
    public RuntimeArray() {
        type = PLAIN_ARRAY;
        elements = new ArrayList<>();
    }

    // Constructor with initial capacity
    public RuntimeArray(int initialCapacity) {
        type = PLAIN_ARRAY;
        elements = new ArrayList<>(initialCapacity);
    }

    /**
     * Constructs a RuntimeArray from a list of RuntimeScalar elements.
     *
     * <p>This constructor initializes the array with the elements provided in the list.
     * It creates a new ArrayList to ensure the internal list is mutable.
     *
     * @param list The list of RuntimeScalar elements to initialize the array with.
     */
    public RuntimeArray(List<RuntimeScalar> list) {
        this.elements = new ArrayList<>(list);
    }

    public RuntimeArray(RuntimeBase... values) {
        this.elements = new ArrayList<>();
        for (RuntimeBase value : values) {
            for (RuntimeScalar runtimeScalar : value) {
                this.elements.add(runtimeScalar);
            }
        }
    }

    /**
     * Constructs a RuntimeArray from a RuntimeList.
     *
     * @param a The RuntimeList to initialize the array with.
     */
    public RuntimeArray(RuntimeList a) {
        this.elements = new ArrayList<>();
        for (RuntimeScalar runtimeScalar : a) {
            this.elements.add(new RuntimeScalar(runtimeScalar));
        }
    }

    /**
     * Constructs a RuntimeArray with a single scalar value.
     *
     * @param value The initial scalar value for the array.
     */
    public RuntimeArray(RuntimeScalar value) {
        this.elements = new ArrayList<>();
        this.elements.add(value);
    }

    /**
     * Removes and returns the last value of the array.
     *
     * @param runtimeArray The array to pop the last value from
     * @return The last value of the array, or undefined if empty.
     */
    public static RuntimeScalar pop(RuntimeArray runtimeArray) {
        return switch (runtimeArray.type) {
            case PLAIN_ARRAY -> {
                if (runtimeArray.isEmpty()) {
                    yield new RuntimeScalar(); // Return undefined if empty
                }
                RuntimeScalar result = runtimeArray.elements.removeLast();
                // Sparse arrays can have null elements - return undef in that case
                if (result != null) {
                    // If this element owned a refCount (stored via push or array assignment),
                    // defer the decrement so the caller can capture the value first.
                    // This matches Perl 5's sv_2mortal on popped values.
                    // Only do this for arrays that own their elements (elementsOwned=true).
                    // @_ uses aliasing (setArrayOfAlias) without refCount increments,
                    // so its elements must NOT be mortal-ized on shift/pop — doing so
                    // would corrupt the caller's refCount tracking.
                    if (runtimeArray.elementsOwned && result.refCountOwned
                            && (result.type & RuntimeScalarType.REFERENCE_BIT) != 0
                            && result.value instanceof RuntimeBase base
                            && base.refCount > 0) {
                        result.refCountOwned = false;
                        MortalList.deferDecrement(base);
                    }
                    yield result;
                }
                yield scalarUndef;
            }
            case AUTOVIVIFY_ARRAY -> {
                AutovivificationArray.vivify(runtimeArray);
                yield pop(runtimeArray); // Recursive call after vivification
            }
            case TIED_ARRAY -> TieArray.tiedPop(runtimeArray);
            case READONLY_ARRAY -> throw new PerlCompilerException("Modification of a read-only value attempted");
            default -> throw new IllegalStateException("Unknown array type: " + runtimeArray.type);
        };
    }

    /**
     * Removes and returns the first value of the array.
     *
     * @param runtimeArray The array to shift the first value from
     * @return The first value of the array, or undefined if empty.
     */
    public static RuntimeScalar shift(RuntimeArray runtimeArray) {
        return switch (runtimeArray.type) {
            case PLAIN_ARRAY -> {
                if (runtimeArray.isEmpty()) {
                    yield new RuntimeScalar(); // Return undefined if empty
                }
                RuntimeScalar result = runtimeArray.elements.removeFirst();
                // Sparse arrays can have null elements - return undef in that case
                if (result != null) {
                    // If this element owned a refCount, defer the decrement.
                    // See pop() for rationale and elementsOwned guard.
                    if (runtimeArray.elementsOwned && result.refCountOwned
                            && (result.type & RuntimeScalarType.REFERENCE_BIT) != 0
                            && result.value instanceof RuntimeBase base
                            && base.refCount > 0) {
                        result.refCountOwned = false;
                        MortalList.deferDecrement(base);
                    }
                    yield result;
                }
                yield scalarUndef;
            }
            case AUTOVIVIFY_ARRAY -> {
                AutovivificationArray.vivify(runtimeArray);
                yield shift(runtimeArray); // Recursive call after vivification
            }
            case TIED_ARRAY -> TieArray.tiedShift(runtimeArray);
            case READONLY_ARRAY -> throw new PerlCompilerException("Modification of a read-only value attempted");
            default -> throw new IllegalStateException("Unknown array type: " + runtimeArray.type);
        };
    }

    /**
     * Gets the index of the last element in the array.
     *
     * @param runtimeArray The array to get the last index from
     * @return A RuntimeScalar lvalue containing the integer index of the last element, or -1 if the array is empty
     */
    public static RuntimeScalar indexLastElem(RuntimeArray runtimeArray) {
        if (runtimeArray.type == AUTOVIVIFY_ARRAY) {
            AutovivificationArray.vivify(runtimeArray);
        }
        return new RuntimeArraySizeLvalue(runtimeArray);
    }

    /**
     * Adds values to the end of the array.
     *
     * @param runtimeArray The array to add values to.
     * @param value        The values to add.
     * @return A scalar representing the new size of the array.
     */
    public static RuntimeScalar push(RuntimeArray runtimeArray, RuntimeBase value) {
        return switch (runtimeArray.type) {
            case PLAIN_ARRAY -> {
                int sizeBefore = runtimeArray.elements.size();
                value.addToArray(runtimeArray);
                // Increment refCount for tracked references stored by push.
                // addToArray creates copies via copy constructor (no refCount increment),
                // so we must account for the container store here, matching the behavior
                // of array assignment (setFromList) which also calls this.
                for (int i = sizeBefore; i < runtimeArray.elements.size(); i++) {
                    RuntimeScalar.incrementRefCountForContainerStore(runtimeArray.elements.get(i));
                }
                runtimeArray.elementsOwned = true;
                yield getScalarInt(runtimeArray.elements.size());
            }
            case AUTOVIVIFY_ARRAY -> {
                AutovivificationArray.vivify(runtimeArray);
                yield push(runtimeArray, value);
            }
            case TIED_ARRAY -> TieArray.tiedPush(runtimeArray, value);
            case READONLY_ARRAY -> {
                // Perl allows push of empty list onto readonly array
                if (value instanceof RuntimeList list && list.size() == 0) {
                    yield getScalarInt(runtimeArray.elements.size());
                }
                throw new PerlCompilerException("Modification of a read-only value attempted");
            }
            default -> throw new IllegalStateException("Unknown array type: " + runtimeArray.type);
        };
    }

    /**
     * Adds values to the beginning of the array.
     *
     * @param runtimeArray The array to add values to.
     * @param value        The values to add.
     * @return A scalar representing the new size of the array.
     */
    public static RuntimeScalar unshift(RuntimeArray runtimeArray, RuntimeBase value) {

        return switch (runtimeArray.type) {
            case PLAIN_ARRAY -> {
                RuntimeArray arr = new RuntimeArray();
                RuntimeArray.push(arr, value);
                runtimeArray.elements.addAll(0, arr.elements);
                yield getScalarInt(runtimeArray.elements.size());
            }
            case AUTOVIVIFY_ARRAY -> {
                AutovivificationArray.vivify(runtimeArray);
                yield unshift(runtimeArray, value);
            }
            case TIED_ARRAY -> TieArray.tiedUnshift(runtimeArray, value);
            case READONLY_ARRAY -> throw new PerlCompilerException("Modification of a read-only value attempted");
            default -> throw new IllegalStateException("Unknown array type: " + runtimeArray.type);
        };
    }

    public RuntimeScalar shift() {
        return shift(this);
    }

    public RuntimeScalar push(RuntimeBase value) {
        return push(this, value);
    }

    /**
     * Adds the elements of this array to another RuntimeArray.
     *
     * @param array The RuntimeArray to which elements will be added.
     */
    public void addToArray(RuntimeArray array) {
        if (this.type == AUTOVIVIFY_ARRAY) {
            if (this.strictAutovivify) {
                throw new PerlCompilerException("Can't use an undefined value as an ARRAY reference");
            }
            return;
        }

        // For tied arrays, iterate via size()/get() to dispatch through FETCHSIZE/FETCH
        // (ArrayList.iterator() bypasses TieArray's overridden methods)
        if (this.type == TIED_ARRAY) {
            List<RuntimeScalar> targetElements = array.elements;
            int size = this.size();
            for (int i = 0; i < size; i++) {
                RuntimeScalar element = this.get(i);
                RuntimeScalar v = new RuntimeScalar();
                element.addToScalar(v);
                targetElements.add(v);
            }
            return;
        }

        List<RuntimeScalar> targetElements = array.elements;

        // If pushing array onto itself, make a copy to avoid ConcurrentModificationException
        List<RuntimeScalar> sourceElements = (this == array) ?
                new ArrayList<>(this.elements) : this.elements;

        for (RuntimeScalar arrElem : sourceElements) {
            if (arrElem == null) {
                targetElements.add(null);
            } else {
                RuntimeScalar v = new RuntimeScalar();
                arrElem.addToScalar(v);
                targetElements.add(v);
            }
        }
    }

    // Methods used by array literal constructor
    public void add(RuntimeBase value) {
        value.addToArray(this);
    }

    public void add(RuntimeScalar value) {
        // Incref immediately on anon-array-literal add so intermediate
        // MortalList.flush() calls from subsequent expressions (e.g., another
        // `bless {...}` assignment) do not drop a pending-mortal referent to
        // refCount=0 before createReferenceWithTrackedElements finalizes the
        // array. incrementRefCountForContainerStore is idempotent, so the
        // final pass in createReferenceWithTrackedElements is a no-op for
        // these. See tt_arr2.pl / TT directive.t repro.
        //
        // NOTE: This method is ONLY called from the anon-array-literal
        // emit path (EmitLiteral -> addElementToArray -> INVOKEVIRTUAL
        // "add(LRuntimeScalar;)V"), which is *always* followed by
        // `createReferenceWithTrackedElements` at the end of the literal.
        // That final call walks the array's elements and pairs each
        // incref here with a corresponding refCount-owning reference,
        // so the accounting balances.
        //
        // Do NOT port this incref into {@link RuntimeScalar#addToArray}:
        // that sister method is also used for arg-list construction
        // (`f($g)` -> args.addToArray), where no matching decref exists,
        // and the leaked +1 would break DBIC
        // t/storage/txn_scope_guard.t#18 (zombie-ref double-DESTROY
        // detection). See the long comment on
        // {@link RuntimeScalar#addToArray} for the full analysis and
        // minimal repro.
        RuntimeScalar copy = new RuntimeScalar(value);
        elements.add(copy);
        RuntimeScalar.incrementRefCountForContainerStore(copy);
    }

    public void add(RuntimeArray value) {
        value.addToArray(this);
    }

    public void add(RuntimeHash value) {
        value.addToArray(this);
    }

    public void add(RuntimeList value) {
        value.addToArray(this);
    }

    /**
     * Adds this array to a RuntimeList.
     *
     * @param list The RuntimeList to which elements will be added.
     */
    public void addToList(RuntimeList list) {
        list.add(this);
    }

    /**
     * Returns the number of elements in the array.
     *
     * @return The size of the array.
     */
    public int countElements() {
        return elements.size();
    }

    public boolean isEmpty() {
        return elements.isEmpty();
    }

    /**
     * Adds the size of the array to a RuntimeScalar.
     *
     * @param scalar The RuntimeScalar object.
     * @return The scalar with the size of the array set.
     */
    public RuntimeScalar addToScalar(RuntimeScalar scalar) {
        return scalar.set(this.size());
    }

    /**
     * Checks if a specific index exists.
     *
     * @param index The index of the value to retrieve.
     * @return Perl value True or False.
     */
    public RuntimeScalar exists(int index) {
        return switch (type) {
            case PLAIN_ARRAY -> {
                if (index < 0) {
                    index = elements.size() + index; // Handle negative indices
                }
                if (index < 0 || index >= elements.size()) {
                    yield scalarFalse;
                }
                // Check if the element at index is null
                RuntimeScalar element = elements.get(index);
                yield (element == null) ? scalarFalse : scalarTrue;
            }
            case AUTOVIVIFY_ARRAY -> scalarFalse;
            case TIED_ARRAY -> {
                int idx = index;
                if (idx < 0 && !TieArray.negativeIndicesAllowed(this)) {
                    idx = TieArray.tiedFetchSize(this).getInt() + idx;
                    if (idx < 0) {
                        yield scalarFalse;   // still negative: doesn't exist
                    }
                }
                yield TieArray.tiedExists(this, getScalarInt(idx));
            }
            case READONLY_ARRAY -> {
                if (index < 0) {
                    index = elements.size() + index; // Handle negative indices
                }
                if (index < 0 || index >= elements.size()) {
                    yield scalarFalse;
                }
                RuntimeScalar element = elements.get(index);
                yield (element == null) ? scalarFalse : scalarTrue;
            }
            default -> throw new IllegalStateException("Unknown array type: " + type);
        };
    }

    public RuntimeScalar exists(RuntimeScalar index) {
        return this.exists(index.getInt());
    }

    /**
     * Delete an array element.
     *
     * @param index The index of the value to delete.
     * @return The value deleted.
     */
    public RuntimeScalar delete(int index) {
        return switch (type) {
            case PLAIN_ARRAY -> {
                if (index < 0) {
                    index = elements.size() + index; // Handle negative indices
                }
                if (index < 0 || index >= elements.size()) {
                    yield scalarUndef;
                }
                if (index == elements.size() - 1) {
                    yield pop(this);
                }
                RuntimeScalar previous = this.get(index);
                this.elements.set(index, null);
                yield previous;
            }
            case AUTOVIVIFY_ARRAY -> scalarUndef;
            case TIED_ARRAY -> {
                int idx = index;
                if (idx < 0 && !TieArray.negativeIndicesAllowed(this)) {
                    idx = TieArray.tiedFetchSize(this).getInt() + idx;
                    if (idx < 0) {
                        yield scalarUndef;   // still negative: nothing to delete
                    }
                }
                yield TieArray.tiedDelete(this, getScalarInt(idx));
            }
            case READONLY_ARRAY -> throw new PerlCompilerException("Modification of a read-only value attempted");
            default -> throw new IllegalStateException("Unknown array type: " + type);
        };
    }

    /**
     * Deletes multiple array elements at the specified indices.
     *
     * @param indices The list of indices to delete.
     * @return A RuntimeList containing the deleted values.
     */
    public RuntimeList deleteSlice(RuntimeList indices) {
        // Collect all indices and their values first (to preserve order)
        List<Integer> indexList = new ArrayList<>();
        for (RuntimeScalar indexScalar : indices) {
            indexList.add(indexScalar.getInt());
        }

        // Create result list to store deleted values in original order
        RuntimeList result = new RuntimeList();

        // First pass: collect values in original order
        for (Integer index : indexList) {
            RuntimeScalar value = this.get(index);
            result.elements.add(value.getDefinedBoolean() ? new RuntimeScalar(value) : scalarUndef);
        }

        // Sort indices in descending order for deletion
        List<Integer> sortedIndices = new ArrayList<>(indexList);
        sortedIndices.sort((a, b) -> b.compareTo(a)); // Sort descending

        // Second pass: delete elements starting from highest index
        for (Integer index : sortedIndices) {
            this.delete(index);
        }

        return result;
    }

    /**
     * Deletes multiple array elements and returns key-value pairs: delete %array[indices]
     *
     * @param indices The list of indices to delete.
     * @return A RuntimeList containing alternating indices and values.
     */
    public RuntimeList deleteKeyValueSlice(RuntimeList indices) {
        // Collect all indices and their values first (to preserve order)
        List<Integer> indexList = new ArrayList<>();
        for (RuntimeScalar indexScalar : indices) {
            indexList.add(indexScalar.getInt());
        }

        // Create result list to store index/value pairs in original order
        RuntimeList result = new RuntimeList();

        // First pass: collect index/value pairs in original order
        for (Integer index : indexList) {
            RuntimeScalar value = this.get(index);
            result.elements.add(new RuntimeScalar(index));  // Add the index
            result.elements.add(value.getDefinedBoolean() ? new RuntimeScalar(value) : scalarUndef);  // Add the value
        }

        // Sort indices in descending order for deletion
        List<Integer> sortedIndices = new ArrayList<>(indexList);
        sortedIndices.sort((a, b) -> b.compareTo(a)); // Sort descending

        // Second pass: delete elements starting from highest index
        for (Integer index : sortedIndices) {
            this.delete(index);
        }

        return result;
    }

    public RuntimeScalar delete(RuntimeScalar index) {
        return this.delete(index.getInt());
    }

    /**
     * Implements `delete local $array[index]`.
     * Saves the current state of the array element, deletes it,
     * and arranges for restoration when the enclosing scope exits.
     */
    public RuntimeScalar deleteLocal(int index) {
        return deleteLocal(new RuntimeScalar(index));
    }

    public RuntimeScalar deleteLocal(RuntimeScalar indexScalar) {
        int index = indexScalar.getInt();
        if (index < 0) {
            index = elements.size() + index;
        }
        boolean existed = index >= 0 && index < elements.size() && elements.get(index) != null;
        RuntimeScalar savedValue = existed ? new RuntimeScalar(elements.get(index)) : null;
        RuntimeScalar returnValue = existed ? new RuntimeScalar(elements.get(index)) : new RuntimeScalar();
        int savedSize = elements.size();
        RuntimeArray self = this;
        final int idx = index;

        DynamicVariableManager.pushLocalVariable(new DynamicState() {
            @Override
            public void dynamicSaveState() {
                // Delete the element during save phase
                if (idx >= 0 && idx < self.elements.size()) {
                    if (idx == self.elements.size() - 1) {
                        // Last element - actually remove it
                        self.elements.removeLast();
                    } else {
                        self.elements.set(idx, null);
                    }
                }
            }

            @Override
            public void dynamicRestoreState() {
                // Restore original size if needed
                while (self.elements.size() < savedSize) {
                    self.elements.add(null);
                }
                if (existed) {
                    if (idx < self.elements.size()) {
                        self.elements.set(idx, savedValue);
                    }
                } else if (idx >= 0 && idx < self.elements.size()) {
                    self.elements.set(idx, null);
                }
            }
        });

        return returnValue;
    }

    /**
     * Deletes a slice of the array with local semantics: delete local @array[indices]
     * Each element is saved and restored when the current scope exits.
     *
     * @param indices The RuntimeList containing the indices to delete.
     * @return A RuntimeList containing the deleted values.
     */
    public RuntimeList deleteLocalSlice(RuntimeList indices) {
        RuntimeList result = new RuntimeList();
        List<RuntimeBase> outElements = result.elements;
        for (RuntimeScalar indexScalar : indices) {
            outElements.add(this.deleteLocal(indexScalar));
        }
        return result;
    }

    /**
     * Gets a value at a specific index.
     *
     * @param index The index of the value to retrieve.
     * @return The value at the specified index, or a proxy if out of bounds.
     */
    public RuntimeScalar get(int index) {

        if (this.type == TIED_ARRAY) {
            return get(new RuntimeScalar(index));
        }

        if (index < 0) {
            index = elements.size() + index; // Handle negative indices
        }
        if (index < 0 || index >= elements.size()) {
            // Lazy autovivification
            return new RuntimeArrayProxyEntry(this, index);
        }

        // Check if the element is null and return proxy if it is
        RuntimeScalar element = elements.get(index);
        if (element == null) {
            // Lazy autovivification for null elements
            return new RuntimeArrayProxyEntry(RuntimeArray.this, index);
        }

        return element;
    }

    /**
     * Gets a value at a specific index using a scalar.
     *
     * @param value The scalar representing the index.
     * @return The value at the specified index, or a proxy if out of bounds.
     */
    public RuntimeScalar get(RuntimeScalar value) {

        // Perl warns "Use of uninitialized value in array element" whenever an
        // undef value is used as an array subscript (both read and lvalue/
        // autoviv use this path). Matches perl's behavior under `use warnings`.
        if (value != null && value.type == RuntimeScalarType.UNDEF) {
            WarnDie.warnWithCategory(
                    new RuntimeScalar("Use of uninitialized value in array element"),
                    RuntimeScalarCache.scalarEmptyString,
                    "uninitialized");
        }

        if (this.type == TIED_ARRAY) {
            int idx = value.getInt();
            Integer outOfRangeOriginal = null;
            if (idx < 0 && !TieArray.negativeIndicesAllowed(this)) {
                // Perl normalizes negative indices (idx + FETCHSIZE) before dispatching
                // to FETCH, unless the tied package opts out via $Pkg::NEGATIVE_INDICES.
                // If the normalized result is STILL negative, Perl does not call FETCH
                // at all: reads yield undef, writes throw "Modification of non-
                // creatable array value attempted".
                int normalized = TieArray.tiedFetchSize(this).getInt() + idx;
                if (normalized < 0) {
                    outOfRangeOriginal = idx;
                } else {
                    value = new RuntimeScalar(normalized);
                }
            }
            RuntimeScalar v = new RuntimeScalar();
            v.type = TIED_SCALAR;
            v.value = new RuntimeTiedArrayProxyEntry(this, value, outOfRangeOriginal);
            return v;
        }

        int index = value.getInt();
        if (index < 0) {
            index = elements.size() + index; // Handle negative indices
        }
        if (index < 0 || index >= elements.size()) {
            // Lazy autovivification
            return new RuntimeArrayProxyEntry(this, index);
        }

        // Check if the element is null and return proxy if it is
        RuntimeScalar element = elements.get(index);
        if (element == null) {
            // Lazy autovivification for null elements
            return new RuntimeArrayProxyEntry(RuntimeArray.this, index);
        }

        return element;
    }

    /**
     * Sets the whole array to a single scalar value.
     *
     * @param value The scalar value to set.
     * @return The updated RuntimeArray.
     */
    public RuntimeArray set(RuntimeScalar value) {
        if (this.type == READONLY_ARRAY) {
            throw new PerlCompilerException("Modification of a read-only value attempted");
        }
        MortalList.deferDestroyForContainerClear(this.elements);
        this.elements.clear();
        this.elements.add(value);
        MortalList.flush();
        return this;
    }

    /**
     * Replaces the whole array with the elements of a list.
     *
     * @param list The list to set.
     * @return The updated RuntimeArray.
     */
    @Override
    public RuntimeArray setFromList(RuntimeList list) {
        return switch (type) {
            case PLAIN_ARRAY -> {
                // Check if the list contains references to this array's elements
                // If so, we need to save the values before clearing
                boolean needsCopy = false;
                for (RuntimeBase elem : list.elements) {
                    if (elem instanceof RuntimeArray && elem == this) {
                        needsCopy = true;
                        break;
                    }
                }

                int originalSize = this.elements.size();
                MortalList.deferDestroyForContainerClear(this.elements);
                if (needsCopy) {
                    // Make a defensive copy of the list before clearing
                    RuntimeList listCopy = new RuntimeList();
                    for (RuntimeBase elem : list.elements) {
                        if (elem instanceof RuntimeArray && elem == this) {
                            // Copy this array's current contents
                            for (RuntimeScalar s : this.elements) {
                                listCopy.elements.add(s);
                            }
                        } else {
                            listCopy.elements.add(elem);
                        }
                    }
                    this.elements.clear();
                    listCopy.addToArray(this);
                } else {
                    this.elements.clear();
                    list.addToArray(this);
                }

                // Increment refCount for tracked references stored in the array.
                // addToArray creates copies via the copy constructor (which doesn't
                // increment refCount), so we do it here for the final container store.
                for (RuntimeScalar elem : this.elements) {
                    RuntimeScalar.incrementRefCountForContainerStore(elem);
                }
                this.elementsOwned = true;

                // Create a new array with scalarContextSize set for assignment return value
                // This is needed for eval context where assignment should return element count
                RuntimeArray result = new RuntimeArray();
                result.elements.addAll(this.elements);
                result.scalarContextSize = this.elements.size();
                // Flush deferred DESTROY for refs removed from the container
                MortalList.flush();
                yield result;
            }
            case AUTOVIVIFY_ARRAY -> {
                AutovivificationArray.vivify(this);
                yield this.setFromList(list); // Recursive call after vivification
            }
            case TIED_ARRAY -> {
                // First, fully materialize the right-hand side list
                // This is important when the right-hand side contains tied variables
                // Use direct element addition (not push()) to avoid spurious refCount
                // increments on the temporary materialized list.
                RuntimeArray materializedList = new RuntimeArray();
                for (RuntimeScalar element : list) {
                    materializedList.elements.add(new RuntimeScalar(element));
                }

                // Now clear and repopulate from the materialized list
                TieArray.tiedClear(this);
                // Perl calls EXTEND on the tied array before the STORE loop so
                // implementations can preallocate. Tie::File relies on this to
                // extend the backing file in autodefer mode.
                int extendTo = materializedList.elements.size();
                if (extendTo > 0) {
                    TieArray.tiedExtend(this, getScalarInt(extendTo));
                }
                int index = 0;
                for (RuntimeScalar element : materializedList) {
                    TieArray.tiedStore(this, getScalarInt(index), element);
                    index++;
                }
                // Return the materialized list instead of `this` to avoid calling
                // FETCHSIZE/FETCH on the tied array after assignment.
                // CLEAR may have replaced the glob (e.g., *a = []), making the
                // tied object invalid. The result should reflect the RHS values.
                yield materializedList;
            }
            case READONLY_ARRAY -> throw new PerlCompilerException("Modification of a read-only value attempted");
            default -> throw new IllegalStateException("Unknown array type: " + type);
        };
    }

    /**
     * Set this array's contents from a list without incrementing the
     * referents' refCounts — i.e., the stored elements are <em>aliases</em>,
     * not counted strong references. This matches Perl's semantics for
     * {@code @_} and {@code @DB::args}, whose entries are aliases to the
     * caller's args and do not affect the referent's refcount.
     * <p>
     * Part of Phase 2 of {@code dev/design/refcount_alignment_plan.md}.
     * Used by {@link RuntimeCode} when populating {@code @DB::args} from
     * {@code caller()} so that a user's {@code push @kept, @DB::args}
     * creates real counted refs in {@code @kept} while the alias slots
     * in {@code @DB::args} stay non-counting.
     * <p>
     * Behavior:
     * <ol>
     *   <li>Defer-decrement any existing counted elements (like normal {@code setFromList}).</li>
     *   <li>Copy new elements in without incrementing their referents' refCounts.</li>
     *   <li>Mark {@code elementsOwned=false} so {@link #shift(RuntimeArray)}
     *       and other removal paths don't defer a spurious decrement.</li>
     * </ol>
     */
    public RuntimeArray setFromListAliased(RuntimeList list) {
        if (type != PLAIN_ARRAY) {
            // Fallback to normal setFromList for non-plain arrays; the
            // refcount-inflation risk is lower there.
            return setFromList(list);
        }
        MortalList.deferDestroyForContainerClear(this.elements);
        this.elements.clear();
        list.addToArray(this);
        // Elements are aliases: mark as non-owning. setLarge in later
        // overwrites will still work correctly because setLarge checks
        // refCountOwned before decrementing.
        for (RuntimeScalar elem : this.elements) {
            if (elem != null) elem.refCountOwned = false;
        }
        this.elementsOwned = false;
        return this;
    }

    /**
     * Creates a reference to the array.
     *
     * @return A scalar representing the array reference.
     */
    public RuntimeScalar createReference() {
        // Opt into refCount tracking when a reference to a named array is created.
        // Named arrays start at refCount=-1 (untracked). When \@array creates a
        // reference, we transition to refCount=0 (tracked, zero external refs)
        // and set localBindingExists=true to indicate a JVM local variable slot
        // holds a strong reference not counted in refCount.
        // This allows setLargeRefCounted to properly count references, and
        // scopeExitCleanupArray to skip element cleanup when external refs exist.
        // Without this, scope exit of `my @array` would destroy elements even when
        // \@array is stored elsewhere.
        if (this.refCount == -1) {
            this.refCount = 0;
            this.localBindingExists = true;
        }
        RuntimeScalar result = new RuntimeScalar();
        result.type = RuntimeScalarType.ARRAYREFERENCE;
        result.value = this;
        return result;
    }

    /**
     * Creates a reference to a fresh anonymous array (no backing named variable).
     * Unlike {@link #createReference()}, this does NOT set localBindingExists=true,
     * so callDestroy will fire when refCount reaches 0.
     * <p>
     * Used by Storable::dclone, deserializers, and other places that produce a
     * brand-new anonymous array. See {@link RuntimeHash#createAnonymousReference()}
     * for details.
     *
     * @return A scalar representing the array reference.
     */
    public RuntimeScalar createAnonymousReference() {
        if (this.refCount == -1) {
            this.refCount = 0;
        }
        RuntimeScalar result = new RuntimeScalar();
        result.type = RuntimeScalarType.ARRAYREFERENCE;
        result.value = this;
        return result;
    }

    /**
     * Creates a reference to the array and tracks refCounts for all elements.
     * Use this for anonymous array construction ([...]) where elements are copies
     * that need refCount tracking to prevent premature destruction of referents.
     *
     * @return A scalar representing the array reference.
     */
    public RuntimeScalar createReferenceWithTrackedElements() {
        // Birth-track anonymous arrays: set refCount=0 so setLarge() can
        // accurately count strong references. Anonymous arrays are only
        // reachable through references (no lexical variable slot), so
        // refCount is complete and reaching 0 means truly no strong refs.
        if (this.refCount == -1) {
            this.refCount = 0;
        }
        for (RuntimeScalar elem : this.elements) {
            RuntimeScalar.incrementRefCountForContainerStore(elem);
        }
        RuntimeScalar result = new RuntimeScalar();
        result.type = RuntimeScalarType.ARRAYREFERENCE;
        result.value = this;
        return result;
    }

    /**
     * Gets the size of the array.
     *
     * @return The size of the array.
     */
    public int size() {
        return elements.size();
    }

    /**
     * Evaluates the boolean representation of the array.
     *
     * @return True if the array is not empty.
     */
    public boolean getBoolean() {
        return !elements.isEmpty();
    }

    /**
     * Checks if the array is defined.
     *
     * @return Always true for arrays.
     */
    public boolean getDefinedBoolean() {
        return true;
    }

    /**
     * Gets the list value of the array.
     *
     * @return A RuntimeList representing the array.
     */
    public RuntimeList getList() {
        // If this array has scalarContextSize set (e.g., from keys()), don't copy elements
        // Just wrap the array itself so the scalar context behavior is preserved
        if (this.scalarContextSize != null) {
            return new RuntimeList(this);
        }

        // For tied arrays, iterate via size()/get() to dispatch through FETCHSIZE/FETCH
        // (ArrayList.iterator() bypasses TieArray's overridden methods)
        if (this.type == TIED_ARRAY) {
            RuntimeList result = new RuntimeList();
            int size = this.size();
            for (int i = 0; i < size; i++) {
                RuntimeScalar element = this.get(i);
                result.elements.add(new RuntimeScalar(element));
            }
            return result;
        }

        // Otherwise, copy all elements to ensure independence from the original array
        // This is important for returning local arrays from functions
        RuntimeList result = new RuntimeList();
        for (RuntimeScalar element : this.elements) {
            result.elements.add(element == null ? new RuntimeScalar() : new RuntimeScalar(element));
        }
        return result;
    }

    /**
     * Gets the scalar value of the array.
     *
     * @return A scalar representing the size of the array.
     */
    public RuntimeScalar scalar() {
        return switch (type) {
            case PLAIN_ARRAY -> {
                // If this array was created from hash assignment, use the original list size
                if (scalarContextSize != null) {
                    yield getScalarInt(scalarContextSize);
                }
                yield getScalarInt(elements.size());
            }
            case AUTOVIVIFY_ARRAY -> {
                if (this.strictAutovivify) {
                    throw new PerlCompilerException("Can't use an undefined value as an ARRAY reference");
                }
                yield getScalarInt(0);
            }
            case TIED_ARRAY -> TieArray.tiedFetchSize(this);
            case READONLY_ARRAY -> {
                if (scalarContextSize != null) {
                    yield getScalarInt(scalarContextSize);
                }
                yield getScalarInt(elements.size());
            }
            default -> throw new IllegalStateException("Unknown array type: " + type);
        };
    }

    // implement `$#array`
    public int lastElementIndex() {
        return switch (type) {
            case PLAIN_ARRAY -> elements.size() - 1;
            case AUTOVIVIFY_ARRAY -> {
                if (this.strictAutovivify) {
                    throw new PerlCompilerException("Can't use an undefined value as an ARRAY reference");
                }
                yield -1;
            }
            case TIED_ARRAY -> TieArray.tiedFetchSize(this).getInt() - 1;
            case READONLY_ARRAY -> elements.size() - 1;
            default -> throw new IllegalStateException("Unknown array type: " + type);
        };
    }

    public void setLastElementIndex(RuntimeScalar value) {
        switch (type) {
            case PLAIN_ARRAY -> {
                int newSize = value.getInt();
                if (newSize < -1) newSize = -1;
                int currentSize = elements.size() - 1;

                // Update the parent with the new size
                if (newSize > currentSize) {
                    for (int i = currentSize; i < newSize; i++) {
                        elements.add(null); // Fill with undefined values if necessary
                    }
                } else {
                    while (newSize < currentSize) {
                        currentSize--;
                        elements.removeLast();
                    }
                }
            }
            case AUTOVIVIFY_ARRAY -> {
                AutovivificationArray.vivify(this);
                setLastElementIndex(value);
            }
            case TIED_ARRAY -> TieArray.tiedStoreSize(this, new RuntimeScalar(value.getInt() + 1));
            case READONLY_ARRAY -> throw new PerlCompilerException("Modification of a read-only value attempted");
            default -> throw new IllegalStateException("Unknown array type: " + type);
        }
    }

    /**
     * Slices the array using a list of indices.
     *
     * @param value The list of indices to slice.
     * @return A RuntimeList representing the sliced elements.
     */
    public RuntimeList getSlice(RuntimeList value) {

        if (this.type == AUTOVIVIFY_ARRAY) {
            AutovivificationArray.vivify(this);
        }

        RuntimeList result = new RuntimeList();
        List<RuntimeBase> outElements = result.elements;
        for (RuntimeScalar runtimeScalar : value) {
            outElements.add(this.get(runtimeScalar));
        }
        return result;
    }

    /**
     * Gets a key-value slice of the array: %array[indices]
     *
     * @param value The list of indices to slice.
     * @return A RuntimeList containing alternating indices and values.
     */
    public RuntimeList getKeyValueSlice(RuntimeList value) {

        if (this.type == AUTOVIVIFY_ARRAY) {
            AutovivificationArray.vivify(this);
        }

        RuntimeList result = new RuntimeList();
        List<RuntimeBase> outElements = result.elements;
        for (RuntimeScalar indexScalar : value) {
            outElements.add(indexScalar);                    // Add the index
            outElements.add(this.get(indexScalar));          // Add the value
        }
        return result;
    }

    /**
     * Sets a slice of the array.
     *
     * @param indices A RuntimeList containing the indices to set.
     * @param values  A RuntimeList containing the values to set at those indices.
     */
    public void setSlice(RuntimeList indices, RuntimeList values) {
        if (this.type == AUTOVIVIFY_ARRAY) {
            AutovivificationArray.vivify(this);
        }

        // Iterate through indices and values in parallel
        Iterator<RuntimeBase> valueIter = values.elements.iterator();
        for (RuntimeScalar index : indices) {
            if (!valueIter.hasNext()) {
                break;  // No more values to assign
            }
            RuntimeBase value = valueIter.next();
            // Get the element at index and set its value
            this.get(index).set((RuntimeScalar) value);
        }
    }

    /**
     * Gets the keys of the array.
     *
     * @return A RuntimeArray representing the keys.
     */
    public RuntimeArray keys() {
        // Reset the each iterator when keys() is called
        this.eachIteratorIndex = null;

        int count = this.countElements();
        if (count == 0) {
            RuntimeArray empty = new RuntimeArray();
            empty.scalarContextSize = 0;
            return empty;
        }
        RuntimeArray result = new PerlRange(getScalarInt(0), getScalarInt(count - 1)).getArrayOfAlias();
        // Set scalarContextSize so that keys() in scalar context returns the count
        result.scalarContextSize = count;
        return result;
    }

    /**
     * Gets the values of the array.
     *
     * @return This RuntimeArray.
     */
    public RuntimeArray values() {
        // Reset the each iterator when values() is called
        this.eachIteratorIndex = null;
        // Return a new RuntimeArray that aliases the elements but carries
        // scalarContextSize so values() in scalar context yields the count
        // (matching real Perl). We do not set scalarContextSize on `this`
        // because that would persist and corrupt later scalar(@arr) results.
        RuntimeArray result = new RuntimeArray();
        result.elements.addAll(this.elements);
        result.scalarContextSize = this.elements.size();
        return result;
    }

    /**
     * The each() operator for arrays.
     *
     * @return A RuntimeList containing the next index-value pair, or an empty list if the iterator is exhausted.
     */
    public RuntimeList each(int ctx) {
        if (this.type == AUTOVIVIFY_ARRAY) {
            AutovivificationArray.vivify(this);
        }

        // Initialize iterator if needed
        if (eachIteratorIndex == null) {
            eachIteratorIndex = 0;
        }

        // Check if we have more elements
        if (eachIteratorIndex < elements.size()) {
            int currentIndex = eachIteratorIndex;
            RuntimeScalar indexScalar = getScalarInt(currentIndex);

            // Move to next position
            eachIteratorIndex++;

            // In scalar context, return only the index
            if (ctx == RuntimeContextType.SCALAR) {
                return new RuntimeList(indexScalar);
            }

            // In list context, return index and value
            RuntimeScalar element = elements.get(currentIndex);
            RuntimeScalar value = (element == null)
                    ? new RuntimeArrayProxyEntry(this, currentIndex)
                    : element;

            return new RuntimeList(indexScalar, value);
        }

        // Reset iterator when exhausted
        eachIteratorIndex = null;
        return new RuntimeList();
    }

    /**
     * Sets array aliases into another array.
     *
     * @param arr The array to set aliases into.
     * @return The updated array with aliases.
     */
    public RuntimeArray setArrayOfAlias(RuntimeArray arr) {
        if (this.type == TIED_ARRAY) {
            // For tied arrays, we need to fetch each element through the tied interface
            int size = this.size();  // This will call FETCHSIZE
            for (int i = 0; i < size; i++) {
                RuntimeScalar element = this.get(i);  // This will call FETCH
                arr.elements.add(element);
            }
            return arr;
        }

        if (this.type == AUTOVIVIFY_ARRAY) {
            AutovivificationArray.vivify(this);
        }

        for (int i = 0; i < this.elements.size(); i++) {
            RuntimeScalar element = this.elements.get(i);
            if (element == null) {
                arr.elements.add(new RuntimeArrayProxyEntry(this, i));
            } else {
                arr.elements.add(element);
            }
        }
        return arr;
    }

    /**
     * Returns an iterator for the array.
     *
     * @return An iterator over the elements of the array.
     */
    public Iterator<RuntimeScalar> iterator() {

        if (this.type == AUTOVIVIFY_ARRAY) {
            AutovivificationArray.vivify(this);
        }

        return new RuntimeArrayIterator();
    }

    /**
     * Returns a string representation of the array reference.
     *
     * @return A string representing the array reference.
     */
    public String toStringRef() {
        String ref = "ARRAY(0x" + Integer.toHexString(this.hashCode()) + ")";
        return (blessId == 0
                ? ref
                : NameNormalizer.getBlessStr(blessId) + "=" + ref);
    }

    /**
     * Returns the integer representation of the array reference.
     *
     * @return The hash code of the array.
     */
    public int getIntRef() {
        return this.hashCode();
    }

    /**
     * Returns the double representation of the array reference.
     *
     * @return The hash code of the array.
     */
    public double getDoubleRef() {
        return this.hashCode();
    }

    /**
     * Evaluates the boolean representation of the array reference.
     *
     * @return Always true for array references.
     */
    public boolean getBooleanRef() {
        return true;
    }

    /**
     * Undefines the array by clearing all elements.
     *
     * @return The updated RuntimeArray after undefining.
     */
    public RuntimeArray undefine() {
        MortalList.deferDestroyForContainerClear(this.elements);
        this.elements.clear();
        MortalList.flush();
        return this;
    }

    /**
     * Removes the last character from each element in the list.
     *
     * @return A scalar representing the result of the chop operation.
     */
    public RuntimeScalar chop() {
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
        RuntimeScalar result = new RuntimeScalar("");
        Iterator<RuntimeScalar> iterator = this.iterator();
        while (iterator.hasNext()) {
            result = iterator.next().chomp();
        }
        return result;
    }

    /**
     * Converts the array to a string, concatenating all elements without separators.
     *
     * @return A string representation of the array.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (this.type == TIED_ARRAY) {
            int size = this.size();
            for (int i = 0; i < size; i++) {
                sb.append(this.get(i));
            }
        } else {
            for (RuntimeBase element : elements) {
                if (element != null) {
                    sb.append(element);
                }
            }
        }
        return sb.toString();
    }

    /**
     * Saves the current state of the RuntimeArray instance.
     *
     * <p>This method creates a snapshot of the current elements and blessId of the array,
     * and pushes it onto a static stack for later restoration. After saving, it clears
     * the current elements and resets the blessId.
     */
    @Override
    public void dynamicSaveState() {
        // Create a new RuntimeArray to save the current state
        RuntimeArray currentState = new RuntimeArray();
        // Copy the current elements to the new state
        // For tied arrays, preserve the TieArray object
        if (this.type == TIED_ARRAY) {
            currentState.elements = this.elements; // Keep the TieArray reference
            currentState.type = TIED_ARRAY;
        } else {
            currentState.elements = new ArrayList<>(this.elements);
        }
        // Copy the current blessId to the new state
        currentState.blessId = this.blessId;
        // Push the current state onto the stack
        dynamicStateStack.push(currentState);
        // Clear the array elements (for tied arrays, this calls CLEAR)
        if (this.type == TIED_ARRAY) {
            TieArray.tiedClear(this);
        } else {
            this.elements.clear();
        }
        // Reset the blessId
        this.blessId = 0;
    }

    /**
     * Restores the most recently saved state of the RuntimeArray instance.
     *
     * <p>This method pops the most recent state from the static stack and restores
     * the elements and blessId to the current array. If no state is saved, it does nothing.
     */
    @Override
    public void dynamicRestoreState() {
        if (!dynamicStateStack.isEmpty()) {
            // Pop the most recent saved state from the stack
            RuntimeArray previousState = dynamicStateStack.pop();
            // Before discarding the current (local scope's) elements, defer
            // refCount decrements for any tracked blessed references they own.
            // Without this, `local @_ = ($obj)` where $obj is tracked would
            // leak refCounts because the local elements are replaced without
            // ever going through scopeExitCleanup.
            MortalList.deferDestroyForContainerClear(this.elements);
            // Real Perl semantics: `local @arr` creates a fresh temporary AV
            // for the scope; `bless \@arr, 'X'` blesses that temporary; at
            // local-restore, the temporary is freed and DESTROY fires for
            // class X. PerlOnJava reuses the same AV across local/restore,
            // so we need to fire DESTROY explicitly when the current state
            // was blessed but the previous state was not (or vice-versa
            // changed bless class). Test: postfixderef.t #38 "no stooges
            // outlast their scope".
            if (this.blessId != 0 && this.blessId != previousState.blessId) {
                int savedBlessId = this.blessId;
                int savedRefCount = this.refCount;
                int savedType = this.type;
                boolean savedDestroyFired = this.destroyFired;
                // callDestroy contract: caller sets refCount = MIN_VALUE.
                this.refCount = Integer.MIN_VALUE;
                DestroyDispatch.callDestroy(this);
                // Restore for safe state replacement below. Reset
                // destroyFired so subsequent local/restore cycles can
                // also fire DESTROY.
                this.destroyFired = savedDestroyFired;
                this.refCount = savedRefCount;
                this.type = savedType;
                this.blessId = savedBlessId;
            }
            // Restore the elements from the saved state
            this.elements = previousState.elements;
            // Restore the type from the saved state (important for tied arrays)
            this.type = previousState.type;
            // Restore the blessId from the saved state
            this.blessId = previousState.blessId;
        }
    }

    /**
     * Inner class implementing the Iterator interface for RuntimeArray.
     */
    private class RuntimeArrayIterator implements Iterator<RuntimeScalar> {
        private final int size = elements.size();
        private int currentIndex = 0;

        @Override
        public boolean hasNext() {
            return currentIndex < size;
        }

        @Override
        public RuntimeScalar next() {
            if (!hasNext()) {
                throw new IllegalStateException("No such element in iterator.next()");
            }
            RuntimeScalar element = elements.get(currentIndex);
            currentIndex++;

            // Return a proxy entry if the element is null
            if (element == null) {
                return new RuntimeArrayProxyEntry(RuntimeArray.this, currentIndex - 1);
            }

            return element;
        }

        @Override
        public void remove() {
            if (currentIndex <= 0) {
                throw new IllegalStateException("next() has not been called yet");
            }
            elements.remove(--currentIndex);
        }
    }
}
