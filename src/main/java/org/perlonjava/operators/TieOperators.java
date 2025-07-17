package org.perlonjava.operators;

import org.perlonjava.runtime.*;

import java.util.Arrays;

import static org.perlonjava.runtime.RuntimeArray.TIED_ARRAY;
import static org.perlonjava.runtime.RuntimeHash.TIED_HASH;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarTrue;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;
import static org.perlonjava.runtime.RuntimeScalarType.*;

public class TieOperators {
    /**
     * Implements Perl's tie() builtin function.
     *
     * <p>Binds a variable to a package class that provides the implementation for that variable.
     * The variable's normal behavior is replaced by method calls to the tied object.</p>
     *
     * <p>In Perl: {@code tie $scalar, 'ClassName', @args}</p>
     *
     * @param scalars varargs where:
     *                - scalars[0] is the variable to tie (must be a reference to scalar, array, hash, or glob)
     *                - scalars[1] is the class name to tie to
     *                - scalars[2..n] are optional arguments passed to the TIE* constructor
     * @return the object returned by the TIE* constructor method
     * @throws PerlCompilerException if the variable type is not supported or not yet implemented
     */
    public static RuntimeScalar tie(RuntimeBase... scalars) {
        RuntimeScalar variable = (RuntimeScalar) scalars[0];
        String className = scalars[1].toString();
        RuntimeArray args = new RuntimeArray(Arrays.copyOfRange(scalars, 2, scalars.length));

        // untie() if the variable is already tied
        untie(variable);

        String method = switch (variable.type) {
            case REFERENCE -> "TIESCALAR";
            case ARRAYREFERENCE -> "TIEARRAY";
            case HASHREFERENCE -> "TIEHASH";
            case GLOBREFERENCE -> "TIEHANDLE";
            default -> throw new PerlCompilerException("Unsupported variable type for tie()");
        };

        // Call the Perl method
        RuntimeScalar self = RuntimeCode.call(
                new RuntimeScalar(className),
                new RuntimeScalar(method),
                className,
                args,
                RuntimeContextType.SCALAR
        ).getFirst();

        switch (variable.type) {
            case REFERENCE -> {
                RuntimeScalar scalar = variable.scalarDeref();
                RuntimeScalar previousValue = new RuntimeScalar(scalar);
                scalar.type = TIED_SCALAR;
                scalar.value = new TieScalar(className, previousValue, self);
            }
            case ARRAYREFERENCE -> {
                RuntimeArray array = variable.arrayDeref();
                RuntimeArray previousValue = new RuntimeArray(array);
                array.type = TIED_ARRAY;
                array.elements = new TieArray(className, previousValue, self);
            }
            case HASHREFERENCE -> {
                RuntimeHash hash = variable.hashDeref();
                RuntimeHash previousValue = RuntimeHash.createHash(hash);
                hash.type = TIED_HASH;
                hash.elements = new TieHash(className, previousValue, self);
            }
            case GLOBREFERENCE -> {
                RuntimeGlob glob = variable.globDeref();
                RuntimeScalar IO = glob.IO;
                IO.type = TIED_SCALAR;
                // IO.value = new TieFile(className, IO.value, self);
                // ...
                throw new PerlCompilerException("tie(GLOB) not implemented");
            }
            default -> {
                return scalarUndef;
            }
        }
        return self;
    }

    /**
     * Implements Perl's untie() builtin function.
     *
     * <p>Breaks the binding between a variable and the package class it was tied to.
     * The variable returns to its normal behavior.</p>
     *
     * <p>In Perl: {@code untie $scalar}</p>
     *
     * @param scalars varargs where scalars[0] is the tied variable (must be a reference)
     * @return true on success, undef if the variable wasn't tied
     * @throws PerlCompilerException if the variable type is not yet implemented
     */
    public static RuntimeScalar untie(RuntimeBase... scalars) {
        RuntimeScalar variable = (RuntimeScalar) scalars[0];

        switch (variable.type) {
            case REFERENCE -> {
                RuntimeScalar scalar = variable.scalarDeref();
                if (scalar.type == TIED_SCALAR) {
                    TieScalar.tiedDestroy(scalar);
                    RuntimeScalar previousValue = ((TieScalar) scalar.value).getPreviousValue();
                    scalar.type = previousValue.type;
                    scalar.value = previousValue.value;
                }
                // return scalar;
                return scalarTrue;
            }
            case ARRAYREFERENCE -> throw new PerlCompilerException("untie(ARRAY) not implemented");
            case HASHREFERENCE -> throw new PerlCompilerException("untie(HASH) not implemented");
            case GLOBREFERENCE -> throw new PerlCompilerException("untie(GLOB) not implemented");
            default -> {
                return scalarUndef;
            }
        }
    }

    /**
     * Implements Perl's tied() builtin function.
     *
     * <p>Returns a reference to the object underlying a tied variable.
     * If the variable is not tied, returns undef.</p>
     *
     * <p>In Perl: {@code my $obj = tied $scalar}</p>
     *
     * @param scalars varargs where scalars[0] is the potentially tied variable (must be a reference)
     * @return the object that the variable is tied to, or undef if not tied
     * @throws PerlCompilerException if the variable type is not yet implemented
     */
    public static RuntimeScalar tied(RuntimeBase... scalars) {
        RuntimeScalar variable = (RuntimeScalar) scalars[0];
        switch (variable.type) {
            case REFERENCE -> {
                RuntimeScalar scalar = variable.scalarDeref();
                if (scalar.type == TIED_SCALAR) {
                    return ((TieScalar) scalar.value).getSelf();
                }
            }
            case ARRAYREFERENCE -> throw new PerlCompilerException("tied(ARRAY) not implemented");
            case HASHREFERENCE -> throw new PerlCompilerException("tied(HASH) not implemented");
            case GLOBREFERENCE -> throw new PerlCompilerException("tied(GLOB) not implemented");
            default -> {
                return scalarUndef;
            }
        }
        return scalarUndef;
    }
}
