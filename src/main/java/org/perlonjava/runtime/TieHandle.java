package org.perlonjava.runtime;

import org.perlonjava.operators.TieOperators;

/**
 * TieHandle provides support for Perl's tie mechanism for filehandle variables.
 *
 * <p>In Perl, the tie mechanism allows filehandle variables to have their operations
 * intercepted and handled by custom classes. When a filehandle is tied, all operations
 * on that filehandle (reading, writing, seeking, etc.) are delegated to methods in
 * the tie handler object.</p>
 *
 * <p>This class provides static methods that are called when operations are
 * performed on tied filehandles.</p>
 *
 * @see RuntimeIO
 */
public class TieHandle extends RuntimeIO {

    /**
     * The tied object (handler) that implements the tie interface methods.
     * This is the blessed object returned by TIEHANDLE.
     */
    private final RuntimeScalar self;

    /**
     * The package name that this handle is tied to.
     * Used for method dispatch and error reporting.
     */
    private final String tiedPackage;

    /**
     * The original value of the handle before it was tied.
     * This value might be needed for untie operations or debugging.
     */
    private final RuntimeIO previousValue;

    /**
     * Creates a new TieHandle instance.
     *
     * @param tiedPackage   the package name this handle is tied to
     * @param previousValue the value of the handle before it was tied (may be null)
     * @param self          the blessed object returned by TIEHANDLE that handles tied operations
     */
    public TieHandle(String tiedPackage, RuntimeIO previousValue, RuntimeScalar self) {
        this.tiedPackage = tiedPackage;
        this.previousValue = previousValue;
        this.self = self;
    }

    /**
     * Helper method to call methods on the tied object.
     *
     * @param method the method name to call
     * @param args   the arguments to pass to the method
     * @return the result of the method call
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
     * Prints data to a tied filehandle.
     *
     * <p>This method is called whenever data is printed to a tied filehandle.
     * It delegates to the PRINT method of the tie handler object.</p>
     *
     * <p>In Perl, this corresponds to operations like:
     * <pre>{@code
     * print $tied_fh "Hello, world!";  # Calls PRINT with "Hello, world!"
     * print $tied_fh @array;           # Calls PRINT with array elements
     * }</pre>
     * </p>
     *
     * @param args the arguments to print
     * @return the value returned by the tie handler's PRINT method
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
     * Prints formatted data to a tied filehandle.
     *
     * <p>This method is called for printf operations on a tied filehandle.
     * It delegates to the PRINTF method of the tie handler object.</p>
     *
     * <p>In Perl, this corresponds to:
     * <pre>{@code
     * printf $tied_fh "Number: %d\n", 42;  # Calls PRINTF with format and args
     * }</pre>
     * </p>
     *
     * @param format the format string
     * @param args   the arguments for the format string
     * @return the value returned by the tie handler's PRINTF method
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
     * Reads a line from a tied filehandle.
     *
     * <p>This method is called for readline operations on a tied filehandle.
     * It delegates to the READLINE method of the tie handler object.</p>
     *
     * <p>In Perl, this corresponds to:
     * <pre>{@code
     * my $line = <$tied_fh>;        # Calls READLINE in scalar context
     * my @lines = <$tied_fh>;       # Calls READLINE in list context
     * }</pre>
     * </p>
     *
     * @param ctx the context (scalar or list)
     * @return the value returned by the tie handler's READLINE method
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
     * Gets a character from a tied filehandle.
     *
     * <p>This method is called for getc operations on a tied filehandle.
     * It delegates to the GETC method of the tie handler object.</p>
     *
     * <p>In Perl, this corresponds to:
     * <pre>{@code
     * my $char = getc($tied_fh);  # Calls GETC
     * }</pre>
     * </p>
     *
     * @return the character returned by the tie handler's GETC method
     */
    public static RuntimeScalar tiedGetc(TieHandle tieHandle) {
        return tieHandle.tieCall("GETC");
    }

    /**
     * Reads data from a tied filehandle.
     *
     * <p>This method is called for read operations on a tied filehandle.
     * It delegates to the READ method of the tie handler object.</p>
     *
     * <p>In Perl, this corresponds to:
     * <pre>{@code
     * read($tied_fh, $buffer, $length);  # Calls READ
     * }</pre>
     * </p>
     *
     * @param buffer the buffer to read into
     * @param length the number of bytes to read
     * @param offset the offset in the buffer
     * @return the number of bytes read
     */
    public static RuntimeScalar tiedRead(TieHandle tieHandle, RuntimeScalar buffer, RuntimeScalar length, RuntimeScalar offset) {
        return tieHandle.tieCall("READ", buffer, length, offset);
    }

    /**
     * Seeks to a position in a tied filehandle.
     *
     * <p>This method is called for seek operations on a tied filehandle.
     * It delegates to the SEEK method of the tie handler object.</p>
     *
     * <p>In Perl, this corresponds to:
     * <pre>{@code
     * seek($tied_fh, $position, $whence);  # Calls SEEK
     * }</pre>
     * </p>
     *
     * @param position the position to seek to
     * @param whence   the seek mode (0=start, 1=current, 2=end)
     * @return the value returned by the tie handler's SEEK method
     */
    public static RuntimeScalar tiedSeek(TieHandle tieHandle, RuntimeScalar position, RuntimeScalar whence) {
        return tieHandle.tieCall("SEEK", position, whence);
    }

