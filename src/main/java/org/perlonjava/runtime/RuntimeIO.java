package org.perlonjava.runtime;

/*
    Additional Features

    Handling pipes (e.g., |- or -| modes).
    Handling in-memory file operations with ByteArrayInputStream or ByteArrayOutputStream.
    Implementing modes for read/write (+<, +>) operations.
 */

import org.perlonjava.io.CustomFileChannel;
import org.perlonjava.io.DirectoryIO;
import org.perlonjava.io.IOHandle;
import org.perlonjava.io.StandardIO;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;

import static org.perlonjava.runtime.GlobalVariable.getGlobalIO;
import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.RuntimeScalarCache.*;

public class RuntimeIO implements RuntimeScalarReference {

    // Buffer size for I/O operations
    private static final int BUFFER_SIZE = 8192;
    // Mapping of file modes to their corresponding StandardOpenOption sets
    private static final Map<String, Set<StandardOpenOption>> MODE_OPTIONS = new HashMap<>();

    // Standard I/O streams
    public static RuntimeIO stdout = RuntimeIO.open(FileDescriptor.out, true);
    public static RuntimeScalar lastSelectedHandle = new RuntimeScalar(stdout);
    public static RuntimeIO stderr = RuntimeIO.open(FileDescriptor.err, true);
    public static RuntimeIO stdin = new RuntimeIO(new StandardIO(System.in));
    // Static variable to store the last accessed filehandle -  `${^LAST_FH}`
    public static RuntimeIO lastAccessedFileHandle = null;

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
    boolean needFlush;
    private DirectoryIO directoryIO;

    // Streams and channels for I/O operations
    private OutputStream outputStream;
    private WritableByteChannel channel;
    // State flags
    private boolean isEOF;

    // Constructor to initialize buffers
    public RuntimeIO() {
    }

    // Constructor for socket, in-memory file
    public RuntimeIO(IOHandle ioHandle) {
        this.ioHandle = ioHandle;
    }

    // Method to set custom OutputStream
    public static void setCustomOutputStream(OutputStream out) {
        stdout.outputStream = new BufferedOutputStream(out, BUFFER_SIZE);
        stdout.channel = Channels.newChannel(stdout.outputStream);
    }

