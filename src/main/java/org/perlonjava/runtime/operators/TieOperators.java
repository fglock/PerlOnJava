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
            // Extra args only — classArg will be used as the invocant,
            // so RuntimeCode.call() will prepend it as $_[0]
            args = new RuntimeArray(Arrays.copyOfRange(scalars, 2, scalars.length));
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
        // When classArg is a blessed ref, use it as invocant so $_[0] is the object
        // (not a string). This matches Perl's tie() behavior where `tie *$obj, $obj`
        // passes the blessed object as $_[0] to TIEHANDLE.
        RuntimeScalar invocant = blessId != 0 ? classArg : new RuntimeScalar(className);
        RuntimeScalar self = RuntimeCode.call(
                invocant,
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
                TieHandle tieHandle = new TieHandle(className, previousValue, self);
                glob.IO.value = tieHandle;
                // Update selectedHandle so that `print` without explicit filehandle
                // goes through the tied handle (e.g., Test2::Plugin::IOEvents)
                if (previousValue == RuntimeIO.getSelectedHandle()) {
                    RuntimeIO.setSelectedHandle(tieHandle);
                }
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
     * <p>untie calls UNTIE (if defined), then releases the tie wrapper's reference
     * to the tied object. If no other strong references remain, DESTROY fires
     * immediately (matching Perl 5 refcounting semantics). If caller code holds
     * a reference (e.g. {@code my $obj = tie ...}), DESTROY is deferred until
     * that reference goes out of scope.</p>
     *
     * @param scalars varargs where scalars[0] is the tied variable (must be a reference)
     * @return true on success, undef if the variable wasn't tied
     */
    public static RuntimeScalar untie(int ctx, RuntimeBase... scalars) {
        RuntimeScalar variable = (RuntimeScalar) scalars[0];

        switch (variable.type) {
            case REFERENCE -> {
                RuntimeScalar scalar = variable.scalarDeref();
                if (scalar.type == TIED_SCALAR && scalar.value instanceof TieScalar tieScalar) {
                    TieScalar.tiedUntie(scalar);
                    RuntimeScalar previousValue = tieScalar.getPreviousValue();
                    scalar.type = previousValue.type;
                    scalar.value = previousValue.value;
                    tieScalar.releaseTiedObject();
                }
                return scalarTrue;
            }
            case ARRAYREFERENCE -> {
                RuntimeArray array = variable.arrayDeref();
                if (array.type == TIED_ARRAY) {
                    TieArray tieArray = (TieArray) array.elements;
                    TieArray.tiedUntie(array);
                    RuntimeArray previousValue = tieArray.getPreviousValue();
                    array.type = previousValue.type;
                    array.elements = previousValue.elements;
                    tieArray.releaseTiedObject();
                }
                return scalarTrue;
            }
            case HASHREFERENCE -> {
                RuntimeHash hash = variable.hashDeref();
                if (hash.type == TIED_HASH) {
                    TieHash tieHash = (TieHash) hash.elements;
                    TieHash.tiedUntie(hash);
                    RuntimeHash previousValue = tieHash.getPreviousValue();
                    hash.type = previousValue.type;
                    hash.elements = previousValue.elements;
                    hash.resetIterator();
                    tieHash.releaseTiedObject();
                }
                return scalarTrue;
            }
            case GLOBREFERENCE -> {
                RuntimeGlob glob = variable.globDeref();
                RuntimeScalar IO = glob.IO;
                if (IO.type == TIED_SCALAR) {
                    TieHandle currentTieHandle = (TieHandle) IO.value;
                    TieHandle.tiedUntie(currentTieHandle);
                    RuntimeIO previousValue = currentTieHandle.getPreviousValue();
                    IO.type = 0;    // XXX there is no type defined for IO handles
                    IO.value = previousValue;
                    // Restore selectedHandle if it pointed to the tied handle
                    if (currentTieHandle == RuntimeIO.getSelectedHandle()) {
                        RuntimeIO.setSelectedHandle(previousValue);
                    }
                    currentTieHandle.releaseTiedObject();
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
                    if (scalar.value instanceof TiedVariableBase tvb) {
                        RuntimeScalar selfObj = tvb.getSelf();
                        if (selfObj != null) {
                            return selfObj;
                        }
                    }
                }
                // Handle tied($$glob_ref) where $$glob_ref evaluates to a GLOB wrapped in a reference.
                // In Perl 5, tied($$fh) when the glob is tied via tie(*$fh, ...) returns the tied object.
                if (scalar.type == GLOB) {
                    RuntimeGlob g = (scalar instanceof RuntimeGlob) ? (RuntimeGlob) scalar : (scalar.value instanceof RuntimeGlob ? (RuntimeGlob) scalar.value : null);
                    if (g != null && g.IO.type == TIED_SCALAR) {
                        return ((TieHandle) g.IO.value).getSelf();
                    }
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
            case GLOB -> {
                // Handle tied($$glob_ref) where $$glob_ref evaluates to a GLOB.
                // In Perl 5, tied($$fh) when the glob is tied via tie(*$fh, ...) returns the tied object.
                RuntimeGlob globFromScalar = null;
                if (variable instanceof RuntimeGlob g) {
                    globFromScalar = g;
                } else if (variable.value instanceof RuntimeGlob g) {
                    globFromScalar = g;
                }
                if (globFromScalar != null) {
                    RuntimeScalar IO = globFromScalar.IO;
                    if (IO.type == TIED_SCALAR) {
                        return ((TieHandle) IO.value).getSelf();
                    }
                }
            }
            default -> {
                return scalarUndef;
            }
        }
        return scalarUndef;
    }

    /**
     * Implements Perl's lock() builtin function.
     * 
     * <p>In threaded Perl, lock() places an advisory lock on a shared variable.
     * In non-threaded Perl (and PerlOnJava), it's a no-op that returns its argument.</p>
     *
     * <p>The prototype for lock is \[$@%&*] so the argument is passed as a reference.</p>
     *
     * @param ctx the calling context
     * @param scalars varargs where scalars[0] is a reference to the variable to lock
     * @return for scalar refs, the dereferenced value; for arrays/hashes, the reference
     */
    public static RuntimeScalar lock(int ctx, RuntimeBase... scalars) {
        // No-op in non-threaded Perl - return the argument appropriately
        if (scalars.length == 0) {
            return scalarUndef;
        }
        RuntimeScalar variable = scalars[0].getFirst();
        // For scalar references, dereference to get the value
        // For other reference types (arrays, hashes), return the reference itself
        return switch (variable.type) {
            case REFERENCE -> variable.scalarDeref();
            default -> variable;
        };
    }
}
