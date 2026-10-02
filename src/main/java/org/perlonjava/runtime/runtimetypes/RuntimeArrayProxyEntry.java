package org.perlonjava.runtime.runtimetypes;

import java.util.List;
import java.util.Stack;

/**
 * RuntimeArrayProxyEntry acts as a proxy for accessing elements within a RuntimeArray.
 * It provides a mechanism to lazily initialize (vivify) elements in the array
 * when they are accessed.
 */
public class RuntimeArrayProxyEntry extends RuntimeBaseProxy {
    private static Stack<Integer> dynamicStateStackInt() {
        return PerlRuntime.current().executionState().arrayProxyIndexStates;
    }
    private static Stack<RuntimeScalar> dynamicStateStack() {
        return PerlRuntime.current().executionState().arrayProxyStates;
    }
    private static Stack<Integer> dynamicSavedSizeStack() {
        return PerlRuntime.current().executionState().arrayProxySavedSizes;
    }

    // Reference to the parent RuntimeArray
    private final RuntimeArray parent;
    // Index associated with this proxy in the parent array
    private final int key;
    // Preserve the source subscript for diagnostics after negative-index
    // normalization (for example, $a[-5] on a four-element array).
    private final int diagnosticKey;

    /**
     * Constructs a RuntimeArrayProxyEntry for a given index in the specified parent array.
     *
     * @param parent the parent RuntimeArray containing the elements
     * @param key    the index in the array for which this proxy is created
     */
    public RuntimeArrayProxyEntry(RuntimeArray parent, int key) {
        this(parent, key, key);
    }

    public RuntimeArrayProxyEntry(RuntimeArray parent, int key, int diagnosticKey) {
        super();
        this.parent = parent;
        this.key = key;
        this.diagnosticKey = diagnosticKey;
        // Note: this.type is RuntimeScalarType.UNDEF
    }

    /**
     * A sparse-slot proxy can itself occupy the array's hole.  Resolve its
     * current physical position so structural mutations performed while an
     * alias is live do not redirect a later write to the old numeric index.
     */
    private int currentKey() {
        int moved = parent.elements.indexOf(this);
        return moved >= 0 ? moved : key;
    }

    /** Whether this proxy still points at an absent source slot. */
    public boolean isUnmaterializedHole() {
        int currentKey = currentKey();
        return lvalue == null && currentKey >= 0 && currentKey < parent.elements.size()
                && parent.elements.get(currentKey) == null;
    }

    /** Current source index, for operations that must preserve a sparse slot. */
    public int getSourceIndex() {
        return currentKey();
    }

    /** Parent aggregate, used for diagnostics that retain an element's identity. */
    public RuntimeArray getParent() { return parent; }

    @Override
    public RuntimeScalar set(RuntimeScalar value) {
        if (parent.threadShared) SharedPerlStorage.validateStoredValue(value);
        vivify();
        if (parent.threadShared) SharedPerlStorage.publishBlessing(value);
        parent.markPackageRootedValue(lvalue);
        RuntimeScalar result = super.set(value);
        if (!parent.elementsAliased) {
            parent.elementsOwned = true;
        }
        return result;
    }

    /** Replace this array slot with the scalar referenced by a refaliasing RHS. */
    public RuntimeScalar aliasToReference(RuntimeScalar reference) {
        if (parent.threadShared) SharedPerlStorage.validateStoredValue(reference);
        RuntimeScalar referent = reference.refAliasScalarReference();
        int currentKey = currentKey();
        if (currentKey < 0) {
            throw new PerlCompilerException(
                    "Modification of non-creatable array value attempted, subscript " + diagnosticKey);
        }
        parent.notePackageRootMutation();
        while (currentKey >= parent.elements.size()) {
            parent.elements.add(null);
        }
        parent.elements.set(currentKey, referent);
        parent.markPackageRootedValue(referent);
        this.lvalue = referent;
        this.type = referent.type;
        this.value = referent.value;
        this.firstClassRegexScalar = referent.firstClassRegexScalar;
        this.firstClassRegexValue = referent.firstClassRegexValue;
        if (!parent.elementsAliased) {
            parent.elementsOwned = true;
        }
        return referent;
    }

    @Override
    public RuntimeScalar undefine() {
        vivify();
        parent.markPackageRootedValue(lvalue);
        parent.notePackageRootMutation();
        return super.undefine();
    }

    /**
     * Creates a reference to the underlying lvalue, vivifying it first.
     * In Perl, \$arr[$i] auto-vivifies the array element so that the reference
     * points to the actual array element, not a temporary.
     * Checks for existing elements first to avoid overwriting tied or special elements.
     */
    @Override
    public RuntimeScalar createReference() {
        if (lvalue == null) {
            // Check if the element already exists (e.g., a tied scalar)
            List<RuntimeScalar> elements = parent.elements;
            int currentKey = currentKey();
            if (currentKey >= 0 && currentKey < elements.size() && elements.get(currentKey) != null
                    && elements.get(currentKey) != this) {
                lvalue = elements.get(currentKey);
                parent.markPackageRootedValue(lvalue);
            } else {
                vivify();
            }
        }
        return lvalue.createReference();
    }

