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

        String method = switch (variable.type) {
            case REFERENCE -> "TIESCALAR";
            case ARRAYREFERENCE -> "TIEARRAY";
            case HASHREFERENCE -> "TIEHASH";
            case GLOBREFERENCE -> "TIEHANDLE";
            default -> throw new PerlCompilerException("Unsupported variable type for tie()");
        };

        // untie() if the variable is already tied
        untie(variable);

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
                array.elements = new TieArray(className, previousValue, self, array);
            }
            case HASHREFERENCE -> {
                RuntimeHash hash = variable.hashDeref();
                RuntimeHash previousValue = RuntimeHash.createHash(hash);
                hash.type = TIED_HASH;
                hash.elements = new TieHash(className, previousValue, self);
            }
            case GLOBREFERENCE -> {
                RuntimeGlob glob = variable.globDeref();
                RuntimeIO previousValue = (RuntimeIO) glob.IO.value;
                glob.IO.type = TIED_SCALAR;
                glob.IO.value = new TieHandle(className, previousValue, self);
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
     */
    public static RuntimeScalar untie(RuntimeBase... scalars) {
        RuntimeScalar variable = (RuntimeScalar) scalars[0];

        switch (variable.type) {
            case REFERENCE -> {
                RuntimeScalar scalar = variable.scalarDeref();
                if (scalar.type == TIED_SCALAR) {
                    TieScalar.tiedUntie(scalar);
                    TieScalar.tiedDestroy(scalar);
                    RuntimeScalar previousValue = ((TieScalar) scalar.value).getPreviousValue();
                    scalar.type = previousValue.type;
                    scalar.value = previousValue.value;
                }
                return scalarTrue;
            }
            case ARRAYREFERENCE -> {
                RuntimeArray array = variable.arrayDeref();
                if (array.type == TIED_ARRAY) {
                    TieArray.tiedUntie(array);
                    TieArray.tiedDestroy(array);
                    RuntimeArray previousValue = ((TieArray) array.elements).getPreviousValue();
                    array.type = previousValue.type;
                    array.elements = previousValue.elements;
                }
                return scalarTrue;
            }
            case HASHREFERENCE -> {
                RuntimeHash hash = variable.hashDeref();
                if (hash.type == TIED_HASH) {
                    TieHash.tiedUntie(hash);
                    TieHash.tiedDestroy(hash);
                    RuntimeHash previousValue = ((TieHash) hash.elements).getPreviousValue();
                    hash.type = previousValue.type;
                    hash.elements = previousValue.elements;
                }
                return scalarTrue;
            }
            case GLOBREFERENCE -> {
                RuntimeGlob glob = variable.globDeref();
                RuntimeScalar IO = glob.IO;
                if (IO.type == TIED_SCALAR) {
                    TieHandle.tiedUntie((TieHandle) IO.value);
                    TieHandle.tiedDestroy((TieHandle) IO.value);
                    RuntimeIO previousValue = ((TieHandle) IO.value).getPreviousValue();
                    IO.type = 0;    // XXX there is no type defined for IO handles
                    IO.value = previousValue;
                }
                return scalarTrue;
            }
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
            case ARRAYREFERENCE -> {
                RuntimeArray array = variable.arrayDeref();
                if (array.type == TIED_ARRAY) {
                    return ((TieArray) array.elements).getSelf();
                }
            }
            case HASHREFERENCE -> {
                RuntimeHash hash = variable.hashDeref();
                if (hash.type == TIED_HASH) {
                    return ((TieHash) hash.elements).getSelf();
                }
            }
            case GLOBREFERENCE -> {
                RuntimeGlob glob = variable.globDeref();
                RuntimeScalar IO = glob.IO;
                if (IO.type == TIED_SCALAR) {
                    return ((TieHandle) IO.value).getSelf();
                }
            }
            default -> {
                return scalarUndef;
            }
        }
        return scalarUndef;
    }
}
