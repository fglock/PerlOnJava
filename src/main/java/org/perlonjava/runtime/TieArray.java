package org.perlonjava.runtime;

import org.perlonjava.operators.TieOperators;

import java.util.ArrayList;

/**
 * TieArray provides support for Perl's tie mechanism for array variables.
 *
 * <p>In Perl, the tie mechanism allows array variables to have their operations
 * intercepted and handled by custom classes. When an array is tied, all operations
 * on that array (fetching elements, storing elements, push, pop, etc.) are delegated
 * to methods in the tie handler object.</p>
 *
 * <p>This class provides static methods that are called when operations are
 * performed on tied arrays.</p>
 *
 *  <p>This class extends ArrayList so it can be stored in RuntimeArray's elements field,
 *  similar to how AutovivificationHash extends HashMap for RuntimeHash.</p>
 *
 * @see RuntimeArray
 */
public class TieArray extends ArrayList<RuntimeScalar> {

    /**
     * The tied object (handler) that implements the tie interface methods.
     * This is the blessed object returned by TIEARRAY.
     */
    private final RuntimeScalar self;

    /**
     * The package name that this array is tied to.
     * Used for method dispatch and error reporting.
     */
    private final String tiedPackage;

    /**
     * The original value of the array before it was tied.
     * This value might be needed for untie operations or debugging.
     */
    private final RuntimeArray previousValue;

    /**
     * Creates a new TieArray instance.
     *
     * @param tiedPackage   the package name this array is tied to
     * @param previousValue the value of the array before it was tied (may be null/empty)
     * @param self          the blessed object returned by TIEARRAY that handles tied operations
     */
    public TieArray(String tiedPackage, RuntimeArray previousValue, RuntimeScalar self) {
        this.tiedPackage = tiedPackage;
        this.previousValue = previousValue;
        this.self = self;
    }

