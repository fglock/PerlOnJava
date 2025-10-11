package org.perlonjava.runtime;

/*
    Additional Features

    Handling pipes (e.g., |- or -| modes).
    Handling in-memory file operations with ByteArrayInputStream or ByteArrayOutputStream.
    Implementing modes for read/write (+<, +>) operations.
 */

import org.perlonjava.io.*;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;

import static org.perlonjava.runtime.GlobalVariable.getGlobalIO;
import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarFalse;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;

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

    /**
     * Standard output stream handle (STDOUT)
     */
    public static RuntimeIO stdout = new RuntimeIO(new StandardIO(System.out, true));

    /**
     * Standard error stream handle (STDERR)
     */
    public static RuntimeIO stderr = new RuntimeIO(new StandardIO(System.err, false));

    /**
     * Standard input stream handle (STDIN)
     */
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
        MODE_OPTIONS.put("+>", EnumSet.of(StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));
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
     * @param e       the IOException that occurred
     * @param message additional context about the operation that failed
     * @return RuntimeScalar false value for error indication
     */
    public static RuntimeScalar handleIOException(Exception e, String message) {
        return handleIOError(message + ": " + e.getMessage());
    }

    /**
     * Initializes the standard I/O handles (STDIN, STDOUT, STDERR).
     * This method should be called during runtime initialization.
     */
    public static void initStdHandles() {
        // Initialize STDOUT, STDERR, STDIN in the main package
        getGlobalIO("main::STDOUT").setIO(stdout);
        getGlobalIO("main::STDERR").setIO(stderr);
        getGlobalIO("main::STDIN").setIO(stdin);
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

        // Trim leading and trailing whitespace
        fileName = fileName.trim();

        // Parse mode from filename for 2-argument open
        String mode = "<";  // default read mode
        String actualFileName = fileName;
        int modeEndIndex = 0;

        // Check for mode at the beginning of the filename
        if (fileName.startsWith(">>")) {
            mode = ">>";
            modeEndIndex = 2;
        } else if (fileName.startsWith(">&")) {
            mode = ">&";
            modeEndIndex = 3;
        } else if (fileName.startsWith(">")) {
            mode = ">";
            modeEndIndex = 1;
        } else if (fileName.startsWith("+>>")) {
            mode = "+>>";
            modeEndIndex = 3;
        } else if (fileName.startsWith("+>")) {
            mode = "+>";
            modeEndIndex = 2;
        } else if (fileName.startsWith("+<&=")) {
            mode = "+<&=";
            modeEndIndex = 4;
        } else if (fileName.startsWith("+<")) {
            mode = "+<";
            modeEndIndex = 2;
        } else if (fileName.startsWith("<")) {
            mode = "<";
            modeEndIndex = 1;
        }

        // Extract filename, skipping any spaces after the mode
        if (modeEndIndex > 0) {
            actualFileName = fileName.substring(modeEndIndex).trim();
        }

        // Handle special filenames
        if ("-".equals(actualFileName) || actualFileName.isEmpty()) {
            if (mode.equals(">") || mode.equals(">>")) {
                // ">-" or just ">" means stdout
                fh.ioHandle = new CustomOutputStreamHandle(System.out);
            } else {
                // "<-" or just "<" means stdin
                fh.ioHandle = new StandardIO(System.in);
            }
            return fh;
        } else {
            // Regular file open with parsed mode
            return open(actualFileName, mode);
        }
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
     * @param mode     the mode string, optionally including I/O layers
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

            Path filePath = resolvePath(fileName);
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
     * @param mode      the file mode (">", "<", ">>")
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

        // Parse I/O layers from mode string
        String ioLayers = "";
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

        // Create ScalarBackedIO
        ScalarBackedIO scalarIO = new ScalarBackedIO(targetScalar);

        // Handle different modes
        if (mode.equals(">")) {
            // Truncate for write mode
            targetScalar.set("");
        } else if (mode.equals(">>")) {
            // Enable append mode - writes always go to the end
            scalarIO.setAppendMode(true);
        }
        // For "<" (read) mode, no special handling needed

        fh.ioHandle = scalarIO;
        addHandle(fh.ioHandle);

        // Apply any I/O layers
        fh.binmode(ioLayers);

        return fh;
    }

    /**
     * Opens a pipe with a specific mode and optional I/O layers.
     *
     * @param runtimeList the mode string, optionally including I/O layers, followed by an external command and optional parameters
     * @return RuntimeIO handle for the opened file, or null on error
     */
    public static RuntimeIO openPipe(RuntimeList runtimeList) {
        RuntimeIO fh = new RuntimeIO();
        try {
            List<String> strings = new ArrayList<>();
            for (RuntimeBase entity : runtimeList.elements) {
                strings.add(entity.toString());
            }
            String arg = strings.getFirst();
            String mode = null;
            String ioLayers = "";

            if (strings.size() > 1) {
                if (arg.startsWith("|-")) {
                    mode = ">";
                    arg = arg.substring(2);
                } else if (arg.startsWith("-|")) {
                    mode = "<";
                    arg = arg.substring(2);
                }

                // Check if mode contains IO layers (indicated by ':')
                int colonIndex = arg.indexOf(':');
                if (colonIndex == 0) {
                    // Extract I/O layers from mode string
                    ioLayers = arg;
                    arg = "";
                }
            } else if (strings.size() == 1) {
                if (arg.startsWith("|")) {
                    mode = ">";
                    arg = arg.substring(1);
                } else if (arg.endsWith("|")) {
                    mode = "<";
                    arg = arg.substring(0, arg.length() - 1);
                }
            }

            if (arg.isEmpty()) {
                strings.removeFirst();
            } else {
                strings.set(0, arg);
            }

            // System.out.println("open pipe: mode=" + mode + " cmd=" + strings + " layers=" + ioLayers);

            if (">".equals(mode)) {
                if (strings.size() == 1) {
                    fh.ioHandle = new PipeOutputChannel(strings.getFirst());
                } else {
                    fh.ioHandle = new PipeOutputChannel(strings);
                }
            } else if ("<".equals(mode)) {
                if (strings.size() == 1) {
                    fh.ioHandle = new PipeInputChannel(strings.getFirst());
                } else {
                    fh.ioHandle = new PipeInputChannel(strings);
                }
            } else {
                handleIOError("open failed: invalid mode for pipe");
                return null;
            }

            // Add the handle to the LRU cache
            addHandle(fh.ioHandle);

            // Apply any I/O layers
            fh.binmode(ioLayers);
        } catch (IOException e) {
            handleIOException(e, "open failed");
            fh = null;
        }
        return fh;
    }

    /**
     * Converts a filename to a Path object relative to the current directory.
     *
     * @param fileName the filename to convert
     * @return Path object for the file
     */
    public static Path resolvePath(String fileName) {
        Path path = Paths.get(fileName);

        // If the path is already absolute, return it as-is
        if (path.isAbsolute()) {
            return path.toAbsolutePath();
        }

        // For relative paths, resolve against current directory
        return Paths.get(System.getProperty("user.dir")).resolve(fileName).toAbsolutePath();
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
        RuntimeIO fh = null;
        // Handle: my $fh2 = \*STDOUT;
        // Handle: my $fh = *STDOUT;

        if (runtimeScalar.isString()) {
            String name = runtimeScalar.toString();
            String packageName = "main";  // XXX TODO: get the current package name
            if (name.equals("STDOUT") || name.equals("STDERR") || name.equals("STDIN")) {
                packageName = "main";
            }

            // Normalize the name to include the package qualifier
            // This converts "HANDLE" to "Package::HANDLE" format
            name = NameNormalizer.normalizeVariableName(name, packageName);
            runtimeScalar = GlobalVariable.getGlobalIO(name);
        }

        if (runtimeScalar.value instanceof RuntimeGlob runtimeGlob) {
            fh = (RuntimeIO) runtimeGlob.getIO().value;
        } else if (runtimeScalar.value instanceof RuntimeIO runtimeIO) {
            // Direct I/O handle
            fh = runtimeIO;
        }

        if (fh == null) {
            // Check if object is eligible for overloading `*{}`
            int blessId = RuntimeScalarType.blessedId(runtimeScalar);
            if (blessId != 0) {
                // Prepare overload context and check if object is eligible for overloading
                OverloadContext ctx = OverloadContext.prepare(blessId);
                if (ctx != null) {
                    // Try overload method
                    RuntimeScalar result = ctx.tryOverload("(*{}", new RuntimeArray(runtimeScalar));
                    // If the subroutine returns the object itself then it will not be called again
                    if (result != null && result.value.hashCode() != runtimeScalar.value.hashCode()) {
                        return getRuntimeIO(result);
                    }
                }
            }
        }

        return fh;
    }

    /**
     * Helper method to convert a Path to a File, resolving relative paths first.
     */
    public static File resolveFile(String pathString) {
        return resolvePath(pathString).toFile();
    }

    /**
     * Duplicates a filehandle for <& and >& operations.
     *
     * @param sourceHandle the source filehandle to duplicate
     * @param mode         the duplication mode ("<&" for read, ">&" for write)
     * @return RuntimeIO handle that shares the same underlying resource, or null on error
     */
    public static RuntimeIO duplicateHandle(RuntimeIO sourceHandle, String mode) {
        if (sourceHandle == null || sourceHandle.ioHandle == null) {
            handleIOError("Cannot duplicate invalid filehandle");
            return null;
        }

        // For now, return the original handle directly to avoid sharing issues
        // This is a simplified implementation - in a full implementation,
        // we would create a proper duplicate that shares the underlying file descriptor
        // but has separate buffering and close semantics
        return sourceHandle;
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
        // Handle duplication modes - these are handled separately in open()
        if (mode.equals("<&") || mode.equals(">&") || mode.equals("+<&") ||
                mode.equals("<&=") || mode.equals(">&=") || mode.equals("+<&=")) {
            // Return read-write mode for dup operations
            return new HashSet<>(MODE_OPTIONS.get("+<"));
        }

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
        if (lastAccesseddHandle != this && lastAccesseddHandle.needFlush) {
            // Synchronize terminal output for stdout and stderr
            lastAccesseddHandle.flush();
        }
        lastAccesseddHandle = this;
        RuntimeScalar result = ioHandle.write(data);
        if (data.endsWith("\n")) {
            ioHandle.flush();
        }
        return result;
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
     * @param port    the local port to bind to
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
     * @param port    the remote port to connect to
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

    /**
     * Truncates the file to the specified length.
     * Only valid for file handles.
     *
     * @param length the new length of the file
     * @return RuntimeScalar indicating success/failure
     */
    public RuntimeScalar truncate(long length) {
        return ioHandle.truncate(length);
    }

    /**
     * Gets the local socket address (getsockname equivalent).
     * Only valid for socket handles.
     *
     * @return RuntimeScalar containing packed sockaddr_in structure, or undef if not a socket
     */
    public RuntimeScalar getsockname() {
        if (ioHandle instanceof org.perlonjava.io.SocketIO) {
            return ((org.perlonjava.io.SocketIO) ioHandle).getsockname();
        }
        return scalarUndef;
    }

    /**
     * Gets the remote socket address (getpeername equivalent).
     * Only valid for socket handles.
     *
     * @return RuntimeScalar containing packed sockaddr_in structure, or undef if not a socket
     */
    public RuntimeScalar getpeername() {
        if (ioHandle instanceof org.perlonjava.io.SocketIO) {
            return ((org.perlonjava.io.SocketIO) ioHandle).getpeername();
        }
        return scalarUndef;
    }
}
