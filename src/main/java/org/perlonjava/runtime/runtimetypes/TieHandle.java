package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.runtime.mro.InheritanceResolver;

/**
 * TieHandle provides support for Perl's tie mechanism for filehandle variables.
 *
 * <p>When a filehandle is tied, all operations are delegated to methods in the tie handler object.
 * Example usage:
 * <pre>{@code
 * tie *FH, 'MyClass';             # Creates handler via TIEHANDLE
 * print FH "data";                # Calls PRINT
 * printf FH "%d", 42;             # Calls PRINTF
 * my $line = <FH>;                # Calls READLINE
 * my $char = getc(FH);            # Calls GETC
 * read(FH, $buf, $len);           # Calls READ
 * seek(FH, $pos, $whence);        # Calls SEEK
 * my $pos = tell(FH);             # Calls TELL
 * if (eof(FH)) { ... }            # Calls EOF
 * close(FH);                      # Calls CLOSE
 * binmode(FH, ":utf8");           # Calls BINMODE
 * my $fd = fileno(FH);            # Calls FILENO
 * syswrite(FH, $data, $len);      # Calls WRITE
 * untie *FH;                      # Calls UNTIE (if exists)
 * # DESTROY called when handler goes out of scope
 * }</pre>
 * </p>
 *
 * @see RuntimeIO
 */
public class TieHandle extends RuntimeIO {

    /**
     * The tied object (handler) that implements the tie interface methods.
     */
    private final RuntimeScalar self;

    /**
     * The package name that this handle is tied to.
     */
    private final String tiedPackage;

    /**
     * The original value of the handle before it was tied.
     */
    private final RuntimeIO previousValue;

    /**
     * Creates a new TieHandle instance.
     *
     * @param tiedPackage   the package name this handle is tied to
     * @param previousValue the value of the handle before it was tied
     * @param self          the blessed object returned by TIEHANDLE
     */
    public TieHandle(String tiedPackage, RuntimeIO previousValue, RuntimeScalar self) {
        this.tiedPackage = tiedPackage;
        this.previousValue = previousValue;
        this.self = self;
    }

    /**
     * Prints data to a tied filehandle (delegates to PRINT).
     */
    public static RuntimeScalar tiedPrint(TieHandle tieHandle, RuntimeList args) {
        return tieHandle.tieCall("PRINT", args);
    }

    /**
     * Prints formatted data to a tied filehandle (delegates to PRINTF).
     */
    public static RuntimeScalar tiedPrintf(TieHandle tieHandle, RuntimeList args) {
        return tieHandle.tieCall("PRINTF", args);
    }

    /**
     * Reads a line from a tied filehandle (delegates to READLINE).
     */
    public static RuntimeBase tiedReadline(TieHandle tieHandle, int ctx) {
        RuntimeList result = RuntimeCode.call(
                tieHandle.self,
                new RuntimeScalar("READLINE"),
                null,
                new RuntimeArray(tieHandle.self),
                ctx
        );

        if (ctx == RuntimeContextType.LIST) {
            return result;
        } else {
            return result.getFirst();
        }
    }

    /**
     * Gets a character from a tied filehandle (delegates to GETC).
     */
    public static RuntimeScalar tiedGetc(TieHandle tieHandle) {
        return tieHandle.tieCall("GETC");
    }

    /**
     * Reads data from a tied filehandle (delegates to READ).
     */
    public static RuntimeScalar tiedRead(TieHandle tieHandle, RuntimeList args) {
        return tieHandle.tieCall("READ", args);
    }

    /**
     * Seeks to a position in a tied filehandle (delegates to SEEK).
     */
    public static RuntimeScalar tiedSeek(TieHandle tieHandle, RuntimeList args) {
        return tieHandle.tieCall("SEEK", args);
    }

    /**
     * Gets the current position in a tied filehandle (delegates to TELL).
     */
    public static RuntimeScalar tiedTell(TieHandle tieHandle) {
        return tieHandle.tieCall("TELL");
    }

    /**
     * Checks if a tied filehandle is at end-of-file (delegates to EOF).
     */
    public static RuntimeScalar tiedEof(TieHandle tieHandle, RuntimeList args) {
        return tieHandle.tieCall("EOF", args);
    }

    /**
     * Closes a tied filehandle (delegates to CLOSE).
     */
    public static RuntimeScalar tiedClose(TieHandle tieHandle) {
        return tieHandle.tieCall("CLOSE");
    }

    /**
     * Sets binary mode on a tied filehandle (delegates to BINMODE).
     */
    public static RuntimeScalar tiedBinmode(TieHandle tieHandle, RuntimeList args) {
        return tieHandle.tieCall("BINMODE", args);
    }

    /**
     * Gets the file descriptor number of a tied filehandle (delegates to FILENO).
     */
    public static RuntimeScalar tiedFileno(TieHandle tieHandle) {
        return tieHandle.tieCall("FILENO");
    }

    /**
     * Called when a tied filehandle goes out of scope (delegates to DESTROY if exists).
     */
    public static RuntimeScalar tiedDestroy(TieHandle tieHandle) {
        return tieHandle.tieCallIfExists("DESTROY");
    }

    /**
     * Writes data to a tied filehandle (delegates to WRITE).
     */
    public static RuntimeScalar tiedWrite(TieHandle tieHandle, RuntimeScalar data, RuntimeScalar length, RuntimeScalar offset) {
        return tieHandle.tieCall("WRITE", data, length, offset);
    }

    /**
     * Unties a filehandle (delegates to UNTIE if exists).
     */
    public static RuntimeScalar tiedUntie(TieHandle tieHandle) {
        return tieHandle.tieCallIfExists("UNTIE");
    }

    /**
     * Helper method to call methods on the tied object.
     */
    private RuntimeScalar tieCall(String method, RuntimeBase... args) {
        // Call the Perl method
        return RuntimeCode.call(
                self,
                new RuntimeScalar(method),
                null,
                new RuntimeArray(args),
                RuntimeContextType.SCALAR
        ).getFirst();
    }

    /**
     * Calls a tie method if it exists in the tied object's class hierarchy.
     * Used by tiedDestroy() and tiedUntie() for optional methods.
     */
    private RuntimeScalar tieCallIfExists(String methodName) {
        String className = getTiedPackage();

        // Check if method exists in the class hierarchy
        RuntimeScalar method = InheritanceResolver.findMethodInHierarchy(methodName, className, null, 0);
        if (method == null) {
            // Method doesn't exist, return undef
            return RuntimeScalarCache.scalarUndef;
        }

        // Method exists, call it
        return RuntimeCode.apply(method, new RuntimeArray(self), RuntimeContextType.SCALAR).getFirst();
    }

    public RuntimeIO getPreviousValue() {
        return previousValue;
    }

    public RuntimeScalar getSelf() {
        return self;
    }

    public String getTiedPackage() {
        return tiedPackage;
    }

    @Override
    public String toString() {
        return "TIED_HANDLE(" + tiedPackage + ")";
    }
}
