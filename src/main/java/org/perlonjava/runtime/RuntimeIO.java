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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;

import static org.perlonjava.runtime.GlobalVariable.getGlobalIO;
import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.RuntimeScalarCache.*;

public class RuntimeIO implements RuntimeScalarReference {

    // Mapping of file modes to their corresponding StandardOpenOption sets
    private static final Map<String, Set<StandardOpenOption>> MODE_OPTIONS = new HashMap<>();

    // Standard I/O streams
    public static RuntimeIO stdout = new RuntimeIO(new StandardIO(System.out, true));
    public static RuntimeIO stderr = new RuntimeIO(new StandardIO(System.err, false));
    public static RuntimeIO stdin = new RuntimeIO(new StandardIO(System.in));
    // Static variable to store the last accessed filehandle -  `${^LAST_FH}`
    public static RuntimeIO lastSelectedHandle = stdout;

    // LRU Cache to store open IOHandles
    private static final int MAX_OPEN_HANDLES = 100; // Set the maximum number of open handles
    private static final Map<IOHandle, Boolean> openHandles = new LinkedHashMap<IOHandle, Boolean>(MAX_OPEN_HANDLES, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<IOHandle, Boolean> eldest) {
            if (size() > MAX_OPEN_HANDLES) {
                try {
                    eldest.getKey().flush(); // Flush the eldest handle instead of closing it
                } catch (Exception e) {
                    // Handle exception if needed
                }
                return true;
            }
            return false;
        }
    };

    static {
        // Initialize mode options
        MODE_OPTIONS.put("<", EnumSet.of(StandardOpenOption.READ));
        MODE_OPTIONS.put(">", EnumSet.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));
        MODE_OPTIONS.put(">>", EnumSet.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.APPEND));
        MODE_OPTIONS.put("+<", EnumSet.of(StandardOpenOption.READ, StandardOpenOption.WRITE));
    }

    // Line number counter for the current filehandle - `$.`
    public int currentLineNumber = 0;
    public IOHandle ioHandle;
    public DirectoryIO directoryIO;
    boolean needFlush;

    // Constructor to initialize buffers
    public RuntimeIO() {
    }

    // Constructor for socket, in-memory file
    public RuntimeIO(IOHandle ioHandle) {
        this.ioHandle = ioHandle;
    }

    // Method to set custom OutputStream
    public static void setCustomOutputStream(OutputStream out) {
        // stdout = new RuntimeIO(new CustomOutputStreamHandle(out));
        // lastSelectedHandle = stdout;
        lastSelectedHandle = new RuntimeIO(new CustomOutputStreamHandle(out));
    }

    public static RuntimeScalar handleIOError(String message) {
        getGlobalVariable("main::!").set(message);
        return scalarFalse;
    }

    public static RuntimeScalar handleIOException(IOException e, String message) {
        return handleIOError(message + ": " + e.getMessage());
    }

    public static void initStdHandles() {
        // Initialize STDOUT, STDERR, STDIN
        getGlobalIO("main::STDOUT").set(stdout);
        getGlobalIO("main::STDERR").set(stderr);
        getGlobalIO("main::STDIN").set(stdin);
    }

    // Constructor to open the file, without mode specification
    public static RuntimeIO open(String fileName) {
        RuntimeIO fh = new RuntimeIO();
        if ("-".equals(fileName) || "<-".equals(fileName)) {
            // Handle standard input
            fh.ioHandle = new StandardIO(System.in);
        } else if (">-".equals(fileName)) {
            // Handle standard output
            fh.ioHandle = new CustomOutputStreamHandle(System.out);
        } else {
            return open(fileName, "<");
        }
        return fh;
    }

    // Constructor to open the file with a specific mode
    public static RuntimeIO open(String fileName, String mode) {
        RuntimeIO fh = new RuntimeIO();
        try {
            Path filePath = getPath(fileName);
            Set<StandardOpenOption> options = fh.convertMode(mode);

            // Initialize ioHandle with CustomFileChannel
            fh.ioHandle = new CustomFileChannel(filePath, options);

            // Add the handle to the cache
            addHandle(fh.ioHandle);

            // Truncate the file if mode is '>'
            if (">".equals(mode)) {
                fh.ioHandle.truncate(0);
            }
            if (">>".equals(mode)) {
                RuntimeScalar size = fh.ioHandle.tell();
                fh.ioHandle.seek(size.getLong()); // Move to end for appending
            }
        } catch (IOException e) {
            handleIOException(e, "open failed");
            fh = null;
        }
        return fh;
    }

    public static Path getPath(String fileName) {
        // Construct the full file path relative to the user.dir
        return Paths.get(System.getProperty("user.dir"), fileName);
    }

    public static RuntimeScalar openDir(RuntimeList args) {
        return DirectoryIO.openDir(args);
    }

    public static void flushFileHandles() {
        // Flush stdout and stderr before sleep, in case we are displaying a prompt
        if (stdout.needFlush) {
            stdout.flush();
        }
        if (stderr.needFlush) {
            stderr.flush();
        }
    }

    // Method to add a handle to the cache
    public static void addHandle(IOHandle handle) {
        synchronized (openHandles) {
            openHandles.put(handle, Boolean.TRUE);
        }
    }

    // Method to remove a handle from the cache
    public static void removeHandle(IOHandle handle) {
        synchronized (openHandles) {
            openHandles.remove(handle);
        }
    }

    // Method to flush all open handles
    public static void flushAllHandles() {
        synchronized (openHandles) {
            for (IOHandle handle : openHandles.keySet()) {
                handle.flush();
            }
        }
        flushFileHandles();
    }

    // Method to close all open handles
    public static void closeAllHandles() {
        flushAllHandles();
        synchronized (openHandles) {
            for (IOHandle handle : openHandles.keySet()) {
                try {
                    handle.close();
                } catch (Exception e) {
                    // Handle exception if needed
                }
            }
            openHandles.clear(); // Clear the cache after closing all handles
        }
    }

    private Set<StandardOpenOption> convertMode(String mode) {
        Set<StandardOpenOption> options = MODE_OPTIONS.get(mode);
        if (options == null) {
            throw new PerlCompilerException("Unsupported file mode: " + mode);
        }
        return new HashSet<>(options);
    }

    public String toString() {
        return "GLOB(0x" + this.hashCode() + ")";
    }

    public String toStringRef() {
        String ref = "IO(0x" + this.hashCode() + ")";

        // XXX TODO IO reference can be blessed
        // return (blessId == 0
        //         ? ref
        //         : NameNormalizer.getBlessStr(blessId) + "=" + ref);

        return ref;
    }

    public int getIntRef() {
        return this.hashCode();
    }

    public double getDoubleRef() {
        return this.hashCode();
    }

    public boolean getBooleanRef() {
        return true;
    }

    // Method to read into a byte array
    public int read(byte[] buffer) {
        if (ioHandle != null) {
            return ioHandle.read(buffer).getInt();
        } else {
            throw new PerlCompilerException("No input source available");
        }
    }

    // Method to check for end-of-file (eof equivalent)
    public RuntimeScalar eof() {
        // Update the last accessed filehandle
        lastSelectedHandle = this;

        if (ioHandle != null) {
            return ioHandle.eof();
        }
        return scalarTrue;
    }

    // Method to get the current file pointer position (tell equivalent)
    public RuntimeScalar tell() {
        // Update the last accessed filehandle
        lastSelectedHandle = this;

        if (ioHandle != null) {
            return ioHandle.tell();
        } else {
            handleIOError("Tell operation is not supported for standard streams");
            return getScalarInt(-1);
        }
    }

    // Method to move the file pointer (seek equivalent)
    public RuntimeScalar seek(long pos) {
        // Update the last accessed filehandle
        lastSelectedHandle = this;

        if (ioHandle != null) {
            return ioHandle.seek(pos);
        } else {
            return handleIOError("Seek operation is not supported for standard streams");
        }
    }

    public RuntimeScalar flush() {
        needFlush = false;
        if (ioHandle != null) {
            return ioHandle.flush();
        }
        return scalarTrue;  // Return 1 to indicate success, consistent with other methods
    }

    // Method to close the filehandle
    public RuntimeScalar close() {
        if (ioHandle != null) {
            removeHandle(ioHandle);
            ioHandle.flush();
            return ioHandle.close();
        }
        return scalarTrue;
    }

    // Method to append data to a file
    public RuntimeScalar write(String data) {
        needFlush = true;
        byte[] bytes = data.getBytes();
        if (ioHandle != null) {
            return ioHandle.write(bytes);
        } else {
            return handleIOError("write: No output channel available");
        }
    }

    public RuntimeScalar fileno() {
        if (ioHandle == null) {
            return getScalarInt(-1);
        }
        return ioHandle.fileno();
    }

    // Method to bind a socket
    public RuntimeScalar bind(String address, int port) {
        if (ioHandle == null) {
            return scalarFalse;
        }
        return ioHandle.bind(address, port);
    }

    // Method to connect a socket
    public RuntimeScalar connect(String address, int port) {
        if (this.ioHandle == null) {
            return scalarFalse;
        }
        return this.ioHandle.connect(address, port);
    }

    // Method to listen on a server socket
    public RuntimeScalar listen(int backlog) {
        if (this.ioHandle == null) {
            return scalarFalse;
        }
        return this.ioHandle.listen(backlog);
    }

    // Method to accept a connection on a server socket
    public RuntimeScalar accept() {
        if (this.ioHandle == null) {
            return scalarFalse;
        }
        return this.ioHandle.accept();
    }
}
