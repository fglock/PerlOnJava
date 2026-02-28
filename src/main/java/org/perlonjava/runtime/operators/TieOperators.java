package org.perlonjava.runtime.operators;

import org.perlonjava.runtime.runtimetypes.*;

import java.util.Arrays;

import static org.perlonjava.runtime.runtimetypes.RuntimeArray.TIED_ARRAY;
import static org.perlonjava.runtime.runtimetypes.RuntimeHash.TIED_HASH;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarTrue;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarUndef;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.*;

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
    public static RuntimeScalar tie(int ctx, RuntimeBase... scalars) {
        RuntimeScalar variable = scalars[0].getFirst();
        RuntimeScalar classArg = scalars[1].getFirst();

        // Determine the class name and arguments
        String className;
        RuntimeArray args;

        // Check if classArg is a blessed reference (object)
        int blessId = blessedId(classArg);
        if (blessId != 0) {
            // classArg is a blessed object, get the package name
            className = NameNormalizer.getBlessStr(blessId);
            // Prepend the object to the arguments
            RuntimeBase[] argsWithObj = new RuntimeBase[scalars.length - 1];
            argsWithObj[0] = classArg;
            System.arraycopy(scalars, 2, argsWithObj, 1, scalars.length - 2);
            args = new RuntimeArray(argsWithObj);
        } else {
            // classArg is a string class name
            className = classArg.getBoolean() ? scalars[1].toString() : "main";
            args = new RuntimeArray(Arrays.copyOfRange(scalars, 2, scalars.length));
        }

        String method = switch (variable.type) {
            case REFERENCE -> "TIESCALAR";
            case ARRAYREFERENCE -> "TIEARRAY";
            case HASHREFERENCE -> "TIEHASH";
            case GLOBREFERENCE -> "TIEHANDLE";
            default -> throw new PerlCompilerException("Unsupported variable type for tie()");
        };

        // untie() if the variable is already tied
        untie(ctx, variable);

        // Call the Perl method
        RuntimeScalar self = RuntimeCode.call(
                new RuntimeScalar(className),
                new RuntimeScalar(method),
                null,
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
                hash.resetIterator();
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
    public static RuntimeScalar untie(int ctx, RuntimeBase... scalars) {
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
                    hash.resetIterator();
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
    public static RuntimeScalar tied(int ctx, RuntimeBase... scalars) {
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
                if (array.type == TIED_ARRAY && array.elements instanceof TieArray) {
                    return ((TieArray) array.elements).getSelf();
                }
            }
            case HASHREFERENCE -> {
                RuntimeHash hash = variable.hashDeref();
                if (hash.type == TIED_HASH && hash.elements instanceof TieHash) {
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
