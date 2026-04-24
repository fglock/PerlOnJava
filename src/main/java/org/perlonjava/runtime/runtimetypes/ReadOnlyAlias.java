package org.perlonjava.runtime.runtimetypes;

/**
 * A scalar that aliases a read-only literal but is NOT a
 * {@link RuntimeScalarReadOnly}, so the bytecode interpreter's defensive
 * "strip immutable proxy" paths (see {@code BytecodeInterpreter.ensureMutableScalar}
 * and {@code OpcodeHandlerExtended} mutating opcodes) leave it alone.
 * <p>
 * Used to implement Perl's {@code foreach} alias semantics for literal
 * rvalues without disrupting unrelated arithmetic that returns cached
 * read-only scalars (e.g. {@code MathOperators.subtract} returning
 * {@code RuntimeScalarCache.getScalarInt(-1)} for {@code $#_++} -- the
 * cache singleton must still be silently copied by mutating opcodes
 * because the user did not write a literal-alias).
 * <p>
 * <strong>Identity:</strong> shares {@code type} and {@code value} with
 * the wrapped read-only scalar so reads (stringification, getInt, etc.)
 * see the original value. All mutating methods throw
 * "Modification of a read-only value attempted".
 */
public class ReadOnlyAlias extends RuntimeScalar {

    public ReadOnlyAlias(RuntimeScalar src) {
        super();
        this.type = src.type;
        this.value = src.value;
        this.blessId = src.blessId;
    }

    private static RuntimeException readOnlyError() {
        return new RuntimeException("Modification of a read-only value attempted");
    }

    @Override
    public RuntimeScalar set(RuntimeScalar value) { throw readOnlyError(); }

    @Override
    public RuntimeScalar set(String value) { throw readOnlyError(); }

    @Override
    public RuntimeScalar set(long value) { throw readOnlyError(); }

    @Override
    public RuntimeScalar set(int value) { throw readOnlyError(); }

    @Override
    public RuntimeScalar set(boolean value) { throw readOnlyError(); }

    @Override
    public RuntimeScalar preAutoIncrement() { throw readOnlyError(); }

    @Override
    public RuntimeScalar postAutoIncrement() { throw readOnlyError(); }

    @Override
    public RuntimeScalar preAutoDecrement() { throw readOnlyError(); }

    @Override
    public RuntimeScalar postAutoDecrement() { throw readOnlyError(); }

    @Override
    public RuntimeScalar undefine() { throw readOnlyError(); }

    @Override
    public RuntimeScalar chop() { throw readOnlyError(); }

    @Override
    public RuntimeScalar chomp() { throw readOnlyError(); }
}
