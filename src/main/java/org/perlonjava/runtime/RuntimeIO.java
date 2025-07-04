package org.perlonjava.runtime;

/*
    Additional Features

    Handling pipes (e.g., |- or -| modes).
    Handling in-memory file operations with ByteArrayInputStream or ByteArrayOutputStream.
    Implementing modes for read/write (+<, +>) operations.
 */

import org.perlonjava.io.*;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;

import static org.perlonjava.runtime.GlobalVariable.getGlobalIO;
import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarFalse;

/**
 * Represents a Perl-style I/O handle with support for files, directories, and streams.
 *
 * <p>This class provides a unified interface for all I/O operations in PerlOnJava,
 * mimicking Perl's filehandle behavior. It supports:
 * <ul>
 *   <li>File I/O with various modes (read, write, append, read-write)</li>
 *   <li>Standard I/O streams (STDIN, STDOUT, STDERR)</li>
 *   <li>Directory operations</li>
 *   <li>In-memory I/O with scalar references</li>
 *   <li>I/O layers for encoding and line-ending conversions</li>
 *   <li>Perl-compatible special variables (like $. for line numbers)</li>
 * </ul>
 *
 * <p>The class implements {@link RuntimeScalarReference} to allow filehandles
 * to be used as scalar values in Perl code, supporting operations like:
 * <pre>
 * my $fh = *STDOUT;
 * my $ref = \*STDERR;
 * </pre>
 *
 * <p>File modes supported:
 * <ul>
 *   <li>{@code <} - Read mode (default)</li>
 *   <li>{@code >} - Write mode (truncate)</li>
 *   <li>{@code >>} - Append mode</li>
 *   <li>{@code +<} - Read-write mode</li>
 *   <li>{@code +>} - Read-write mode (truncate)</li>
 * </ul>
 *
 * @see IOHandle
 * @see DirectoryIO
 * @see RuntimeScalarReference
 */
public class RuntimeIO implements RuntimeScalarReference {

    /**
     * Mapping of Perl file modes to their corresponding Java NIO StandardOpenOption sets.
     * This allows easy conversion from Perl-style mode strings to Java NIO options.
     */
    private static final Map<String, Set<StandardOpenOption>> MODE_OPTIONS = new HashMap<>();

    /**
     * Maximum number of file handles to keep in the LRU cache.
     * Older handles are flushed (not closed) when this limit is exceeded.
     */
    private static final int MAX_OPEN_HANDLES = 100;

