package org.perlonjava.runtime.runtimetypes;

/**
 * An immutable scalar produced by evaluating an expression rather than by a
 * literal.  Lvalue subroutines must reject these as temporaries, while literal
 * constants retain Perl's distinct readonly diagnostic.
 */
public final class RuntimeScalarTemporary extends RuntimeScalarReadOnly {
    public RuntimeScalarTemporary(int value) {
        super(value);
    }
}
