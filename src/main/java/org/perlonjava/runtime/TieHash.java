package org.perlonjava.runtime;

import org.perlonjava.operators.Operator;

import java.util.HashMap;

/**
 * TieHash provides support for Perl's tie mechanism for hash variables.
 *
 * <p>In Perl, the tie mechanism allows hash variables to have their operations
 * intercepted and handled by custom classes. When a hash is tied, all operations
 * on that hash (fetching values, storing values, deleting keys, etc.) are delegated
 * to methods in the tie handler object.</p>
 *
 * <p>This class provides static methods that are called when operations are
 * performed on tied hashes.</p>
 *
 * @see RuntimeHash
 */
public class TieHash extends HashMap<String, RuntimeScalar> {

    /**
     * The tied object (handler) that implements the tie interface methods.
     * This is the blessed object returned by TIEHASH.
     */
    private final RuntimeScalar self;

    /**
     * The package name that this hash is tied to.
     * Used for method dispatch and error reporting.
     */
    private final String tiedPackage;

    /**
     * The original value of the hash before it was tied.
     * This value might be needed for untie operations or debugging.
     */
    private final RuntimeHash previousValue;

    /**
     * Creates a new TieHash instance.
     *
     * @param tiedPackage   the package name this hash is tied to
     * @param previousValue the value of the hash before it was tied (may be null/empty)
     * @param self          the blessed object returned by TIEHASH that handles tied operations
     */
    public TieHash(String tiedPackage, RuntimeHash previousValue, RuntimeScalar self) {
        this.tiedPackage = tiedPackage;
        this.previousValue = previousValue;
        this.self = self;
    }