    public static void handleIOException(IOException e, String message) {
        getGlobalVariable("main::!").set(message + ": " + e.getMessage());
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
            fh.outputStream = new BufferedOutputStream(System.out, BUFFER_SIZE);
            fh.channel = Channels.newChannel(fh.outputStream);
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

            if (options.contains(StandardOpenOption.READ)) {
                fh.isEOF = false;
            }

            // Truncate the file if mode is '>'
            if (">".equals(mode)) {
                fh.ioHandle.seek(0);
            }
            if (">>".equals(mode)) {
                long size = fh.ioHandle.tell();
                fh.ioHandle.seek(size); // Move to end for appending
            }
        } catch (IOException e) {
            handleIOException(e, "File operation failed");
            fh = null;
        }
        return fh;
    }

    public static Path getPath(String fileName) {
        // Get the base directory from the user.dir system property
        String userDir = System.getProperty("user.dir");

        // Construct the full file path relative to the user.dir
        Path filePath = Paths.get(userDir, fileName);
        return filePath;
    }

    // Constructor for standard output and error streams
    public static RuntimeIO open(FileDescriptor fd, boolean isOutput) {
        try {
            RuntimeIO fh = new RuntimeIO();
            if (isOutput) {
                if (fd == FileDescriptor.out || fd == FileDescriptor.err) {
                    // For standard output and error, we can't use FileChannel
                    OutputStream out = (fd == FileDescriptor.out) ? System.out : System.err;
                    fh.outputStream = new BufferedOutputStream(out, BUFFER_SIZE);
                    fh.channel = Channels.newChannel(fh.outputStream);
                } else {
                    // For other output file descriptors, use FileChannel
                    fh.ioHandle = new CustomFileChannel(fd, Collections.singleton(StandardOpenOption.WRITE));
                }
            } else {
                // For input, use FileChannel
                fh.ioHandle = new CustomFileChannel(fd, Collections.singleton(StandardOpenOption.READ));
            }
            fh.isEOF = false;
            return fh;
        } catch (IOException e) {
            handleIOException(e, "open failed");
            return null;
        }
    }

    public static RuntimeScalar openDir(RuntimeList args) {
        return DirectoryIO.openDir(args);
    }

    public static RuntimeScalar readdir(RuntimeScalar dirHandle, int ctx) {
        RuntimeIO runtimeIO = (RuntimeIO) dirHandle.value;
        if (runtimeIO.directoryIO != null) {
            return runtimeIO.directoryIO.readdir(ctx);
        }
        return scalarFalse;
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

    /**
     * Unified truncate method that handles both filename and file handle.
     *
     * @param scalar The RuntimeScalar representing either a filename or a file handle.
     * @param length The length to truncate the file to.
     * @return true if successful, false if an error occurs.
     * @throws UnsupportedOperationException if truncate is not supported.
     */
    public static RuntimeScalar truncate(RuntimeScalar scalar, long length) {
        if (scalar.type == RuntimeScalarType.STRING) {
            // Handle as filename
            String filename = scalar.toString();
            Path filePath = Paths.get(filename);
            try (FileChannel channel1 = FileChannel.open(filePath, StandardOpenOption.WRITE)) {
                channel1.truncate(length);
                return scalarTrue;
            } catch (IOException e) {
                handleIOException(e, "Truncate operation failed");
                return scalarFalse;
            }
        } else if (scalar.type == RuntimeScalarType.GLOB || scalar.type == RuntimeScalarType.GLOBREFERENCE) {
            // File handle
            RuntimeIO runtimeIO = scalar.getRuntimeIO();
            if (runtimeIO.ioHandle != null) {
                return runtimeIO.ioHandle.truncate(length);
            } else {
                throw new UnsupportedOperationException("No file handle available for truncation");
            }
        } else {
            throw new UnsupportedOperationException("Unsupported scalar type for truncate");
        }
    }

    // Method for closing directory streams
    public RuntimeScalar closedir() {
        if (directoryIO != null) {
            directoryIO.closedir();
            directoryIO = null;
            return scalarTrue;
        }
        return scalarFalse; // Not a directory handle
    }

    // Method to get the current position in the directory stream (telldir equivalent)
    public int telldir() {
        if (directoryIO == null) {
            throw new PerlCompilerException("telldir is not supported for non-directory streams");
        }
        return directoryIO.telldir();
    }

    // Method to seek to a specific position in the directory stream (seekdir equivalent)
    public void seekdir(int position) {
        if (directoryIO == null) {
            throw new PerlCompilerException("seekdir is not supported for non-directory streams");
        }
        directoryIO.seekdir(position);
    }

    // Method to rewind the directory stream to the beginning (rewinddir equivalent)
    public void rewinddir() {
        seekdir(1);
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

    // Method to read a single character (getc equivalent)
    public RuntimeScalar getc() {
        if (ioHandle != null) {
            return ioHandle.getc();
        }
        throw new PerlCompilerException("No input source available");
    }

    // Method to read into a byte array
    public int read(byte[] buffer) {
        if (ioHandle != null) {
            return ioHandle.read(buffer).getInt();
        } else {
            throw new PerlCompilerException("No input source available");
        }
    }

    public RuntimeScalar readline() {
        // Update the last accessed filehandle
        lastAccessedFileHandle = this;

        // Flush stdout and stderr before reading, in case we are displaying a prompt
        flushFileHandles();

        // Check if the IO object is set up for reading
        if (ioHandle == null) {
            throw new PerlCompilerException("Readline is not supported for output streams");
        }

        // Get the input record separator (equivalent to Perl's $/)
        String sep = getGlobalVariable("main::/").toString();
        boolean hasSeparator = !sep.isEmpty();
        int separator = hasSeparator ? sep.charAt(0) : '\n';

        StringBuilder line = new StringBuilder();
        byte[] buffer = new byte[1]; // Buffer to read one byte at a time

        int bytesRead;
        while ((bytesRead = ioHandle.read(buffer).getInt()) != -1) {
            char c = (char) buffer[0];
            line.append(c);
            // Break if we've reached the separator (if defined)
            if (hasSeparator && c == separator) {
                break;
            }
        }

        if (bytesRead == -1) {
            this.isEOF = true;
        }

        // Increment the line number counter if a line was read
        if (!line.isEmpty()) {
            currentLineNumber++;
        }

        // Return undef if we've reached EOF and no characters were read
        if (line.isEmpty() && this.isEOF) {
            return scalarUndef;
        }

        // Return the read line as a RuntimeScalar
        return new RuntimeScalar(line.toString());
    }


    // Method to check for end-of-file (eof equivalent)
    public RuntimeScalar eof() {
        // Update the last accessed filehandle
        lastAccessedFileHandle = this;

        if (ioHandle != null) {
            return ioHandle.eof();
        }
        // For output streams, EOF is not applicable
        return getScalarBoolean(this.isEOF);
    }

    // Method to get the current file pointer position (tell equivalent)
    public long tell() {
        // Update the last accessed filehandle
        lastAccessedFileHandle = this;

        if (ioHandle != null) {
            return ioHandle.tell();
        } else {
            throw new PerlCompilerException("Tell operation is not supported for standard streams");
        }
    }

    // Method to move the file pointer (seek equivalent)
    public void seek(long pos) {
        // Update the last accessed filehandle
        lastAccessedFileHandle = this;

        if (ioHandle != null) {
            ioHandle.seek(pos);
            isEOF = false;
        } else {
            throw new PerlCompilerException("Seek operation is not supported for standard streams");
        }
    }

    public RuntimeScalar flush() {
        try {
            needFlush = false;
            if (channel != null && channel instanceof FileChannel) {
                ((FileChannel) channel).force(false);
            } else if (outputStream != null) {
                outputStream.flush();
            } else if (ioHandle != null) {
                ioHandle.flush();
            }
            return scalarTrue;  // Return 1 to indicate success, consistent with other methods
        } catch (IOException e) {
            handleIOException(e, "File operation failed");
            return scalarFalse;  // Return undef to indicate failure
        }
    }

    // Method to close the filehandle
    public RuntimeScalar close() {
        try {
            if (ioHandle != null) {
                ioHandle.close();
            }

            if (channel != null) {
                if (channel instanceof FileChannel) {
                    ((FileChannel) channel).force(true);
                }
                channel.close();
                channel = null;
            }
            if (outputStream != null) {
                outputStream.flush();
                outputStream.close();
                outputStream = null;
            }
            return scalarTrue;
        } catch (IOException e) {
            handleIOException(e, "File operation failed");
            return scalarFalse;
        }
    }

    // Method to append data to a file
    public RuntimeScalar write(String data) {
        try {
            needFlush = true;
            byte[] bytes = data.getBytes();
            if (ioHandle != null) {
                return ioHandle.write(bytes);
            } else if (channel != null) {
                // For standard output and error streams
                ByteBuffer buf = ByteBuffer.wrap(bytes);
                while (buf.hasRemaining()) {
                    channel.write(buf);
                }
            } else {
                throw new PerlCompilerException("No output channel available");
            }
            return scalarTrue;
        } catch (IOException e) {
            handleIOException(e, "File operation failed");
            return scalarFalse;
        }
    }

    public RuntimeScalar fileno() {
        RuntimeIO runtimeIO = this;

        try {
            int fd;
            if (runtimeIO == stdin) {
                fd = 0; // File descriptor for STDIN
            } else if (runtimeIO == stdout) {
                fd = 1; // File descriptor for STDOUT
            } else if (runtimeIO == stderr) {
                fd = 2; // File descriptor for STDERR
            } else if (runtimeIO.ioHandle != null) {
                // Get the file descriptor from the Socket
                return ioHandle.fileno();
            } else if (runtimeIO.directoryIO != null) {
                // On systems with dirfd support, return the directory file descriptor
                fd = -1; // Return -1 if not supported
            } else if (runtimeIO.outputStream instanceof FileOutputStream) {
                // Attempt to get the file descriptor from FileOutputStream
                fd = ((FileOutputStream) runtimeIO.outputStream).getFD().hashCode();
            } else {
                fd = -1; // No real file descriptor
            }
            return new RuntimeScalar(fd);
        } catch (IOException e) {
            handleIOException(e, "File operation failed");
            return scalarUndef;
        }
    }

    // Method to bind a socket
    public RuntimeScalar bind(String address, int port) {
        return this.ioHandle.bind(address, port);
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