    /**
     * Gets the current position in a tied filehandle.
     *
     * <p>This method is called for tell operations on a tied filehandle.
     * It delegates to the TELL method of the tie handler object.</p>
     *
     * <p>In Perl, this corresponds to:
     * <pre>{@code
     * my $pos = tell($tied_fh);  # Calls TELL
     * }</pre>
     * </p>
     *
     * @return the position returned by the tie handler's TELL method
     */
    public static RuntimeScalar tiedTell(TieHandle tieHandle) {
        return tieHandle.tieCall("TELL");
    }

    /**
     * Checks if a tied filehandle is at end-of-file.
     *
     * <p>This method is called for eof operations on a tied filehandle.
     * It delegates to the EOF method of the tie handler object.</p>
     *
     * <p>In Perl, this corresponds to:
     * <pre>{@code
     * if (eof($tied_fh)) { ... }  # Calls EOF
     * }</pre>
     * </p>
     *
     * @return the value returned by the tie handler's EOF method
     */
    public static RuntimeScalar tiedEof(TieHandle tieHandle) {
        return tieHandle.tieCall("EOF");
    }

    /**
     * Closes a tied filehandle.
     *
     * <p>This method is called for close operations on a tied filehandle.
     * It delegates to the CLOSE method of the tie handler object.</p>
     *
     * <p>In Perl, this corresponds to:
     * <pre>{@code
     * close($tied_fh);  # Calls CLOSE
     * }</pre>
     * </p>
     *
     * @return the value returned by the tie handler's CLOSE method
     */
    public static RuntimeScalar tiedClose(TieHandle tieHandle) {
        return tieHandle.tieCall("CLOSE");
    }

    /**
     * Sets binary mode on a tied filehandle.
     *
     * <p>This method is called for binmode operations on a tied filehandle.
     * It delegates to the BINMODE method of the tie handler object.</p>
     *
     * <p>In Perl, this corresponds to:
     * <pre>{@code
     * binmode($tied_fh);           # Calls BINMODE
     * binmode($tied_fh, ":utf8");  # Calls BINMODE with layer
     * }</pre>
     * </p>
     *
     * @param layer the I/O layer to apply (may be empty)
     * @return the value returned by the tie handler's BINMODE method
     */
    public static RuntimeScalar tiedBinmode(TieHandle tieHandle, RuntimeScalar layer) {
        return tieHandle.tieCall("BINMODE", layer);
    }

    /**
     * Gets the file descriptor number of a tied filehandle.
     *
     * <p>This method is called for fileno operations on a tied filehandle.
     * It delegates to the FILENO method of the tie handler object.</p>
     *
     * <p>In Perl, this corresponds to:
     * <pre>{@code
     * my $fd = fileno($tied_fh);  # Calls FILENO
     * }</pre>
     * </p>
     *
     * @return the file descriptor returned by the tie handler's FILENO method
     */
    public static RuntimeScalar tiedFileno(TieHandle tieHandle) {
        return tieHandle.tieCall("FILENO");
    }

    /**
     * Destroys a tied filehandle.
     *
     * <p>This method is called when a tied filehandle goes out of scope or is being
     * garbage collected. It delegates to the DESTROY method of the tie handler
     * object, if such a method exists.</p>
     *
     * @param runtimeIO the tied filehandle being destroyed
     * @return the value returned by the tie handler's DESTROY method, or undef
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

    /**
     * Gets the previous value of the handle before it was tied.
     *
     * @return the previous RuntimeIO value
     */
    public RuntimeIO getPreviousValue() {
        return previousValue;
    }

    /**
     * Gets the tied object (handler) that implements the tie interface methods.
     *
     * @return the self RuntimeScalar
     */
    public RuntimeScalar getSelf() {
        return self;
    }

    /**
     * Gets the package name that this handle is tied to.
     *
     * @return the tied package name
     */
    public String getTiedPackage() {
        return tiedPackage;
    }

    /**
     * Writes data to a tied filehandle.
     *
     * <p>This method is called for write/syswrite operations on a tied filehandle.
     * It delegates to the WRITE method of the tie handler object.</p>
     *
     * <p>In Perl, this corresponds to:
     * <pre>{@code
     * syswrite($tied_fh, $data, $length, $offset);  # Calls WRITE
     * }</pre>
     * </p>
     *
     * @param data   the data to write
     * @param length the number of bytes to write
     * @param offset the offset in the data
     * @return the number of bytes written
     */
    public static RuntimeScalar tiedWrite(TieHandle tieHandle, RuntimeScalar data, RuntimeScalar length, RuntimeScalar offset) {
        return tieHandle.tieCall("WRITE", data, length, offset);
    }

    /**
     * Unties a filehandle by calling the UNTIE method if it exists.
     *
     * <p>This method is called when untie() is called on a tied filehandle.
     * It delegates to the UNTIE method of the tie handler object if it exists.</p>
     *
     * <p>In Perl, this corresponds to:
     * <pre>{@code
     * untie *TIED_FH;  # Calls UNTIE if it exists
     * }</pre>
     * </p>
     *
     * @return the value returned by the tie handler's UNTIE method, or undef
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

    /**
     * Creates a string representation of this tied handle.
     *
     * @return string representation
     */
    @Override
    public String toString() {
        return "TIED_HANDLE(" + tiedPackage + ")";
    }
}