    /**
     * Helper method to call methods on the tied object.
     *
     * @param tieHash the TieHash instance
     * @param method  the method name to call
     * @param args    the arguments to pass to the method
     * @return the result of the method call
     */
    private static RuntimeScalar tieCall(TieHash tieHash, String method, RuntimeBase... args) {
        RuntimeScalar self = tieHash.getSelf();
        String className = tieHash.getTiedPackage();

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
     * Fetches a value from a tied hash by key.
     *
     * <p>This method is called whenever a value is accessed from a tied hash.
     * It delegates to the FETCH method of the tie handler object associated
     * with the hash.</p>
     *
     * <p>In Perl, this corresponds to operations like:
     * <pre>{@code
     * my $value = $tied_hash{$key};  # Calls FETCH with $key
     * print $tied_hash{$key};        # Calls FETCH with $key
     * if ($tied_hash{$key}) { ... }  # Calls FETCH with $key
     * }</pre>
     * </p>
     *
     * @param tieHash the tied hash whose value is being fetched
     * @param key     the key to fetch
     * @return the value returned by the tie handler's FETCH method
     */
    public static RuntimeScalar tiedFetch(TieHash tieHash, RuntimeScalar key) {
        return tieCall(tieHash, "FETCH", key);
    }

    /**
     * Stores a value into a tied hash.
     *
     * <p>This method is called whenever a value is assigned to a tied hash.
     * It delegates to the STORE method of the tie handler object associated
     * with the hash.</p>
     *
     * <p>In Perl, this corresponds to operations like:
     * <pre>{@code
     * $tied_hash{$key} = 42;           # Calls STORE with $key and 42
     * $tied_hash{$key} = "hello";      # Calls STORE with $key and "hello"
     * $tied_hash{$key}++;              # Calls FETCH, then STORE
     * }</pre>
     * </p>
     *
     * @param tieHash the tied hash being assigned to
     * @param key     the key to store at
     * @param value   the value to store in the tied hash
     * @return the value returned by the tie handler's STORE method
     */
    public static RuntimeScalar tiedStore(TieHash tieHash, RuntimeScalar key, RuntimeScalar value) {
        return tieCall(tieHash, "STORE", key, value);
    }

    /**
     * Deletes a key from a tied hash.
     *
     * <p>This method is called when a key is deleted from a tied hash.
     * It delegates to the DELETE method of the tie handler object.</p>
     *
     * <p>In Perl, this corresponds to:
     * <pre>{@code
     * delete $tied_hash{$key};  # Calls DELETE with $key
     * }</pre>
     * </p>
     *
     * @param tieHash the tied hash
     * @param key     the key to delete
     * @return the value returned by the tie handler's DELETE method
     */
    public static RuntimeScalar tiedDelete(TieHash tieHash, RuntimeScalar key) {
        return tieCall(tieHash, "DELETE", key);
    }

    /**
     * Checks if a key exists in a tied hash.
     *
     * <p>This method is called when checking for key existence in a tied hash.
     * It delegates to the EXISTS method of the tie handler object.</p>
     *
     * <p>In Perl, this corresponds to:
     * <pre>{@code
     * if (exists $tied_hash{$key}) { ... }  # Calls EXISTS with $key
     * }</pre>
     * </p>
     *
     * @param tieHash the tied hash
     * @param key     the key to check
     * @return the value returned by the tie handler's EXISTS method
     */
    public static RuntimeScalar tiedExists(TieHash tieHash, RuntimeScalar key) {
        return tieCall(tieHash, "EXISTS", key);
    }

    /**
     * Gets the first key for iterating over a tied hash.
     *
     * <p>This method is called when beginning iteration over a tied hash.
     * It delegates to the FIRSTKEY method of the tie handler object.</p>
     *
     * <p>In Perl, this corresponds to:
     * <pre>{@code
     * foreach my $key (keys %tied_hash) { ... }  # Calls FIRSTKEY, then NEXTKEY
     * while (my ($k, $v) = each %tied_hash) { ... }  # Calls FIRSTKEY, then NEXTKEY
     * }</pre>
     * </p>
     *
     * @param tieHash the tied hash
     * @return the first key returned by the tie handler's FIRSTKEY method
     */
    public static RuntimeScalar tiedFirstKey(TieHash tieHash) {
        return tieCall(tieHash, "FIRSTKEY");
    }

    /**
     * Gets the next key for iterating over a tied hash.
     *
     * <p>This method is called during iteration over a tied hash.
     * It delegates to the NEXTKEY method of the tie handler object.</p>
     *
     * <p>In Perl, this corresponds to continued iteration after FIRSTKEY.</p>
     *
     * @param tieHash    the tied hash
     * @param previousKey the previous key in the iteration
     * @return the next key returned by the tie handler's NEXTKEY method
     */
    public static RuntimeScalar tiedNextKey(TieHash tieHash, RuntimeScalar previousKey) {
        return tieCall(tieHash, "NEXTKEY", previousKey);
    }

    /**
     * Gets the scalar representation of a tied hash.
     *
     * <p>This method is called when a tied hash is used in scalar context.
     * It delegates to the SCALAR method of the tie handler object.</p>
     *
     * <p>In Perl, this corresponds to:
     * <pre>{@code
     * my $count = %tied_hash;  # Calls SCALAR
     * if (%tied_hash) { ... }  # Calls SCALAR
     * }</pre>
     * </p>
     *
     * @param tieHash the tied hash
     * @return the value returned by the tie handler's SCALAR method
     */
    public static RuntimeScalar tiedScalar(TieHash tieHash) {
        return tieCall(tieHash, "SCALAR");
    }

    /**
     * Clears all keys from a tied hash.
     *
     * <p>This method is called when clearing a tied hash.
     * It delegates to the CLEAR method of the tie handler object.</p>
     *
     * <p>In Perl, this corresponds to:
     * <pre>{@code
     * %tied_hash = ();  # Calls CLEAR
     * undef %tied_hash;  # Calls CLEAR
     * }</pre>
     * </p>
     *
     * @param tieHash the tied hash to clear
     * @return the value returned by the tie handler's CLEAR method
     */
    public static RuntimeScalar tiedClear(TieHash tieHash) {
        return tieCall(tieHash, "CLEAR");
    }

    /**
     * Destroys a tied hash variable.
     *
     * <p>This method is called when a tied hash goes out of scope or is being
     * garbage collected. It delegates to the DESTROY method of the tie handler
     * object associated with the hash, if such a method exists.</p>
     *
     * <p>In Perl, this corresponds to:
     * <pre>{@code
     * {
     *     my %tied_hash;
     *     tie %tied_hash, 'MyClass';
     *     # ... use %tied_hash ...
     * } # DESTROY called here when %tied_hash goes out of scope
     * }</pre>
     * </p>
     *
     * @param runtimeHash the tied hash being destroyed
     * @return the value returned by the tie handler's DESTROY method, or undef if no DESTROY method exists
     */
    public static RuntimeScalar tiedDestroy(RuntimeHash runtimeHash) {
        // Get the tied object using the tied() operator
        RuntimeScalar tiedObject = Operator.tied(runtimeHash.createReference());
        if (!tiedObject.getDefinedBoolean()) {
            return RuntimeScalarCache.scalarUndef;
        }

        // Get the class name from the tied object
        int blessId = tiedObject.blessedId();
        if (blessId == 0) {
            return RuntimeScalarCache.scalarUndef;
        }
        String perlClassName = NameNormalizer.getBlessStr(blessId);

        // Check if DESTROY method exists in the class hierarchy
        RuntimeScalar destroyMethod = InheritanceResolver.findMethodInHierarchy("DESTROY", perlClassName, null, 0);
        if (destroyMethod == null) {
            // DESTROY method doesn't exist, return undef
            return RuntimeScalarCache.scalarUndef;
        }

        // DESTROY method exists, call it
        RuntimeArray args = new RuntimeArray(tiedObject);
        return RuntimeCode.apply(destroyMethod, args, RuntimeContextType.SCALAR).getFirst();
    }

    public RuntimeHash getPreviousValue() {
        return previousValue;
    }

    public RuntimeScalar getSelf() {
        return self;
    }

    public String getTiedPackage() {
        return tiedPackage;
    }
}