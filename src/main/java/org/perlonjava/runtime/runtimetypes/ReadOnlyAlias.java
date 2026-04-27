package org.perlonjava.runtime.runtimetypes;

/**
 * A scalar that aliases a read-only literal in foreach loop context.
 * <p>
 * Extends {@link RuntimeScalarReadOnly} so existing code that checks
 * {@code instanceof RuntimeScalarReadOnly} (e.g. {@code utf8::upgrade},
 * {@code utf8::downgrade}, {@code RuntimeGlob.set}, autovivification
 * paths in {@code RuntimeScalar} and {@code RuntimeBaseProxy}) treats
 * it like any other read-only scalar -- no in-place mutation, fall
 * back to scalar replacement or undef.
 * <p>
 * <strong>Special case:</strong> the bytecode interpreter's
 * {@code isImmutableProxy}/{@code ensureMutableScalar} pair (see
 * {@link org.perlonjava.backend.bytecode.BytecodeInterpreter} and
 * {@link org.perlonjava.backend.bytecode.InlineOpcodeHandler}) used to
 * silently unbox any RuntimeScalarReadOnly into a fresh mutable
 * RuntimeScalar at every {@code ALIAS}, {@code SET_SCALAR}, and
 * {@code PRE/POST_AUTOINCREMENT/DECREMENT} opcode. That behaviour is
 * correct for cached read-only singletons (e.g. arithmetic results
 * like {@code MathOperators.subtract} returning
 * {@code RuntimeScalarCache.getScalarInt(-1)} for {@code $#_++}), but
 * it broke Perl's foreach-aliasing semantics: {@code for (3) { ++$_ }}
 * must throw "Modification of a read-only value", not silently
 * succeed against a copy.
 * <p>
 * The interpreter's {@code isImmutableProxy} therefore explicitly
 * excludes {@code ReadOnlyAlias}: instances slip through the strip
 * path and reach {@code preAutoIncrement()}, where
 * {@link RuntimeScalarReadOnly#vivify} throws the expected error.
 */
public class ReadOnlyAlias extends RuntimeScalarReadOnly {

    /** The original read-only scalar this aliases. Reads delegate to it. */
    private final RuntimeScalar src;

    public ReadOnlyAlias(RuntimeScalar src) {
        super();
        this.src = src;
        this.type = src.type;
        this.value = src.value;
        this.blessId = src.blessId;
    }

    @Override
    public String toString() {
        return src.toString();
    }

    @Override
    public boolean getBoolean() {
        return src.getBoolean();
    }

    @Override
    public int getInt() {
        return src.getInt();
    }

    @Override
    public long getLong() {
        return src.getLong();
    }

    @Override
    public double getDouble() {
        return src.getDouble();
    }
}
