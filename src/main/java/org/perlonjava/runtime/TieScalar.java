package org.perlonjava.runtime;

/**
 * TieScalar provides support for Perl's tie mechanism for scalar variables.
 *
 * <p>When a scalar is tied, all operations (fetch, store, etc.) are delegated to methods
 * in the tie handler object. Example usage:
 * <pre>{@code
 * tie $scalar, 'MyClass';     # Creates handler via TIESCALAR
 * $scalar = 42;               # Calls STORE
 * my $val = $scalar;          # Calls FETCH
 * untie $scalar;              # Calls UNTIE (if exists)
 * # DESTROY called when handler goes out of scope
 * }</pre>
 * </p>
 *
 * @see RuntimeScalar
 */
public class TieScalar extends TiedVariableBase {

    /**
     * The original value of the scalar before it was tied.
     */
    private final RuntimeScalar previousValue;

    /**
     * Creates a new TieScalar instance.
     *
     * @param tiedPackage   the package name this scalar is tied to
     * @param previousValue the value of the scalar before it was tied
     * @param self          the blessed object returned by TIESCALAR
     */
    public TieScalar(String tiedPackage, RuntimeScalar previousValue, RuntimeScalar self) {
        super(self, tiedPackage);
        this.previousValue = previousValue;
    }

    /**
     * Called when a tied scalar goes out of scope (delegates to DESTROY if exists).
     */
    public static RuntimeScalar tiedDestroy(RuntimeScalar runtimeScalar) {
        return ((TieScalar) runtimeScalar.value).tieCallIfExists("DESTROY");
    }

    /**
     * Unties a tied scalar (delegates to UNTIE if exists).
     */
    public static RuntimeScalar tiedUntie(RuntimeScalar runtimeScalar) {
        return ((TieScalar) runtimeScalar.value).tieCallIfExists("UNTIE");
    }

    /**
     * Fetches the value from a tied scalar (delegates to FETCH).
     */
    public RuntimeScalar tiedFetch() {
        return tieCall("FETCH");
    }

    /**
     * Stores a value into a tied scalar (delegates to STORE).
     */
    public RuntimeScalar tiedStore(RuntimeScalar v) {
        return tieCall("STORE", v);
    }

    public RuntimeScalar getPreviousValue() {
        return previousValue;
    }
}