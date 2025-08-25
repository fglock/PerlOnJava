package org.perlonjava.runtime;

import org.perlonjava.mro.InheritanceResolver;

import java.util.HashMap;

/**
 * TieHash provides support for Perl's tie mechanism for hash variables.
 *
 * <p>When a hash is tied, all operations are delegated to methods in the tie handler object.
 * Example usage:
 * <pre>{@code
 * tie %hash, 'MyClass';           # Creates handler via TIEHASH
 * $hash{key} = 42;                # Calls STORE
 * my $val = $hash{key};           # Calls FETCH
 * delete $hash{key};              # Calls DELETE
 * exists $hash{key};              # Calls EXISTS
 * %hash = ();                     # Calls CLEAR
 * my $size = %hash;               # Calls SCALAR
 * for (keys %hash) { ... }        # Calls FIRSTKEY, then NEXTKEY
 * untie %hash;                    # Calls UNTIE (if exists)
 * # DESTROY called when handler goes out of scope
 * }</pre>
 * </p>
 *
 * @see RuntimeHash
 */
public class TieHash extends HashMap<String, RuntimeScalar> {

    /** The tied object (handler) that implements the tie interface methods. */
    private final RuntimeScalar self;

    /** The package name that this hash is tied to. */
    private final String tiedPackage;

    /** The original value of the hash before it was tied. */
    private final RuntimeHash previousValue;

    /**
     * Creates a new TieHash instance.
     *
     * @param tiedPackage   the package name this hash is tied to
     * @param previousValue the value of the hash before it was tied
     * @param self          the blessed object returned by TIEHASH
     */
    public TieHash(String tiedPackage, RuntimeHash previousValue, RuntimeScalar self) {
        this.tiedPackage = tiedPackage;
        this.previousValue = previousValue;
        this.self = self;
    }

    /**
     * Helper method to call methods on the tied object.
     */
    private static RuntimeScalar tieCall(RuntimeHash hash, String method, RuntimeBase... args) {
        TieHash tieHash = (TieHash) hash.elements;
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
     * Calls a tie method if it exists in the tied object's class hierarchy.
     * Used by tiedDestroy() and tiedUntie() for optional methods.
     */
    private static RuntimeScalar tieCallIfExists(RuntimeHash hash, String methodName) {
        TieHash tieHash = (TieHash) hash.elements;
        RuntimeScalar self = tieHash.getSelf();
        String className = tieHash.getTiedPackage();

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
     * Fetches a value from a tied hash by key (delegates to FETCH).
     */
    public static RuntimeScalar tiedFetch(RuntimeHash hash, RuntimeScalar key) {
        return tieCall(hash, "FETCH", key);
    }

    /**
     * Stores a value into a tied hash (delegates to STORE).
     */
    public static RuntimeScalar tiedStore(RuntimeHash hash, RuntimeScalar key, RuntimeScalar value) {
        return tieCall(hash, "STORE", key, value);
    }

    /**
     * Deletes a key from a tied hash (delegates to DELETE).
     */
    public static RuntimeScalar tiedDelete(RuntimeHash hash, RuntimeScalar key) {
        return tieCall(hash, "DELETE", key);
    }

    /**
     * Checks if a key exists in a tied hash (delegates to EXISTS).
     */
    public static RuntimeScalar tiedExists(RuntimeHash hash, RuntimeScalar key) {
        return tieCall(hash, "EXISTS", key);
    }

    /**
     * Gets the first key for iterating over a tied hash (delegates to FIRSTKEY).
     */
    public static RuntimeScalar tiedFirstKey(RuntimeHash hash) {
        return tieCall(hash, "FIRSTKEY");
    }

    /**
     * Gets the next key during iteration (delegates to NEXTKEY).
     */
    public static RuntimeScalar tiedNextKey(RuntimeHash hash, RuntimeScalar previousKey) {
        return tieCall(hash, "NEXTKEY", previousKey);
    }

    /**
     * Gets the scalar representation of a tied hash (delegates to SCALAR).
     */
    public static RuntimeScalar tiedScalar(RuntimeHash hash) {
        return tieCall(hash, "SCALAR");
    }

    /**
     * Clears all keys from a tied hash (delegates to CLEAR).
     */
    public static RuntimeScalar tiedClear(RuntimeHash hash) {
        return tieCall(hash, "CLEAR");
    }

    /**
     * Called when a tied hash goes out of scope (delegates to DESTROY if exists).
     */
    public static RuntimeScalar tiedDestroy(RuntimeHash hash) {
        return tieCallIfExists(hash, "DESTROY");
    }

    /**
     * Unties a hash variable (delegates to UNTIE if exists).
     */
    public static RuntimeScalar tiedUntie(RuntimeHash hash) {
        return tieCallIfExists(hash, "UNTIE");
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