    /**
     * Vivifies (initializes) the element in the parent array if it does not exist.
     * If the element at the specified index is not present, it creates new
     * RuntimeScalar instances up to that index and assigns them in the parent array.
     */
    void vivify() {
        if (lvalue == null) {
            if (parent.type == RuntimeArray.READONLY_ARRAY) {
                throw new PerlCompilerException("Modification of a read-only value attempted");
            }
            int currentKey = currentKey();
            if (currentKey < 0) {
                throw new PerlCompilerException(
                        "Modification of non-creatable array value attempted, subscript " + diagnosticKey);
            }
            lvalue = new RuntimeScalar();

            if (parent.type == RuntimeArray.AUTOVIVIFY_ARRAY) {
                // If the array is auto-vivified, vivify the parent array
                AutovivificationArray.vivify(parent);
            }

            List<RuntimeScalar> elements = parent.elements;

            // Expand the array if needed
            parent.notePackageRootMutation();
            while (currentKey >= elements.size()) {
                elements.add(null); // Add null placeholders
            }

            // Set the element at the index
            elements.set(currentKey, lvalue);
            parent.markPackageRootedValue(lvalue);
        }
    }

    @Override
    RuntimeScalar posStorage() {
        int currentKey = currentKey();
        if (lvalue == null && currentKey >= 0 && currentKey < parent.elements.size()
                && parent.elements.get(currentKey) != this) {
            lvalue = parent.elements.get(currentKey);
        }
        return lvalue == null ? this : lvalue.posStorage();
    }

    @Override
    public RuntimeArray setArrayOfAlias(RuntimeArray array) {
        int currentKey = currentKey();
        if (lvalue == null && currentKey >= 0 && currentKey < parent.elements.size()
                && parent.elements.get(currentKey) != this) {
            lvalue = parent.elements.get(currentKey);
        }
        return lvalue == null ? super.setArrayOfAlias(array) : lvalue.setArrayOfAlias(array);
    }

    @Override
    public String toString() {
        int currentKey = currentKey();
        if (lvalue == null && currentKey >= 0 && currentKey < parent.elements.size()
                && parent.elements.get(currentKey) != this) {
            lvalue = parent.elements.get(currentKey);
        }
        return lvalue == null ? super.toString() : lvalue.toString();
    }

    /**
     * Saves the current state of the RuntimeScalar instance.
     *
     * <p>This method creates a snapshot of the current type and value of the scalar,
     * and pushes it onto a static stack for later restoration.
     */
    @Override
    public void dynamicSaveState() {
        dynamicStateStackInt().push(parent.elements.size());
        dynamicSavedSizeStack().push(parent.elements.size());
        // Create a new RuntimeScalar to save the current state
        if (this.lvalue == null) {
            dynamicStateStack().push(null);
            vivify();
        } else {
            RuntimeScalar currentState = new RuntimeScalar();
            // Copy the current type and value to the new state
            currentState.type = this.lvalue.type;
            currentState.value = this.lvalue.value;
            currentState.blessId = this.lvalue.blessId;
            dynamicStateStack().push(currentState);
            // Clear the current type and value
            this.undefine();
        }
    }

    /**
     * Restores the most recently saved state of the RuntimeScalar instance.
     *
     * <p>This method pops the most recent state from the static stack and restores
     * the type and value to the current scalar. If no state is saved, it does nothing.
     */
    @Override
    public void dynamicRestoreState() {
        Stack<RuntimeScalar> dynamicStateStack = dynamicStateStack();
        if (!dynamicStateStack.isEmpty()) {
            // Pop the most recent saved state from the stack
            RuntimeScalar previousState = dynamicStateStack.pop();
            if (previousState == null) {
                parent.notePackageRootMutation();
                // Element didn't exist before.
                // Decrement refCount of the current value being displaced.
                if (this.lvalue != null
                        && (this.lvalue.type & RuntimeScalarType.REFERENCE_BIT) != 0
                        && this.lvalue.value instanceof RuntimeBase displacedBase
                        && displacedBase.refCount > 0 && --displacedBase.refCount == 0) {
                    displacedBase.refCount = Integer.MIN_VALUE;
                    DestroyDispatch.callDestroy(displacedBase);
                }
                this.lvalue = null;
                this.type = RuntimeScalarType.UNDEF;
                this.value = null;
            } else {
                // Restore the type, value from the saved state
                // this.set() goes through setLarge() which handles refCount
                this.set(previousState);
                this.lvalue.blessId = previousState.blessId;
                this.blessId = previousState.blessId;
            }
            int previousSize = dynamicStateStackInt().pop();
            dynamicSavedSizeStack().pop();
            if (previousState == null && key >= previousSize) {
                // Remove the localized hole itself while retaining values
                // assigned to intervening indices during the scope.
                // Structural array operations can move the proxy, so remove
                // its current physical slot rather than the original index.
                int currentKey = currentKey();
                if (currentKey >= 0 && currentKey < parent.elements.size()) {
                    parent.elements.set(currentKey, null);
                }
                while (parent.elements.size() > previousSize
                        && isEmptyPlaceholder(parent.elements.getLast())) {
                    parent.elements.removeLast();
                }
                return;
            }
            if (parent.elements.size() > previousSize) {
                parent.notePackageRootMutation();
            }
            while (parent.elements.size() > previousSize) {
                parent.elements.removeLast();
            }
        }
    }

    private static boolean isEmptyPlaceholder(RuntimeScalar value) {
        return RuntimeArray.isEmptySlot(value) || (value.type & RuntimeScalarType.UNDEF) != 0;
    }
}
