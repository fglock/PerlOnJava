package org.perlonjava.runtime;

import static org.perlonjava.runtime.RuntimeScalarType.UNDEF;

/**
 * The RuntimeScalarReadOnly class represents an immutable scalar value in the runtime environment.
 * It is used for caching and reusing common scalar values such as integers, booleans, and strings.
 */
public class RuntimeScalarReadOnly extends RuntimeBaseProxy {

    // Immutable fields representing the scalar value
    final boolean b;
    final String s;

    // Lazy computation fields for string-to-number conversions
    // These are null until first access to avoid premature warning generation
    private Integer i;
    private Double d;

    /**
     * Constructs a RuntimeScalarReadOnly representing an undefined value.
     */
    public RuntimeScalarReadOnly() {
        super();
        this.b = false;
        this.i = 0;
        this.s = "";
        this.d = 0.0;
        this.value = null;
        this.type = UNDEF;
    }

    /**
     * Constructs a RuntimeScalarReadOnly representing an integer value.
     *
     * @param i the integer value
     */
    public RuntimeScalarReadOnly(int i) {
        super();
        this.b = (i != 0);
        this.i = i;
        this.s = Integer.toString(i);
        this.d = (double) i;
        this.value = i;
        this.type = RuntimeScalarType.INTEGER;
    }

    /**
     * Constructs a RuntimeScalarReadOnly representing a boolean value.
     *
     * @param b the boolean value
     */
    public RuntimeScalarReadOnly(boolean b) {
        super();
        this.b = b;
        this.i = b ? 1 : 0;
        this.s = b ? "1" : "";
        this.d = b ? 1.0 : 0.0;
        this.value = b;
        this.type = RuntimeScalarType.BOOLEAN;
    }

    /**
     * Constructs a RuntimeScalarReadOnly representing a string value.
     *
     * @param s the string value
     */
    public RuntimeScalarReadOnly(String s) {
        super();
        // Don't pre-compute numeric values for strings as this would trigger
        // "Argument isn't numeric" warnings at construction time instead of at use time.
        this.b = !s.isEmpty();  // String boolean: true if non-empty
        this.i = null;  // Computed lazily on first getInt() call
        this.s = s;
        this.d = null;  // Computed lazily on first getDouble() call
        this.value = s;
        this.type = RuntimeScalarType.STRING;
    }

    /**
     * Throws an exception as this scalar is immutable and cannot be modified.
     *
     * @throws RuntimeException indicating that the constant item cannot be modified
     */
    @Override
    void vivify() {
        throw new RuntimeException("Modification of a read-only value attempted");
    }

    /**
     * Retrieves the integer representation of the scalar.
     * For STRING type, computes lazily to ensure warnings are generated at use time.
     *
     * @return the integer value
     */
    @Override
    public int getInt() {
        // For strings, compute lazily to generate warnings at use time, not construction time
        if (i == null) {
            i = super.getInt();
        }
        return i;
    }

    /**
     * Retrieves the double representation of the scalar.
     * For STRING type, computes lazily to ensure warnings are generated at use time.
     *
     * @return the double value
     */
    @Override
    public double getDouble() {
        // For strings, compute lazily to generate warnings at use time, not construction time
        if (d == null) {
            d = super.getDouble();
        }
        return d;
    }

    /**
     * Retrieves the string representation of the scalar.
     *
     * @return the string value
     */
    @Override
    public String toString() {
        return s;
    }

    /**
     * Retrieves the boolean representation of the scalar.
     *
     * @return the boolean value
     */
    @Override
    public boolean getBoolean() {
        return b;
    }

    @Override
    public RuntimeHash hashDeref() {
        if (this.type == UNDEF) {
            throw new PerlCompilerException("Can't use an undefined value as a HASH reference");
        }
        throw new PerlCompilerException("Can't use value as a HASH reference");
    }

    @Override
    public RuntimeArray arrayDeref() {
        if (this.type == UNDEF) {
            throw new PerlCompilerException("Can't use an undefined value as an ARRAY reference");
        }
        // For non-reference values (like constants), return an empty array
        // This matches Perl's behavior where 1->[0] returns undef without error
        return new RuntimeArray();
    }
}