    /**
     * LRU (Least Recently Used) cache for managing open file handles.
     * This helps prevent resource exhaustion by limiting open handles and
     * automatically flushing less recently used ones.
     */
    private static final Map<IOHandle, Boolean> openHandles = new LinkedHashMap<IOHandle, Boolean>(MAX_OPEN_HANDLES, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<IOHandle, Boolean> eldest) {
            if (size() > MAX_OPEN_HANDLES) {
                try {
                    // Flush but don't close the eldest handle
                    eldest.getKey().flush();
                } catch (Exception e) {
                    // Handle exception if needed
                }
                return true;
            }
            return false;
        }
    };

    /** Standard output stream handle (STDOUT) */
    public static RuntimeIO stdout = new RuntimeIO(new StandardIO(System.out, true));

    /** Standard error stream handle (STDERR) */
    public static RuntimeIO stderr = new RuntimeIO(new StandardIO(System.err, false));

    /** Standard input stream handle (STDIN) */
    public static RuntimeIO stdin = new RuntimeIO(new StandardIO(System.in));

    /**
     * The last accessed filehandle, used for Perl's ${^LAST_FH} special variable.
     * Updated whenever a filehandle is used for I/O operations.
     */
    public static RuntimeIO lastAccesseddHandle;

    /**
     * The currently selected filehandle for output operations.
     * Used by print/printf when no filehandle is specified.
     */
    public static RuntimeIO selectedHandle;

    static {
        // Initialize mode options mapping
        MODE_OPTIONS.put("<", EnumSet.of(StandardOpenOption.READ));
        MODE_OPTIONS.put(">", EnumSet.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));
        MODE_OPTIONS.put(">>", EnumSet.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.APPEND));
        MODE_OPTIONS.put("+<", EnumSet.of(StandardOpenOption.READ, StandardOpenOption.WRITE));
    }

    /**
     * Line number counter for the current filehandle, corresponds to Perl's $. variable.
     * Incremented for each line read from this handle.
     */
    public int currentLineNumber = 0;

    /**
     * The underlying I/O handle that performs actual I/O operations.
     * Can be a file, socket, pipe, or custom I/O implementation.
     */
    public IOHandle ioHandle = new ClosedIOHandle(); // Initialize with ClosedIOHandle

    /**
     * Directory handle for directory operations (opendir, readdir, etc.).
     * Mutually exclusive with ioHandle - a RuntimeIO is either a file or directory handle.
     */
    public DirectoryIO directoryIO;

    /**
     * Flag indicating if this handle has unflushed output.
     * Used to determine when automatic flushing is needed.
     */
    boolean needFlush;

    /**
     * Creates a new uninitialized I/O handle.
     * The handle must be opened before use.
     */
    public RuntimeIO() {
    }

    /**
     * Creates a new I/O handle wrapping an existing IOHandle.
     * Used for sockets, pipes, and custom I/O implementations.
     *
     * @param ioHandle the IOHandle implementation to wrap
     */
    public RuntimeIO(IOHandle ioHandle) {
        this.ioHandle = ioHandle;
    }

    /**
     * Creates a new directory handle.
     *
     * @param directoryIO the directory I/O implementation
     */
    public RuntimeIO(DirectoryIO directoryIO) {
        this.directoryIO = directoryIO;
    }

    /**
     * Sets a custom output stream as the last accessed handle.
     * This is primarily used for testing and special output redirection.
     *
     * @param out the OutputStream to wrap
     */
    public static void setCustomOutputStream(OutputStream out) {
        lastAccesseddHandle = new RuntimeIO(new CustomOutputStreamHandle(out));
    }

    /**
     * Handles I/O errors by setting the Perl $! variable.
     *
     * @param message the error message to set in $!
     * @return RuntimeScalar false value for error indication
     */
    public static RuntimeScalar handleIOError(String message) {
        getGlobalVariable("main::!").set(message);
        return scalarFalse;
    }

    /**
     * Handles IOException by extracting the message and setting $!.
     *
     * @param e the IOException that occurred
     * @param message additional context about the operation that failed
     * @return RuntimeScalar false value for error indication
     */
    public static RuntimeScalar handleIOException(IOException e, String message) {
        return handleIOError(message + ": " + e.getMessage());
    }

    /**
     * Initializes the standard I/O handles (STDIN, STDOUT, STDERR).
     * This method should be called during runtime initialization.
     */
    public static void initStdHandles() {
        // Initialize STDOUT, STDERR, STDIN in the main package
        getGlobalIO("main::STDOUT").set(stdout);
        getGlobalIO("main::STDERR").set(stderr);
        getGlobalIO("main::STDIN").set(stdin);
        lastAccesseddHandle = stdout;
        selectedHandle = stdout;
    }

    /**
     * Opens a file for reading with default mode.
     *
     * <p>Special filenames:
     * <ul>
     *   <li>"-" or "<-" - Opens standard input</li>
     *   <li>">-" - Opens standard output</li>
     * </ul>
     *
     * @param fileName the name of the file to open
     * @return RuntimeIO handle for the opened file, or null on error
     */
    public static RuntimeIO open(String fileName) {
        RuntimeIO fh = new RuntimeIO();
        if ("-".equals(fileName) || "<-".equals(fileName)) {
            // Handle standard input
            fh.ioHandle = new StandardIO(System.in);
        } else if (">-".equals(fileName)) {
            // Handle standard output
            fh.ioHandle = new CustomOutputStreamHandle(System.out);
        } else {
            // Default to read mode for regular files
            return open(fileName, "<");
        }
        return fh;
    }

    /**
     * Opens a file with a specific mode and optional I/O layers.
     *
     * <p>The mode string can include I/O layers after a colon:
     * <pre>
     * open("file.txt", ">:utf8")     // Write mode with UTF-8 encoding
     * open("file.txt", "<:crlf")      // Read mode with CRLF conversion
     * open("file.txt", ">>:encoding(UTF-16)") // Append with UTF-16
     * </pre>
     *
     * @param fileName the name of the file to open
     * @param mode the mode string, optionally including I/O layers
     * @return RuntimeIO handle for the opened file, or null on error
     */
    public static RuntimeIO open(String fileName, String mode) {
        RuntimeIO fh = new RuntimeIO();
        try {
            String ioLayers = "";
            // Check if mode contains IO layers (indicated by ':')
            int colonIndex = mode.indexOf(':');
            if (colonIndex != -1) {
                // Extract I/O layers from mode string
                ioLayers = mode.substring(colonIndex);
                mode = mode.substring(0, colonIndex);

                // If empty fileMode, default to read mode
                if (mode.isEmpty()) {
                    mode = "<";
                }
            }

            Path filePath = getPath(fileName);
            Set<StandardOpenOption> options = fh.convertMode(mode);

            // Initialize ioHandle with CustomFileChannel
            fh.ioHandle = new CustomFileChannel(filePath, options);

            // Add the handle to the LRU cache
            addHandle(fh.ioHandle);

            // Truncate the file if mode is '>' (already done by TRUNCATE_EXISTING)
            if (">".equals(mode)) {
                fh.ioHandle.truncate(0);
            }
            // Position at end of file for append mode
            if (">>".equals(mode)) {
                RuntimeScalar size = fh.ioHandle.tell();
                fh.ioHandle.seek(size.getLong()); // Move to end for appending
            }

            // Apply any I/O layers
            fh.binmode(ioLayers);

        } catch (IOException e) {
            handleIOException(e, "open failed");
            fh = null;
        }
        return fh;
    }

    /**
     * Opens an in-memory file backed by a scalar reference.
     *
     * <p>This allows treating a string variable as a file:
     * <pre>
     * my $data = "";
     * open(my $fh, ">", \$data);
     * print $fh "Hello";  # $data now contains "Hello"
     * </pre>
     *
     * @param scalarRef a reference to a scalar that will back the file
     * @param mode the file mode (">", "<", ">>")
     * @return RuntimeIO handle for the scalar-backed file, or null on error
     */
    public static RuntimeIO open(RuntimeScalar scalarRef, String mode) {
        RuntimeIO fh = new RuntimeIO();

        // Check if the argument is a scalar reference
        if (scalarRef.type != RuntimeScalarType.REFERENCE) {
            handleIOError("Not a SCALAR reference");
            return null;
        }

        // Get the referenced scalar
        RuntimeScalar targetScalar = (RuntimeScalar) scalarRef.value;

        // Create ScalarBackedIO
        ScalarBackedIO scalarIO = new ScalarBackedIO(targetScalar);

        // Handle different modes
        if (mode.equals(">")) {
            // Truncate for write mode
            targetScalar.set("");
        } else if (mode.equals(">>")) {
            // Seek to end for append mode
            String content = targetScalar.toString();
            int length = content.getBytes(StandardCharsets.ISO_8859_1).length;
            scalarIO.seek(length);
        }
        // For "<" (read) mode, no special handling needed

        fh.ioHandle = scalarIO;
        addHandle(fh.ioHandle);

        return fh;
    }

    /**
     * Converts a filename to a Path object relative to the current directory.
     *
     * @param fileName the filename to convert
     * @return Path object for the file
     */
    public static Path getPath(String fileName) {
        // Construct the full file path relative to the user.dir
        return Paths.get(System.getProperty("user.dir"), fileName);
    }

    /**
     * Flushes stdout and stderr if they have pending output.
     * This is typically called before operations that might block,
     * ensuring prompts are displayed.
     */
    public static void flushFileHandles() {
        // Flush stdout and stderr before sleep, in case we are displaying a prompt
        if (stdout.needFlush) {
            stdout.flush();
        }
        if (stderr.needFlush) {
            stderr.flush();
        }
    }

    /**
     * Adds a handle to the LRU cache of open handles.
     *
     * @param handle the IOHandle to cache
     */
    public static void addHandle(IOHandle handle) {
        synchronized (openHandles) {
            openHandles.put(handle, Boolean.TRUE);
        }
    }

    /**
     * Removes a handle from the LRU cache.
     *
     * @param handle the IOHandle to remove
     */
    public static void removeHandle(IOHandle handle) {
        synchronized (openHandles) {
            openHandles.remove(handle);
        }
    }

    /**
     * Flushes all open file handles.
     * This ensures all buffered data is written without closing files.
     */
    public static void flushAllHandles() {
        synchronized (openHandles) {
            for (IOHandle handle : openHandles.keySet()) {
                handle.flush();
            }
        }
        flushFileHandles();
    }

    /**
     * Closes all open file handles.
     * This is typically called during program shutdown.
     */
    public static void closeAllHandles() {
        flushAllHandles();
        synchronized (openHandles) {
            for (IOHandle handle : openHandles.keySet()) {
                try {
                    handle.close();
                    handle = new ClosedIOHandle();
                } catch (Exception e) {
                    // Handle exception if needed
                }
            }
            openHandles.clear(); // Clear the cache after closing all handles
        }
    }

    /**
     * Extracts a RuntimeIO from various Perl scalar types.
     *
     * <p>Handles:
     * <ul>
     *   <li>Direct I/O handles</li>
     *   <li>Glob references (\*STDOUT)</li>
     *   <li>Globs (*STDOUT)</li>
     * </ul>
     *
     * @param runtimeScalar the scalar containing or referencing an I/O handle
     * @return the extracted RuntimeIO
     */
    public static RuntimeIO getRuntimeIO(RuntimeScalar runtimeScalar) {
        RuntimeIO fh;
        if (runtimeScalar.type == RuntimeScalarType.GLOBREFERENCE) {
            // Handle: my $fh2 = \*STDOUT;
            String globName = ((RuntimeGlob) runtimeScalar.value).globName;
            fh = (RuntimeIO) getGlobalIO(globName).value;
        } else if (runtimeScalar.type == RuntimeScalarType.GLOB) {
            // Handle: my $fh = *STDOUT;
            if (runtimeScalar.value instanceof RuntimeGlob) {
                String globName = ((RuntimeGlob) runtimeScalar.value).globName;
                fh = (RuntimeIO) getGlobalIO(globName).value;
            } else {
                // Direct I/O handle
                fh = (RuntimeIO) runtimeScalar.value;
            }
        } else {
            // Handle: print STDOUT ...
            fh = (RuntimeIO) runtimeScalar.value;
        }
        return fh;
    }

    /**
     * Sets or changes the I/O layers for this handle.
     *
     * <p>If no layers are specified, checks the ${^OPEN} variable for defaults.
     * On Windows, defaults to :crlf, otherwise :raw.
     * Layers can be stacked, e.g., ":utf8:crlf".
     *
     * @param ioLayer the layer specification (e.g., ":utf8", ":crlf", ":raw")
     */
    public void binmode(String ioLayer) {
        if (ioLayer.isEmpty()) {
            // No layers specified, check ${^OPEN} for default layers
            ioLayer = getGlobalVariable(GlobalContext.OPEN).toString();
        }
        if (ioLayer.isEmpty() || ioLayer.equals(":")) {
            // If only ":" is specified, use OS default
            if (SystemUtils.osIsWindows()) {
                ioLayer = ":crlf";
            } else {
                ioLayer = ":raw";
            }
        }

        // Unwrap all layers to get to the base handle
        IOHandle baseHandle = ioHandle;
        while (baseHandle instanceof LayeredIOHandle) {
            baseHandle = ((LayeredIOHandle) baseHandle).getDelegate();
        }

        // Special handling for :raw mode - just use the base handle directly
        if (ioLayer.equals(":raw") || ioLayer.equals(":bytes") || ioLayer.equals(":unix")) {
            ioHandle = baseHandle;
            return;
        }

        // For other modes, wrap the IOHandle and set the ioLayer
        LayeredIOHandle wrappedHandle = new LayeredIOHandle(baseHandle);
        wrappedHandle.binmode(ioLayer);
        ioHandle = wrappedHandle;
    }

    /**
     * Converts a Perl-style mode string to Java NIO options.
     *
     * @param mode the Perl mode string
     * @return set of StandardOpenOption for the mode
     * @throws PerlCompilerException if the mode is not supported
     */
    private Set<StandardOpenOption> convertMode(String mode) {
        Set<StandardOpenOption> options = MODE_OPTIONS.get(mode);
        if (options == null) {
            throw new PerlCompilerException("Unsupported file mode: " + mode);
        }
        return new HashSet<>(options);
    }

    /**
     * Returns a string representation of this I/O handle.
     * Format: GLOB(0xHASHCODE)
     *
     * @return string representation
     */
    public String toString() {
        return "GLOB(0x" + this.hashCode() + ")";
    }

    /**
     * Returns a string representation of an I/O reference.
     * Format: IO(0xHASHCODE)
     *
     * @return string representation of the reference
     */
    public String toStringRef() {
        // XXX TODO IO reference can be blessed
        // return (blessId == 0
        //         ? ref
        //         : NameNormalizer.getBlessStr(blessId) + "=" + ref);

        return "IO(0x" + this.hashCode() + ")";
    }

    /**
     * Gets the numeric value of this I/O reference.
     *
     * @return the hash code as an integer
     */
    public int getIntRef() {
        return this.hashCode();
    }

    /**
     * Gets the numeric value of this I/O reference as a double.
     *
     * @return the hash code as a double
     */
    public double getDoubleRef() {
        return this.hashCode();
    }

    /**
     * Gets the boolean value of this I/O reference.
     * I/O handles are always true in boolean context.
     *
     * @return true
     */
    public boolean getBooleanRef() {
        return true;
    }

    /**
     * Closes this I/O handle.
     * Removes from cache, flushes buffers, and releases resources.
     *
     * @return RuntimeScalar indicating success/failure
     */
    public RuntimeScalar close() {
        removeHandle(ioHandle);
        ioHandle.flush();
        RuntimeScalar ret = ioHandle.close();
        ioHandle = new ClosedIOHandle();
        return ret;
    }

    /**
     * Checks if this handle has reached end-of-file.
     * Updates the last accessed handle.
     *
     * @return RuntimeScalar with true if at EOF
     */
    public RuntimeScalar eof() {
        lastAccesseddHandle = this;
        return ioHandle.eof();
    }

    /**
     * Gets the current position in the file.
     * Updates the last accessed handle.
     *
     * @return RuntimeScalar with the current position
     */
    public RuntimeScalar tell() {
        lastAccesseddHandle = this;
        return ioHandle.tell();
    }

    /**
     * Seeks to a new position in the file.
     * Updates the last accessed handle.
     *
     * @param pos the position to seek to
     * @return RuntimeScalar indicating success/failure
     */
    public RuntimeScalar seek(long pos) {
        lastAccesseddHandle = this;
        return ioHandle.seek(pos);
    }

    /**
     * Flushes any buffered output.
     * Clears the needFlush flag.
     *
     * @return RuntimeScalar indicating success/failure
     */
    public RuntimeScalar flush() {
        needFlush = false;
        return ioHandle.flush();
    }

    /**
     * Writes data to this handle.
     * Sets the needFlush flag.
     *
     * @param data the string data to write
     * @return RuntimeScalar indicating success/failure or bytes written
     */
    public RuntimeScalar write(String data) {
        needFlush = true;
        return ioHandle.write(data);
    }

    /**
     * Gets the file descriptor number for this handle.
     *
     * @return RuntimeScalar with the file descriptor number, or undef if not available
     */
    public RuntimeScalar fileno() {
        return ioHandle.fileno();
    }

    /**
     * Binds this socket to a local address and port.
     * Only valid for socket handles.
     *
     * @param address the local address to bind to
     * @param port the local port to bind to
     * @return RuntimeScalar indicating success/failure
     */
    public RuntimeScalar bind(String address, int port) {
        return ioHandle.bind(address, port);
    }

    /**
     * Connects this socket to a remote address and port.
     * Only valid for socket handles.
     *
     * @param address the remote address to connect to
     * @param port the remote port to connect to
     * @return RuntimeScalar indicating success/failure
     */
    public RuntimeScalar connect(String address, int port) {
        return ioHandle.connect(address, port);
    }

    /**
     * Configures this socket to listen for incoming connections.
     * Only valid for socket handles.
     *
     * @param backlog the maximum number of pending connections
     * @return RuntimeScalar indicating success/failure
     */
    public RuntimeScalar listen(int backlog) {
        return ioHandle.listen(backlog);
    }

    /**
     * Accepts an incoming connection on this socket.
     * Only valid for socket handles in listening mode.
     *
     * @return RuntimeScalar containing the new socket handle, or false on error
     */
    public RuntimeScalar accept() {
        return ioHandle.accept();
    }
}
