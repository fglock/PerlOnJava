package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.runtime.operators.WarnDie;
import java.util.Stack;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents the value of $#{array}.
 */
public class RuntimeArraySizeLvalue extends RuntimeBaseProxy {
    private record SavedSize(int lastIndex, List<RuntimeScalar> tail) {}
    private final Stack<SavedSize> savedSizes = new Stack<>();
    private final RuntimeArray parentArray;
    private Object parentGeneration;

    private boolean isOrphaned() {
        return parentArray != null
                && (!parentArray.isArraySizeLvalueGenerationActive(parentGeneration)
                    || (parentArray.arrayLengthLvalueOrphaned
                        && parentArray.refCount <= 0));
    }

    private RuntimeScalar orphanedValue() {
        return RuntimeScalarCache.scalarUndef;
    }

    /**
     * The value a copy of {@code $#array} receives. The proxy's own fields
     * are a snapshot taken at construction; the array may have grown,
     * shrunk, or been freed since then.
     */
    RuntimeScalar currentValue() {
        if (isOrphaned()) return new RuntimeScalar();
        return new RuntimeScalar(parentArray.lastElementIndex());
    }

    private RuntimeScalar warnFreedArray() {
        WarnDie.warn(new RuntimeScalar("Attempt to set length of freed array"),
                RuntimeScalarCache.scalarEmptyString);
        return this;
    }

    /**
     * Constructs a new RuntimeArraySizeLvalue.
     *
     * @param parent The parent RuntimeArray.
     */
    public RuntimeArraySizeLvalue(RuntimeArray parent) {
        // $#array is an lvalue proxy, not an escaped \@array reference.
        // Keeping a tracked reference here would prevent lexical cleanup from
        // orphaning the proxy after `local @array` unwinds.
        this.parentArray = parent;
        this.lvalue = new RuntimeScalar();
        this.lvalue.type = RuntimeScalarType.ARRAYREFERENCE;
        this.lvalue.value = parent;
        this.type = RuntimeScalarType.INTEGER;
        this.value = parent.lastElementIndex();
        parentGeneration = parent.arraySizeLvalueGeneration();
    }

    RuntimeArray parentArray() {
        return parentArray;
    }

    /**
     * PerlOnJava reports a reference-counted stack (Internals::stack_refcounted),
     * under which the \$# magic variable keeps a temporary array alive: after
     * {@code my $r = \$#{[]}}, {@code $$r} is -1 and assigning to it does not
     * warn. A reference to this proxy therefore retains an anonymous parent
     * that has no other owner. A lexical array is still orphaned at scope exit.
     */
    @Override
    public RuntimeScalar createReference() {
        if (parentArray != null && parentArray.isRefCountTracked()
                && !parentArray.localBindingExists) {
            parentArray.retainedByArraySizeReference = true;
        }
        return super.createReference();
    }

    @Override
    RuntimeBase argumentAliasContainer() {
        return parentArray != null && !parentArray.isPerlDestroyed()
                && (parentArray.refCount > 0 || parentArray.hasPerlStrongOwnerIdentity())
                ? parentArray : null;
    }

    @Override
    boolean hasLivePerlValueOwner() {
        // The $#array proxy is a lvalue for the AV length, not an element
        // slot in that AV. A scalar reference to this proxy must not keep an
        // anonymous or lexical parent array alive.
        return false;
    }

    @Override
    boolean hasLivePerlValueOwner(boolean requirePersistentParent) {
        return false;
    }

    /**
     * Vivification method (currently empty as it doesn't require vivification).
     */
    @Override
    void vivify() {
    }

    /**
     * The array-size proxy is already a complete lvalue.  RuntimeBaseProxy's
     * generic implementation copies the backing reference's ARRAYREFERENCE
     * type and value into the proxy, destroying the integer $# value before a
     * compound assignment can use it.
     */
    @Override
    public void vivifyLvalue() {
        // Nothing to vivify: set() writes through to the referenced array.
    }

    /**
     * Sets the value of this scalar and updates the parent array accordingly.
     *
     * @param value The new size for the array.
     * @return This instance.
     */
    @Override
    public RuntimeScalar set(RuntimeScalar value) {
        if (isOrphaned()) return warnFreedArray();
        RuntimeArray parent = lvalue.arrayDeref();
        parent.setLastElementIndex(value);
        this.type = RuntimeScalarType.INTEGER;
        this.value = parent.lastElementIndex();
        return this;
    }

    @Override
    public void dynamicSaveState() {
        // Perl treats `local $#array` as an ordinary array-size assignment;
        // the assignment is not undone when the dynamic scope exits.
    }

    @Override
    public void dynamicRestoreState() {
        // Do not restore element wrappers whose counted owner slots were
        // retired when the localized size assignment removed them.
    }

    @Override
    public RuntimeScalar preAutoIncrement() {
        if (isOrphaned()) return warnFreedArray();
        RuntimeArray parent = lvalue.arrayDeref();
        int newIndex = parent.lastElementIndex() + 1;
        parent.setLastElementIndex(new RuntimeScalar(newIndex));
        this.type = RuntimeScalarType.INTEGER;
        this.value = newIndex;
        return this;
    }

    @Override
    public RuntimeScalar postAutoIncrement() {
        if (isOrphaned()) return warnFreedArray();
        RuntimeArray parent = lvalue.arrayDeref();
        int oldIndex = parent.lastElementIndex();
        parent.setLastElementIndex(new RuntimeScalar(oldIndex + 1));
        this.type = RuntimeScalarType.INTEGER;
        this.value = oldIndex + 1;
        return new RuntimeScalar(oldIndex);
    }

    @Override
    public RuntimeScalar preAutoDecrement() {
        if (isOrphaned()) return warnFreedArray();
        RuntimeArray parent = lvalue.arrayDeref();
        int newIndex = parent.lastElementIndex() - 1;
        parent.setLastElementIndex(new RuntimeScalar(newIndex));
        this.type = RuntimeScalarType.INTEGER;
        this.value = newIndex;
        return this;
    }

    @Override
    public RuntimeScalar postAutoDecrement() {
        if (isOrphaned()) return warnFreedArray();
        RuntimeArray parent = lvalue.arrayDeref();
        int oldIndex = parent.lastElementIndex();
        parent.setLastElementIndex(new RuntimeScalar(oldIndex - 1));
        this.type = RuntimeScalarType.INTEGER;
        this.value = oldIndex - 1;
        return new RuntimeScalar(oldIndex);
    }

    @Override
    public boolean getDefinedBoolean() {
        return !isOrphaned() && super.getDefinedBoolean();
    }

    @Override
    public boolean getBoolean() {
        return isOrphaned() ? false : super.getBoolean();
    }

    @Override
    public int getInt() {
        return isOrphaned() ? 0 : super.getInt();
    }

    @Override
    public long getLong() {
        return isOrphaned() ? 0 : super.getLong();
    }

    @Override
    public double getDouble() {
        return isOrphaned() ? 0 : super.getDouble();
    }

    @Override
    public String toString() {
        return isOrphaned() ? orphanedValue().toString() : super.toString();
    }
}
