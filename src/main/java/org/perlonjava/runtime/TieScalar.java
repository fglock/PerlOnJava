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
public class TieScalar {

    /** The tied object (handler) that implements the tie interface methods. */
    private final RuntimeScalar self;

    /** The package name that this scalar is tied to. */
    private final String tiedPackage;

    /** The original value of the scalar before it was tied. */
    private final RuntimeScalar previousValue;

    /**
     * Creates a new TieScalar instance.
     *
     * @param tiedPackage   the package name this scalar is tied to
     * @param previousValue the value of the scalar before it was tied
     * @param self          the blessed object returned by TIESCALAR
     */
    public TieScalar(String tiedPackage, RuntimeScalar previousValue, RuntimeScalar self) {
        this.tiedPackage = tiedPackage;
        this.previousValue = previousValue;
        this.self = self;
    }

    public static RuntimeScalar tieCall(RuntimeScalar runtimeScalar, String method, RuntimeBase... args) {
        RuntimeScalar self = ((TieScalar) runtimeScalar.value).getSelf();
        String className = ((TieScalar) runtimeScalar.value).getTiedPackage();

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
    private static RuntimeScalar tieCallIfExists(RuntimeScalar runtimeScalar, String methodName) {
        RuntimeScalar self = ((TieScalar) runtimeScalar.value).getSelf();
        String className = ((TieScalar) runtimeScalar.value).getTiedPackage();

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
     * Fetches the value from a tied scalar (delegates to FETCH).
     */
    public static RuntimeScalar tiedFetch(RuntimeScalar runtimeScalar) {
        return tieCall(runtimeScalar, "FETCH");
    }

    /**
     * Stores a value into a tied scalar (delegates to STORE).
     */
    public static RuntimeScalar tiedStore(RuntimeScalar runtimeScalar, RuntimeScalar value) {
        return tieCall(runtimeScalar, "STORE", value);
    }

    /**
     * Called when a tied scalar goes out of scope (delegates to DESTROY if exists).
     */
    public static RuntimeScalar tiedDestroy(RuntimeScalar runtimeScalar) {
        return tieCallIfExists(runtimeScalar, "DESTROY");
    }

    /**
     * Unties a tied scalar (delegates to UNTIE if exists).
     */
    public static RuntimeScalar tiedUntie(RuntimeScalar runtimeScalar) {
        return tieCallIfExists(runtimeScalar, "UNTIE");
    }

    public RuntimeScalar getPreviousValue() {
        return previousValue;
    }

    public RuntimeScalar getSelf() {
        return self;
    }

    public String getTiedPackage() {
        return tiedPackage;
    }
}