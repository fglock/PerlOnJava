package org.perlonjava.runtime;

import org.perlonjava.operators.TieOperators;

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

    /** The tied object (handler) that implements the tie interface methods. */
    private final RuntimeScalar self;

    /** The package name that this handle is tied to. */
    private final String tiedPackage;

    /** The original value of the handle before it was tied. */
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
     * Helper method to call methods on the tied object.
     */
    private RuntimeScalar tieCall(String method, RuntimeBase... args) {
        // Call the Perl method
        return RuntimeCode.call(
                self,
                new RuntimeScalar(method),
                tiedPackage,
                new RuntimeArray(args),
                RuntimeContextType.SCALAR
        ).getFirst();
    }

    /**
     * Prints data to a tied filehandle (delegates to PRINT).
     */
    public static RuntimeScalar tiedPrint(TieHandle tieHandle, RuntimeList args) {
        // Convert RuntimeList to RuntimeArray for the method call
        RuntimeArray argsArray = new RuntimeArray();
        argsArray.elements.add(tieHandle.self);
        // argsArray.elements.addAll(args.elements); XXX

        return RuntimeCode.call(
                tieHandle.self,
                new RuntimeScalar("PRINT"),
                tieHandle.tiedPackage,
                argsArray,
                RuntimeContextType.SCALAR
        ).getFirst();
    }

    /**
     * Prints formatted data to a tied filehandle (delegates to PRINTF).
     */
    public static RuntimeScalar tiedPrintf(TieHandle tieHandle, RuntimeScalar format, RuntimeList args) {
        RuntimeArray argsArray = new RuntimeArray();
        argsArray.elements.add(tieHandle.self);
        argsArray.elements.add(format);
        // argsArray.elements.addAll(args.elements); XXX

        return RuntimeCode.call(
                tieHandle.self,
                new RuntimeScalar("PRINTF"),
                tieHandle.tiedPackage,
                argsArray,
                RuntimeContextType.SCALAR
        ).getFirst();
    }

    /**
     * Reads a line from a tied filehandle (delegates to READLINE).
     */
    public static RuntimeBase tiedReadline(TieHandle tieHandle, int ctx) {
        RuntimeList result = RuntimeCode.call(
                tieHandle.self,
                new RuntimeScalar("READLINE"),
                tieHandle.tiedPackage,
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
    public static RuntimeScalar tiedRead(TieHandle tieHandle, RuntimeScalar buffer, RuntimeScalar length, RuntimeScalar offset) {
        return tieHandle.tieCall("READ", buffer, length, offset);
    }

    /**
     * Seeks to a position in a tied filehandle (delegates to SEEK).
     */
    public static RuntimeScalar tiedSeek(TieHandle tieHandle, RuntimeScalar position, RuntimeScalar whence) {
        return tieHandle.tieCall("SEEK", position, whence);
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
    public static RuntimeScalar tiedEof(TieHandle tieHandle) {
        return tieHandle.tieCall("EOF");
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
    public static RuntimeScalar tiedBinmode(TieHandle tieHandle, RuntimeScalar layer) {
        return tieHandle.tieCall("BINMODE", layer);
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
    public static RuntimeScalar tiedDestroy(RuntimeIO runtimeIO) {
        // Get the tied object using the tied() operator
        RuntimeScalar tiedObject = TieOperators.tied(new RuntimeScalar(runtimeIO));
        if (!tiedObject.getDefinedBoolean()) {
            return RuntimeScalarCache.scalarUndef;
        }

        // Get the class name from the tied object
        int blessId = tiedObject.blessedId();
        if (blessId == 0) {
            return RuntimeScalarCache.scalarUndef;
        }
        String perlClassName = NameNormalizer.getBlessStr(blessId);

        // Check if DESTROY method exists in the class hierarchy
        RuntimeScalar destroyMethod = InheritanceResolver.findMethodInHierarchy("DESTROY", perlClassName, null, 0);
        if (destroyMethod == null) {
            // DESTROY method doesn't exist, return undef
            return RuntimeScalarCache.scalarUndef;
        }

        // DESTROY method exists, call it
        RuntimeArray args = new RuntimeArray(tiedObject);
        return RuntimeCode.apply(destroyMethod, args, RuntimeContextType.SCALAR).getFirst();
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
        // Check if UNTIE method exists in the class hierarchy
        String perlClassName = tieHandle.getTiedPackage();
        RuntimeScalar untieMethod = InheritanceResolver.findMethodInHierarchy("UNTIE", perlClassName, null, 0);

        if (untieMethod == null) {
            // UNTIE method doesn't exist, return undef
            return RuntimeScalarCache.scalarUndef;
        }

        // UNTIE method exists, call it
        RuntimeArray args = new RuntimeArray(tieHandle.self);
        return RuntimeCode.apply(untieMethod, args, RuntimeContextType.SCALAR).getFirst();
    }

    // IOHandle interface implementation

    public RuntimeScalar readline() {
        return (RuntimeScalar) tiedReadline(this, RuntimeContextType.SCALAR);
    }

    public RuntimeScalar read(int length) {
        RuntimeScalar buffer = new RuntimeScalar();
        return tiedRead(this, buffer, new RuntimeScalar(length), RuntimeScalarCache.scalarZero);
    }

    @Override
    public RuntimeScalar write(String data) {
        RuntimeList args = new RuntimeList(new RuntimeScalar(data));
        return tiedPrint(this, args);
    }

    @Override
    public RuntimeScalar eof() {
        return tiedEof(this);
    }

    @Override
    public RuntimeScalar tell() {
        return tiedTell(this);
    }

    @Override
    public RuntimeScalar seek(long position) {
        return tiedSeek(this, new RuntimeScalar(position), RuntimeScalarCache.scalarZero);
    }

    @Override
    public RuntimeScalar close() {
        return tiedClose(this);
    }

    @Override
    public RuntimeScalar flush() {
        // Default implementation - can be overridden by FLUSH method if it exists
        return RuntimeScalarCache.scalarTrue;
    }

    @Override
    public void binmode(String layer) {
        tiedBinmode(this, new RuntimeScalar(layer));
    }

    @Override
    public RuntimeScalar fileno() {
        return tiedFileno(this);
    }

    public RuntimeScalar truncate(long length) {
        // Truncate is not a standard tie method, but we can try to call it
        return tieCall("TRUNCATE", new RuntimeScalar(length));
    }

    // Socket methods - may not be implemented by all tied handles

    @Override
    public RuntimeScalar bind(String address, int port) {
        return tieCall("BIND", new RuntimeScalar(address), new RuntimeScalar(port));
    }

    @Override
    public RuntimeScalar connect(String address, int port) {
        return tieCall("CONNECT", new RuntimeScalar(address), new RuntimeScalar(port));
    }

    @Override
    public RuntimeScalar listen(int backlog) {
        return tieCall("LISTEN", new RuntimeScalar(backlog));
    }

    @Override
    public RuntimeScalar accept() {
        return tieCall("ACCEPT");
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
