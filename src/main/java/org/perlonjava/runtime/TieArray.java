package org.perlonjava.runtime;

import org.perlonjava.mro.InheritanceResolver;

import java.util.ArrayList;

import static org.perlonjava.runtime.RuntimeScalarCache.getScalarInt;

/**
 * TieArray provides support for Perl's tie mechanism for array variables.
 *
 * <p>When an array is tied, all operations are delegated to methods in the tie handler object.
 * This class extends ArrayList so it can be stored in RuntimeArray's elements field.
 * Example usage:
 * <pre>{@code
 * tie @array, 'MyClass';          # Creates handler via TIEARRAY
 * $array[0] = 42;                 # Calls STORE
 * my $val = $array[0];            # Calls FETCH
 * push @array, 1, 2, 3;           # Calls PUSH
 * my $elem = pop @array;          # Calls POP
 * shift @array;                   # Calls SHIFT
 * unshift @array, 1, 2;           # Calls UNSHIFT
 * splice @array, 0, 1, 'x';       # Calls SPLICE
 * delete $array[0];               # Calls DELETE
 * exists $array[0];               # Calls EXISTS
 * my $size = @array;              # Calls FETCHSIZE
 * $#array = 10;                   # Calls STORESIZE
 * @array = ();                    # Calls CLEAR
 * untie @array;                   # Calls UNTIE (if exists)
 * # DESTROY called when handler goes out of scope
 * }</pre>
 * </p>
 *
 * @see RuntimeArray
 */
public class TieArray extends ArrayList<RuntimeScalar> {

    /**
     * The tied object (handler) that implements the tie interface methods.
     */
    private final RuntimeScalar self;

    /**
     * The package name that this array is tied to.
     */
    private final String tiedPackage;

    /**
     * The original value of the array before it was tied.
     */
    private final RuntimeArray previousValue;

    private final RuntimeArray parent;

    /**
     * Creates a new TieArray instance.
     *
     * @param tiedPackage   the package name this array is tied to
     * @param previousValue the value of the array before it was tied
     * @param self          the blessed object returned by TIEARRAY
     */
    public TieArray(String tiedPackage, RuntimeArray previousValue, RuntimeScalar self, RuntimeArray parent) {
        this.tiedPackage = tiedPackage;
        this.previousValue = previousValue;
        this.self = self;
        this.parent = parent;
    }

    /**
     * Helper method to call methods on the tied object.
     */
    private static RuntimeList tieCall(RuntimeArray array, String method, RuntimeBase... args) {
        TieArray tieArray = (TieArray) array.elements;
        RuntimeScalar self = tieArray.getSelf();
        String className = tieArray.getTiedPackage();

        // Call the Perl method
        return RuntimeCode.call(
                self,
                new RuntimeScalar(method),
                className,
                new RuntimeArray(args),
                RuntimeContextType.SCALAR
        );
    }

    /**
     * Calls a tie method if it exists in the tied object's class hierarchy.
     * Used by tiedDestroy() and tiedUntie() for optional methods.
     */
    private static RuntimeScalar tieCallIfExists(RuntimeArray array, String methodName) {
        TieArray tieArray = (TieArray) array.elements;
        RuntimeScalar self = tieArray.getSelf();
        String className = tieArray.getTiedPackage();

        // Check if method exists in the class hierarchy
        RuntimeScalar method = InheritanceResolver.findMethodInHierarchy(methodName, className, null, 0);
        if (method == null) {
            // Method doesn't exist, return undef
            return RuntimeScalarCache.scalarUndef;
        }

        // Method exists, call it
        return RuntimeCode.apply(method, new RuntimeArray(self), RuntimeContextType.SCALAR).getFirst();
    }

    /**
     * Fetches an element from a tied array by index (delegates to FETCH).
     */
    public static RuntimeScalar tiedFetch(RuntimeArray array, RuntimeScalar index) {
        return tieCall(array, "FETCH", index).getFirst();
    }

    /**
     * Stores an element into a tied array (delegates to STORE).
     */
    public static RuntimeScalar tiedStore(RuntimeArray array, RuntimeScalar index, RuntimeScalar value) {
        return tieCall(array, "STORE", index, value).getFirst();
    }

    /**
     * Gets the size of a tied array (delegates to FETCHSIZE).
     */
    public static RuntimeScalar tiedFetchSize(RuntimeArray array) {
        return tieCall(array, "FETCHSIZE").getFirst();
    }

    /**
     * Sets the size of a tied array (delegates to STORESIZE).
     */
    public static RuntimeScalar tiedStoreSize(RuntimeArray array, RuntimeScalar size) {
        return tieCall(array, "STORESIZE", size).getFirst();
    }

    /**
     * Extends a tied array to a specified size (delegates to EXTEND).
     */
    public static RuntimeScalar tiedExtend(RuntimeArray array, RuntimeScalar size) {
        return tieCall(array, "EXTEND", size).getFirst();
    }

    /**
     * Checks if an index exists in a tied array (delegates to EXISTS).
     */
    public static RuntimeScalar tiedExists(RuntimeArray array, RuntimeScalar index) {
        return tieCall(array, "EXISTS", index).getFirst();
    }

    /**
     * Deletes an element from a tied array (delegates to DELETE).
     */
    public static RuntimeScalar tiedDelete(RuntimeArray array, RuntimeScalar index) {
        return tieCall(array, "DELETE", index).getFirst();
    }

    /**
     * Clears all elements from a tied array (delegates to CLEAR).
     */
    public static RuntimeScalar tiedClear(RuntimeArray array) {
        return tieCall(array, "CLEAR").getFirst();
    }

    /**
     * Pushes elements onto the end of a tied array (delegates to PUSH).
     */
    public static RuntimeScalar tiedPush(RuntimeArray array, RuntimeBase elements) {
        return tieCall(array, "PUSH", elements).getFirst();
    }

    /**
     * Pops an element from the end of a tied array (delegates to POP).
     */
    public static RuntimeScalar tiedPop(RuntimeArray array) {
        return tieCall(array, "POP").getFirst();
    }

    /**
     * Shifts an element from the beginning of a tied array (delegates to SHIFT).
     */
    public static RuntimeScalar tiedShift(RuntimeArray array) {
        return tieCall(array, "SHIFT").getFirst();
    }

    /**
     * Unshifts elements onto the beginning of a tied array (delegates to UNSHIFT).
     */
    public static RuntimeScalar tiedUnshift(RuntimeArray array, RuntimeBase elements) {
        return tieCall(array, "UNSHIFT", elements).getFirst();
    }

    /**
     * Performs a splice operation on a tied array (delegates to SPLICE).
     */
    public static RuntimeList tiedSplice(RuntimeArray array, RuntimeList list) {
        return tieCall(array, "SPLICE", list).getList();
    }

    /**
     * Called when a tied array goes out of scope (delegates to DESTROY if exists).
     */
    public static RuntimeScalar tiedDestroy(RuntimeArray array) {
        return tieCallIfExists(array, "DESTROY").getFirst();
    }

    /**
     * Unties an array variable (delegates to UNTIE if exists).
     */
    public static RuntimeScalar tiedUntie(RuntimeArray array) {
        return tieCallIfExists(array, "UNTIE").getFirst();
    }

    public RuntimeArray getPreviousValue() {
        return previousValue;
    }

    public RuntimeScalar getSelf() {
        return self;
    }

    public String getTiedPackage() {
        return tiedPackage;
    }

    public int size() { return tiedFetchSize(parent).getInt(); }

    public RuntimeScalar get(int i) {
        return parent.get(i);
    }
}