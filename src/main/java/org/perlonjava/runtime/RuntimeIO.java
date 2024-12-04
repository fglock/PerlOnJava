package org.perlonjava.runtime;

/*
    Additional Features

    Handling pipes (e.g., |- or -| modes).
    Handling in-memory file operations with ByteArrayInputStream or ByteArrayOutputStream.
    Implementing modes for read/write (+<, +>) operations.
 */

import org.perlonjava.io.SocketIO;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
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
    public static RuntimeIO stdin = RuntimeIO.open(FileDescriptor.in, false);
    // Static variable to store the last accessed filehandle -  `${^LAST_FH}`
    public static RuntimeIO lastAccessedFileHandle = null;

    static {
        // Initialize mode options
        MODE_OPTIONS.put("<", EnumSet.of(StandardOpenOption.READ));
        MODE_OPTIONS.put(">", EnumSet.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));
        MODE_OPTIONS.put(">>", EnumSet.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.APPEND));
        MODE_OPTIONS.put("+<", EnumSet.of(StandardOpenOption.READ, StandardOpenOption.WRITE));
    }

    // List to keep track of directory stream positions
    private final List<DirectoryStream<Path>> directoryStreamPositions = new ArrayList<>();
    // Line number counter for the current filehandle - `$.`
    public int currentLineNumber = 0;
    boolean needFlush;
    private SocketIO socketIO;
    // Buffers for various I/O operations
    private ByteBuffer buffer = null;
    private ByteBuffer readBuffer = null;
    private ByteBuffer singleCharBuffer = null;
    // Streams and channels for I/O operations
    private InputStream inputStream;
    private OutputStream outputStream;
    private BufferedReader bufferedReader;
    private FileChannel fileChannel;
    private WritableByteChannel channel;
    // Stream for directory operations
    private DirectoryStream<Path> directoryStream;
    private int currentDirPosition = 0;
    private String directoryPath;
    private Iterator<Path> directoryIterator;
    private ArrayList<RuntimeScalar> directorySpecialEntries = new ArrayList<>();
    // State flags
    private boolean isEOF;

    // Constructor to initialize buffers
    public RuntimeIO() {
        this.buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
        this.readBuffer = ByteBuffer.allocate(BUFFER_SIZE);
        this.singleCharBuffer = ByteBuffer.allocate(1);
    }

    // Constructor for directory streams
    public RuntimeIO(DirectoryStream<Path> directoryStream) {
        this.directoryStream = directoryStream;
        this.buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
        this.readBuffer = ByteBuffer.allocate(BUFFER_SIZE);
        this.singleCharBuffer = ByteBuffer.allocate(1);
    }

    // Constructor for socket
    public RuntimeIO(Socket socket) {
        this.socketIO = new SocketIO(socket);
    }

    // Method to set custom OutputStream
    public static void setCustomOutputStream(OutputStream out) {
        stdout.outputStream = new BufferedOutputStream(out, BUFFER_SIZE);
        stdout.channel = Channels.newChannel(stdout.outputStream);
    }

    // Method to set custom ErrorStream
    public static void setCustomErrorStream(OutputStream err) {
        stderr.outputStream = new BufferedOutputStream(err, BUFFER_SIZE);
        stderr.channel = Channels.newChannel(stderr.outputStream);
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
            fh.bufferedReader = new BufferedReader(new InputStreamReader(System.in));
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
            fh.fileChannel = FileChannel.open(filePath, options);

            if (options.contains(StandardOpenOption.READ)) {
                fh.bufferedReader = new BufferedReader(Channels.newReader(fh.fileChannel, StandardCharsets.UTF_8));
            }

            fh.isEOF = false;

            // Truncate the file if mode is '>'
            if (">".equals(mode)) {
                fh.fileChannel.truncate(0);
            }
            if (">>".equals(mode)) {
                fh.fileChannel.position(fh.fileChannel.size()); // Move to end for appending
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
        RuntimeIO fh = new RuntimeIO();
        if (isOutput) {
            if (fd == FileDescriptor.out || fd == FileDescriptor.err) {
                // For standard output and error, we can't use FileChannel
                OutputStream out = (fd == FileDescriptor.out) ? System.out : System.err;
                fh.outputStream = new BufferedOutputStream(out, BUFFER_SIZE);
                fh.channel = Channels.newChannel(fh.outputStream);
            } else {
                // For other output file descriptors, use FileChannel
                fh.fileChannel = new FileOutputStream(fd).getChannel();
            }
        } else {
            // For input, use FileChannel
            fh.fileChannel = new FileInputStream(fd).getChannel();
            fh.bufferedReader = new BufferedReader(Channels.newReader(fh.fileChannel, StandardCharsets.UTF_8));
        }
        fh.isEOF = false;
        return fh;
    }

    public static RuntimeScalar openDir(RuntimeList args) {
        RuntimeScalar dirHandle = (RuntimeScalar) args.elements.get(0);
        String dirPath = args.elements.get(1).toString();

        try {
            // Get the base directory from the user.dir system property
            Path fullDirPath = getPath(dirPath);

            DirectoryStream<Path> stream = Files.newDirectoryStream(fullDirPath);
            RuntimeIO dirIO = new RuntimeIO(stream);
            dirIO.directoryPath = dirPath;
            dirHandle.type = RuntimeScalarType.GLOB;
            dirHandle.value = dirIO;
            return scalarTrue;
        } catch (IOException e) {
            handleIOException(e, "Directory operation failed");
            return scalarFalse;
        }
    }

    public static RuntimeBaseEntity readdir(RuntimeScalar dirHandle, int ctx) {
        if (dirHandle.type != RuntimeScalarType.GLOB) {
            throw new PerlCompilerException("Invalid directory handle");
        }

        RuntimeIO dirIO = (RuntimeIO) dirHandle.value;
        DirectoryStream<Path> stream = dirIO.getDirectoryStream();

        // If the iterator is null, initialize it
        if (dirIO.directoryIterator == null) {
            dirIO.directoryIterator = stream.iterator();
            // Add special directories '.' and '..' only on the first iteration
            dirIO.directorySpecialEntries = new ArrayList<>();
            dirIO.directorySpecialEntries.add(new RuntimeScalar("."));
            dirIO.directorySpecialEntries.add(new RuntimeScalar(".."));
        }

        // Scalar context: return one entry at a time
        if (ctx == RuntimeContextType.SCALAR) {
            // Handle special entries first ('.' and '..')
            if (!dirIO.directorySpecialEntries.isEmpty()) {
                return dirIO.directorySpecialEntries.removeFirst();  // return '.' or '..'
            }

            // Now handle actual directory contents
            if (dirIO.directoryIterator.hasNext()) {
                Path entry = dirIO.directoryIterator.next();
                return new RuntimeScalar(entry.getFileName().toString());
            } else {
                return scalarFalse;  // No more entries
            }
        } else {
            // List context: return all entries at once
            RuntimeList result = new RuntimeList();

            // Add special entries ('.' and '..') first
            result.elements.addAll(dirIO.directorySpecialEntries);
            dirIO.directorySpecialEntries.clear();

            // Add remaining directory contents
            while (dirIO.directoryIterator.hasNext()) {
                Path entry = dirIO.directoryIterator.next();
                result.elements.add(new RuntimeScalar(entry.getFileName().toString()));
            }

            return result;
        }
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
            // Handle as file handle
            RuntimeIO runtimeIO = scalar.getRuntimeIO();
            if (runtimeIO.fileChannel == null) {
                throw new UnsupportedOperationException("No file channel available for truncation");
            }
            try {
                runtimeIO.fileChannel.truncate(length);
                return scalarTrue;
            } catch (IOException e) {
                handleIOException(e, "Truncate operation failed");
                return scalarFalse;
            }
        } else {
            throw new UnsupportedOperationException("Unsupported scalar type for truncate");
        }
    }

    // Method to get the directory stream
    public DirectoryStream<Path> getDirectoryStream() {
        if (directoryStream != null && !directoryStreamPositions.contains(directoryStream)) {
            directoryStreamPositions.add(directoryStream);
        }
        return directoryStream;
    }

    // Method for closing directory streams
    public RuntimeScalar closedir() {
        try {
            if (directoryStream != null) {
                directoryStream.close();
                directoryStream = null;
                return scalarTrue;
            }
            return scalarFalse; // Not a directory handle
        } catch (IOException e) {
            handleIOException(e, "Directory operation failed");
            return scalarFalse;
        }
    }

    // Method to get the current position in the directory stream (telldir equivalent)
    public int telldir() {
        return currentDirPosition;
    }

    // Method to seek to a specific position in the directory stream (seekdir equivalent)
    public void seekdir(int position) {
        if (directoryStream == null) {
            throw new PerlCompilerException("seekdir is not supported for non-directory streams");
        }

        // Reset the directory stream
        try {
            directoryStream.close();
            directoryStream = Files.newDirectoryStream(Paths.get(directoryPath));
            directoryIterator = directoryStream.iterator();
            for (int i = 1; i < position && directoryIterator.hasNext(); i++) {
                directoryIterator.next();
            }
            currentDirPosition = position;
        } catch (IOException e) {
            throw new PerlCompilerException("Directory operation failed: " + e.getMessage());
        }
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
        try {
            if (fileChannel != null) {
                singleCharBuffer.clear();
                int bytesRead = fileChannel.read(singleCharBuffer);
                if (bytesRead == -1) {
                    isEOF = true;
                    return scalarUndef; // End of file
                }
                singleCharBuffer.flip();
                char character = (char) (singleCharBuffer.get() & 0xFF);
                return new RuntimeScalar(Character.toString(character));
            } else if (bufferedReader != null) {
                int result = bufferedReader.read();
                if (result == -1) {
                    isEOF = true;
                    return scalarUndef; // End of file
                }
                return new RuntimeScalar(Character.toString((char) result));
            } else if (inputStream != null) {
                int result = inputStream.read();
                if (result == -1) {
                    isEOF = true;
                    return scalarUndef; // End of file
                }
                return new RuntimeScalar(Character.toString((char) result));
            }
            throw new PerlCompilerException("No input source available");
        } catch (IOException e) {
            handleIOException(e, "Read operation failed");
            return scalarUndef; // Indicating an error
        }
    }

    // Method to read into a byte array
    public int read(byte[] buffer) {
        try {
            if (fileChannel != null) {
                ByteBuffer byteBuffer = ByteBuffer.wrap(buffer);
                int bytesRead = fileChannel.read(byteBuffer);
                if (bytesRead == -1) {
                    isEOF = true;
                }
                return bytesRead;
            } else if (inputStream != null) {
                int bytesRead = inputStream.read(buffer);
                if (bytesRead == -1) {
                    isEOF = true;
                }
                return bytesRead;
            } else {
                throw new PerlCompilerException("No input source available");
            }
        } catch (IOException e) {
            handleIOException(e, "Read operation failed");
        }
        return -1; // Indicating an error or EOF
    }

    public RuntimeScalar readline() {
        try {
            // Update the last accessed filehandle
            lastAccessedFileHandle = this;

            // Flush stdout and stderr before reading, in case we are displaying a prompt
            flushFileHandles();

            // Check if the IO object is set up for reading
            if (fileChannel == null && bufferedReader == null) {
                throw new PerlCompilerException("Readline is not supported for output streams");
            }

            // Get the input record separator (equivalent to Perl's $/)
            String sep = getGlobalVariable("main::/").toString();
            boolean hasSeparator = !sep.isEmpty();
            int separator = hasSeparator ? sep.charAt(0) : '\n';

            StringBuilder line = new StringBuilder();

            // Reading from a file using NIO FileChannel
            if (fileChannel != null) {
                ByteBuffer charBuffer = ByteBuffer.allocate(1);
                while (true) {
                    int bytesRead = fileChannel.read(charBuffer);
                    if (bytesRead == -1) {
                        this.isEOF = true;
                        break;
                    }
                    charBuffer.flip();
                    char c = (char) charBuffer.get();
                    line.append(c);
                    // Break if we've reached the separator (if defined)
                    if (hasSeparator && c == separator) {
                        break;
                    }
                    charBuffer.clear();
                }
            }
            // Reading from a BufferedReader (e.g., for standard input)
            else if (bufferedReader != null) {
                int c;
                while ((c = bufferedReader.read()) != -1) {
                    line.append((char) c);
                    // Break if we've reached the separator (if defined)
                    if (hasSeparator && c == separator) {
                        break;
                    }
                }
                if (c == -1) {
                    this.isEOF = true;
                }
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
        } catch (IOException e) {
            // Set the global error variable ($!) and return undef on error
            handleIOException(e, "Readline operation failed");
            return scalarUndef;
        }
    }

    // Method to check for end-of-file (eof equivalent)
    public RuntimeScalar eof() {
        try {
            // Update the last accessed filehandle
            lastAccessedFileHandle = this;

            if (fileChannel != null) {
                this.isEOF = (fileChannel.position() >= fileChannel.size());
            } else if (bufferedReader != null) {
                this.isEOF = !bufferedReader.ready();
            } else if (inputStream != null) {
                this.isEOF = (inputStream.available() == 0);
            }
            // For output streams, EOF is not applicable
        } catch (IOException e) {
            handleIOException(e, "File operation failed");
        }
        return getScalarBoolean(this.isEOF);
    }

    // Method to get the current file pointer position (tell equivalent)
    public long tell() {
        try {
            // Update the last accessed filehandle
            lastAccessedFileHandle = this;

            if (fileChannel != null) {
                return fileChannel.position();
            } else {
                throw new PerlCompilerException("Tell operation is not supported for standard streams");
            }
        } catch (IOException e) {
            handleIOException(e, "File operation failed");
        }
        // TODO return error (false)
        return 0;
    }

    // Method to move the file pointer (seek equivalent)
    public void seek(long pos) {
        // Update the last accessed filehandle
        lastAccessedFileHandle = this;

        if (fileChannel != null) {
            try {
                fileChannel.position(pos);
                isEOF = false;
            } catch (IOException e) {
                handleIOException(e, "File operation failed");
            }
        } else {
            throw new PerlCompilerException("Seek operation is not supported for standard streams");
        }
    }

    public RuntimeScalar flush() {
        try {
            needFlush = false;
            if (fileChannel != null) {
                fileChannel.force(false);  // Force any updates to the file (false means don't force metadata updates)
            } else if (channel != null && channel instanceof FileChannel) {
                ((FileChannel) channel).force(false);
            } else if (outputStream != null) {
                outputStream.flush();
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
            if (socketIO != null) {
                socketIO.close();
            }

            if (fileChannel != null) {
                fileChannel.force(true);  // Ensure all data is written to the file
                fileChannel.close();
                fileChannel = null;
            }
            if (channel != null) {
                if (channel instanceof FileChannel) {
                    ((FileChannel) channel).force(true);
                }
                channel.close();
                channel = null;
            }
            if (bufferedReader != null) {
                bufferedReader.close();
                bufferedReader = null;
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
            if (channel != null) {
                // For standard output and error streams
                ByteBuffer buf = ByteBuffer.wrap(bytes);
                while (buf.hasRemaining()) {
                    channel.write(buf);
                }
                // // Flush the outputStream if it's available
                // if (outputStream != null) {
                //     outputStream.flush();
                // }
            } else if (fileChannel != null) {
                // For file output using FileChannel
                int totalWritten = 0;
                while (totalWritten < bytes.length) {
                    int bytesWritten = fileChannel.write(ByteBuffer.wrap(bytes, totalWritten, bytes.length - totalWritten));
                    if (bytesWritten == 0) break; // Shouldn't happen, but just in case
                    totalWritten += bytesWritten;
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
            } else if (runtimeIO.fileChannel != null) {
                // FileChannel does not directly expose a file descriptor in Java
                fd = -1; // Placeholder for unsupported operation
            } else if (runtimeIO.socketIO != null) {
                // Get the file descriptor from the Socket
                return socketIO.fileno();
            } else if (runtimeIO.directoryStream != null) {
                // On systems with dirfd support, return the directory file descriptor
                fd = -1; // Return -1 if not supported
            } else if (runtimeIO.inputStream instanceof FileInputStream) {
                // Attempt to get the file descriptor from FileInputStream
                fd = ((FileInputStream) runtimeIO.inputStream).getFD().hashCode();
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
        return this.socketIO.bind(address, port);
    }

    // Method to connect a socket
    public RuntimeScalar connect(String address, int port) {
        if (this.socketIO == null) {
            return scalarFalse;
        }
        return this.socketIO.connect(address, port);
    }

    // Method to listen on a server socket
    public RuntimeScalar listen(int backlog) {
        if (this.socketIO == null) {
            return scalarFalse;
        }
        return this.socketIO.listen(backlog);
    }

    // Method to accept a connection on a server socket
    public RuntimeScalar accept() {
        if (this.socketIO == null) {
            return scalarFalse;
        }
        return this.socketIO.accept();
    }
}