    /**
     * Helper method to call methods on the tied object.
     *
     * @param array    the RuntimeArray that is tied
     * @param method   the method name to call
     * @param args     the arguments to pass to the method
     * @return the result of the method call
     */
    private static RuntimeScalar tieCall(RuntimeArray array, String method, RuntimeBase... args) {
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
        ).getFirst();
    }

    /**
     * Calls a tie method if it exists in the tied object's class hierarchy.
     *
     * <p>This is a helper method used by tiedDestroy() and tiedUntie() to check
     * if a specific method exists in the tied object's class and call it if present.</p>
     *
     * @param array         the RuntimeArray that is tied
     * @param methodName    the name of the method to call (e.g., "DESTROY", "UNTIE")
     * @return the value returned by the method, or undef if the method doesn't exist
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
     * Fetches an element from a tied array by index.
     *
     * <p>This method is called whenever an element is accessed from a tied array.
     * It delegates to the FETCH method of the tie handler object associated
     * with the array.</p>
     *
     * <p>In Perl, this corresponds to operations like:
     * <pre>{@code
     * my $value = $tied_array[$index];  # Calls FETCH with $index
     * print $tied_array[$index];        # Calls FETCH with $index
     * if ($tied_array[$index]) { ... }  # Calls FETCH with $index
     * }</pre>
     * </p>
     *
     * @param array    the tied array whose element is being fetched
     * @param index    the index to fetch
     * @return the value returned by the tie handler's FETCH method
     */
    public static RuntimeScalar tiedFetch(RuntimeArray array, RuntimeScalar index) {
        return tieCall(array, "FETCH", index);
    }

    /**
     * Stores an element into a tied array.
     *
     * <p>This method is called whenever an element is assigned to a tied array.
     * It delegates to the STORE method of the tie handler object associated
     * with the array.</p>
     *
     * <p>In Perl, this corresponds to operations like:
     * <pre>{@code
     * $tied_array[$index] = 42;           # Calls STORE with $index and 42
     * $tied_array[$index] = "hello";      # Calls STORE with $index and "hello"
     * $tied_array[$index]++;              # Calls FETCH, then STORE
     * }</pre>
     * </p>
     *
     * @param array    the tied array being assigned to
     * @param index    the index to store at
     * @param value    the value to store in the tied array
     * @return the value returned by the tie handler's STORE method
     */
    public static RuntimeScalar tiedStore(RuntimeArray array, RuntimeScalar index, RuntimeScalar value) {
        return tieCall(array, "STORE", index, value);
    }

    /**
     * Gets the size of a tied array.
     *
     * <p>This method is called when the size of a tied array is queried.
     * It delegates to the FETCHSIZE method of the tie handler object.</p>
     *
     * <p>In Perl, this corresponds to operations like:
     * <pre>{@code
     * my $size = @tied_array;     # Calls FETCHSIZE
     * my $last = $#tied_array;    # Calls FETCHSIZE (returns size - 1)
     * }</pre>
     * </p>
     *
     * @param array the tied array
     * @return the size returned by the tie handler's FETCHSIZE method
     */
    public static RuntimeScalar tiedFetchSize(RuntimeArray array) {
        return tieCall(array, "FETCHSIZE");
    }

    /**
     * Sets the size of a tied array.
     *
     * <p>This method is called when the size of a tied array is set.
     * It delegates to the STORESIZE method of the tie handler object.</p>
     *
     * <p>In Perl, this corresponds to operations like:
     * <pre>{@code
     * $#tied_array = 10;    # Calls STORESIZE with 11
     * @tied_array = ();     # Calls STORESIZE with 0
     * }</pre>
     * </p>
     *
     * @param array    the tied array
     * @param size     the new size for the array
     * @return the value returned by the tie handler's STORESIZE method
     */
    public static RuntimeScalar tiedStoreSize(RuntimeArray array, RuntimeScalar size) {
        return tieCall(array, "STORESIZE", size);
    }

    /**
     * Extends a tied array to a specified size.
     *
     * <p>This method is called when a tied array needs to be extended.
     * It delegates to the EXTEND method of the tie handler object.</p>
     *
     * <p>In Perl, this is typically called internally when an array
     * needs to grow to accommodate new elements.</p>
     *
     * @param array    the tied array
     * @param size     the size to extend to
     * @return the value returned by the tie handler's EXTEND method
     */
    public static RuntimeScalar tiedExtend(RuntimeArray array, RuntimeScalar size) {
        return tieCall(array, "EXTEND", size);
    }

    /**
     * Checks if an index exists in a tied array.
     *
     * <p>This method is called when checking for element existence in a tied array.
     * It delegates to the EXISTS method of the tie handler object.</p>
     *
     * <p>In Perl, this corresponds to:
     * <pre>{@code
     * if (exists $tied_array[$index]) { ... }  # Calls EXISTS with $index
     * }</pre>
     * </p>
     *
     * @param array    the tied array
     * @param index    the index to check
     * @return the value returned by the tie handler's EXISTS method
     */
    public static RuntimeScalar tiedExists(RuntimeArray array, RuntimeScalar index) {
        return tieCall(array, "EXISTS", index);
    }

    /**
     * Deletes an element from a tied array.
     *
     * <p>This method is called when an element is deleted from a tied array.
     * It delegates to the DELETE method of the tie handler object.</p>
     *
     * <p>In Perl, this corresponds to:
     * <pre>{@code
     * delete $tied_array[$index];  # Calls DELETE with $index
     * }</pre>
     * </p>
     *
     * @param array    the tied array
     * @param index    the index to delete
     * @return the value returned by the tie handler's DELETE method
     */
    public static RuntimeScalar tiedDelete(RuntimeArray array, RuntimeScalar index) {
        return tieCall(array, "DELETE", index);
    }

    /**
     * Clears all elements from a tied array.
     *
     * <p>This method is called when clearing a tied array.
     * It delegates to the CLEAR method of the tie handler object.</p>
     *
     * <p>In Perl, this corresponds to:
     * <pre>{@code
     * @tied_array = ();   # Calls CLEAR
     * undef @tied_array;  # Calls CLEAR
     * }</pre>
     * </p>
     *
     * @param array the tied array to clear
     * @return the value returned by the tie handler's CLEAR method
     */
    public static RuntimeScalar tiedClear(RuntimeArray array) {
        return tieCall(array, "CLEAR");
    }

    /**
     * Pushes elements onto the end of a tied array.
     *
     * <p>This method is called when pushing elements onto a tied array.
     * It delegates to the PUSH method of the tie handler object.</p>
     *
     * <p>In Perl, this corresponds to:
     * <pre>{@code
     * push @tied_array, $elem1, $elem2;  # Calls PUSH with elements
     * }</pre>
     * </p>
     *
     * @param array    the tied array
     * @param elements the elements to push
     * @return the value returned by the tie handler's PUSH method
     */
    public static RuntimeScalar tiedPush(RuntimeArray array, RuntimeArray elements) {
        TieArray tieArray = (TieArray) array.elements;
        RuntimeBase[] args = new RuntimeBase[elements.size() + 1];
        args[0] = tieArray.getSelf();
        for (int i = 0; i < elements.size(); i++) {
            args[i + 1] = elements.get(i);
        }

        String className = tieArray.getTiedPackage();
        return RuntimeCode.call(
                tieArray.getSelf(),
                new RuntimeScalar("PUSH"),
                className,
                new RuntimeArray(args),
                RuntimeContextType.SCALAR
        ).getFirst();
    }

    /**
     * Pops an element from the end of a tied array.
     *
     * <p>This method is called when popping from a tied array.
     * It delegates to the POP method of the tie handler object.</p>
     *
     * <p>In Perl, this corresponds to:
     * <pre>{@code
     * my $elem = pop @tied_array;  # Calls POP
     * }</pre>
     * </p>
     *
     * @param array the tied array
     * @return the value returned by the tie handler's POP method
     */
    public static RuntimeScalar tiedPop(RuntimeArray array) {
        return tieCall(array, "POP");
    }

    /**
     * Shifts an element from the beginning of a tied array.
     *
     * <p>This method is called when shifting from a tied array.
     * It delegates to the SHIFT method of the tie handler object.</p>
     *
     * <p>In Perl, this corresponds to:
     * <pre>{@code
     * my $elem = shift @tied_array;  # Calls SHIFT
     * }</pre>
     * </p>
     *
     * @param array the tied array
     * @return the value returned by the tie handler's SHIFT method
     */
    public static RuntimeScalar tiedShift(RuntimeArray array) {
        return tieCall(array, "SHIFT");
    }

    /**
     * Unshifts elements onto the beginning of a tied array.
     *
     * <p>This method is called when unshifting elements onto a tied array.
     * It delegates to the UNSHIFT method of the tie handler object.</p>
     *
     * <p>In Perl, this corresponds to:
     * <pre>{@code
     * unshift @tied_array, $elem1, $elem2;  # Calls UNSHIFT with elements
     * }</pre>
     * </p>
     *
     * @param array    the tied array
     * @param elements the elements to unshift
     * @return the value returned by the tie handler's UNSHIFT method
     */
    public static RuntimeScalar tiedUnshift(RuntimeArray array, RuntimeArray elements) {
        TieArray tieArray = (TieArray) array.elements;
        RuntimeBase[] args = new RuntimeBase[elements.size() + 1];
        args[0] = tieArray.getSelf();
        for (int i = 0; i < elements.size(); i++) {
            args[i + 1] = elements.get(i);
        }

        String className = tieArray.getTiedPackage();
        return RuntimeCode.call(
                tieArray.getSelf(),
                new RuntimeScalar("UNSHIFT"),
                className,
                new RuntimeArray(args),
                RuntimeContextType.SCALAR
        ).getFirst();
    }

    /**
     * Performs a splice operation on a tied array.
     *
     * <p>This method is called when splicing a tied array.
     * It delegates to the SPLICE method of the tie handler object.</p>
     *
     * <p>In Perl, this corresponds to:
     * <pre>{@code
     * splice @tied_array, $offset, $length, @replacement;  # Calls SPLICE
     * }</pre>
     * </p>
     *
     * @param array       the tied array
     * @param offset      the offset at which to start
     * @param length      the number of elements to remove
     * @param replacement the elements to insert
     * @return the value returned by the tie handler's SPLICE method
     */
    public static RuntimeScalar tiedSplice(RuntimeArray array, RuntimeScalar offset,
                                           RuntimeScalar length, RuntimeArray replacement) {
        TieArray tieArray = (TieArray) array.elements;
        RuntimeBase[] args = new RuntimeBase[replacement.size() + 3];
        args[0] = tieArray.getSelf();
        args[1] = offset;
        args[2] = length;
        for (int i = 0; i < replacement.size(); i++) {
            args[i + 3] = replacement.get(i);
        }

        String className = tieArray.getTiedPackage();
        return RuntimeCode.call(
                tieArray.getSelf(),
                new RuntimeScalar("SPLICE"),
                className,
                new RuntimeArray(args),
                RuntimeContextType.SCALAR
        ).getFirst();
    }

    /**
     * Destroys a tied array variable.
     *
     * <p>This method is called when a tied array goes out of scope or is being
     * garbage collected. It delegates to the DESTROY method of the tie handler
     * object associated with the array, if such a method exists.</p>
     *
     * <p>In Perl, this corresponds to:
     * <pre>{@code
     * {
     *     my @tied_array;
     *     tie @tied_array, 'MyClass';
     *     # ... use @tied_array ...
     * } # DESTROY called here when @tied_array goes out of scope
     * }</pre>
     * </p>
     *
     * @param array the tied array being destroyed
     * @return the value returned by the tie handler's DESTROY method, or undef if no DESTROY method exists
     */
    public static RuntimeScalar tiedDestroy(RuntimeArray array) {
        return tieCallIfExists(array, "DESTROY");
    }

    /**
     * Unties an array variable.
     *
     * <p>This method is called when untying an array. It delegates to the UNTIE method
     * of the tie handler object associated with the array, if such a method exists.</p>
     *
     * <p>In Perl, this corresponds to:
     * <pre>{@code
     * tie @array, 'MyClass';
     * # ... use @array ...
     * untie @array;  # Calls UNTIE if it exists
     * }</pre>
     * </p>
     *
     * @param array the tied array being untied
     * @return the value returned by the tie handler's UNTIE method, or undef if no UNTIE method exists
     */
    public static RuntimeScalar tiedUntie(RuntimeArray array) {
        return tieCallIfExists(array, "UNTIE");
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
}
