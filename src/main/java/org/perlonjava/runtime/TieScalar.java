package org.perlonjava.runtime;

/**
 * TieScalar provides support for Perl's tie mechanism for scalar variables.
 *
 * <p>In Perl, the tie mechanism allows scalar variables to have their operations
 * intercepted and handled by custom classes. When a scalar is tied, all operations
 * on that scalar (fetching values, storing values, etc.) are delegated to methods
 * in the tie handler object.</p>
 *
 * <p>This class provides static methods that are called when operations are
 * performed on tied scalars.</p>
 *
 * @see RuntimeScalar
 */
public class TieScalar {

    /**
     * The tied object (handler) that implements the tie interface methods.
     * This is the blessed object returned by TIESCALAR.
     */
    private final RuntimeScalar self;

    /**
     * The package name that this scalar is tied to.
     * Used for method dispatch and error reporting.
     */
    private final String tiedPackage;

    /**
     * The original value of the scalar before it was tied.
     * This value might be needed for untie operations or debugging.
     */
    private final RuntimeScalar previousValue;

    /**
     * Creates a new TieScalar instance.
     *
     * @param tiedPackage   the package name this scalar is tied to
     * @param previousValue the value of the scalar before it was tied (may be null/undef)
     * @param self          the blessed object returned by TIESCALAR that handles tied operations
     */
    public TieScalar(String tiedPackage, RuntimeScalar previousValue, RuntimeScalar self) {
        this.tiedPackage = tiedPackage;
        this.previousValue = new RuntimeScalar(previousValue);
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
     * Fetches the value from a tied scalar variable.
     *
     * <p>This method is called whenever the value of a tied scalar is accessed.
     * It delegates to the FETCH method of the tie handler object associated
     * with the scalar.</p>
     *
     * <p>In Perl, this corresponds to operations like:
     * <pre>{@code
     * my $value = $tied_scalar;  # Calls FETCH
     * print $tied_scalar;        # Calls FETCH
     * if ($tied_scalar) { ... }  # Calls FETCH
     * }</pre>
     * </p>
     *
     * @param runtimeScalar the tied scalar whose value is being fetched
     * @return the value returned by the tie handler's FETCH method
     */
    public static RuntimeScalar tiedFetch(RuntimeScalar runtimeScalar) {
        return tieCall(runtimeScalar, "FETCH");
    }

    /**
     * Stores a value into a tied scalar variable.
     *
     * <p>This method is called whenever a value is assigned to a tied scalar.
     * It delegates to the STORE method of the tie handler object associated
     * with the scalar.</p>
     *
     * <p>In Perl, this corresponds to operations like:
     * <pre>{@code
     * $tied_scalar = 42;           # Calls STORE with 42
     * $tied_scalar = "hello";      # Calls STORE with "hello"
     * $tied_scalar++;              # Calls FETCH, then STORE
     * }</pre>
     * </p>
     *
     * @param runtimeScalar the tied scalar being assigned to
     * @param value         the value to store in the tied scalar
     * @return the value returned by the tie handler's STORE method
     */
    public static RuntimeScalar tiedStore(RuntimeScalar runtimeScalar, RuntimeScalar value) {
        return tieCall(runtimeScalar, "STORE", value);
    }

    /**
     * Destroys a tied scalar variable.
     *
     * <p>This method is called when a tied scalar goes out of scope or is being
     * garbage collected. It delegates to the DESTROY method of the tie handler
     * object associated with the scalar, if such a method exists.</p>
     *
     * <p>In Perl, this corresponds to:
     * <pre>{@code
     * {
     *     my $tied_scalar;
     *     tie $tied_scalar, 'MyClass';
     *     # ... use $tied_scalar ...
     * } # DESTROY called here when $tied_scalar goes out of scope
     * }</pre>
     * </p>
     *
     * @param runtimeScalar the tied scalar being destroyed
     * @return the value returned by the tie handler's DESTROY method, or undef if no DESTROY method exists
     */
    public static RuntimeScalar tiedDestroy(RuntimeScalar runtimeScalar) {
        // Check if DESTROY method exists before calling it
        // In Perl, DESTROY is optional for tied variables
        try {
            return tieCall(runtimeScalar, "DESTROY");
        } catch (Exception e) {
            // If DESTROY method doesn't exist or fails, return undef
            return RuntimeScalarCache.scalarUndef;
        }
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
