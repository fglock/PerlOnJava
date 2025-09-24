package org.perlonjava.operators;

import org.perlonjava.io.*;
import org.perlonjava.io.ClosedIOHandle;
import org.perlonjava.io.IOHandle;
import org.perlonjava.io.LayeredIOHandle;
import org.perlonjava.io.PipeInputChannel;
import org.perlonjava.io.PipeOutputChannel;
import org.perlonjava.io.ScalarBackedIO;
import org.perlonjava.io.SocketIO;
import org.perlonjava.parser.StringParser;
import org.perlonjava.runtime.*;
import org.perlonjava.runtime.NameNormalizer;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.PerlJavaUnimplementedException;

import java.io.File;
import java.io.IOException;
import java.net.*;
import java.nio.channels.FileChannel;
import java.nio.channels.Pipe;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarFalse;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarTrue;

public class IOOperator {
    public static RuntimeScalar select(RuntimeList runtimeList, int ctx) {
        if (runtimeList.isEmpty()) {
            // select (returns current filehandle)
            return new RuntimeScalar(RuntimeIO.selectedHandle);
        }
        if (runtimeList.size() == 4) {
            // select RBITS,WBITS,EBITS,TIMEOUT (syscall)
            RuntimeScalar rbits = runtimeList.elements.get(0).scalar();
            RuntimeScalar wbits = runtimeList.elements.get(1).scalar();
            RuntimeScalar ebits = runtimeList.elements.get(2).scalar();
            RuntimeScalar timeout = runtimeList.elements.get(3).scalar();

            // Special case: if all bit vectors are undef, just sleep
            if (!rbits.getDefinedBoolean() && !wbits.getDefinedBoolean() && !ebits.getDefinedBoolean()) {
                double sleepTime = timeout.getDouble();
                if (sleepTime > 0) {
                    try {
                        // Convert seconds to milliseconds
                        long millis = (long) (sleepTime * 1000);
                        int nanos = (int) ((sleepTime * 1000 - millis) * 1_000_000);
                        Thread.sleep(millis, nanos);
                    } catch (InterruptedException e) {
                        // Restore interrupted status
                        Thread.currentThread().interrupt();
                        // Return remaining time (we don't track it precisely, so return 0)
                        return new RuntimeScalar(0);
                    }
                }
                // Return 0 to indicate the sleep completed
                return new RuntimeScalar(0);
            }

            // Full select implementation not yet supported
            throw new PerlJavaUnimplementedException("not implemented: select RBITS,WBITS,EBITS,TIMEOUT");
        }
        // select FILEHANDLE (returns/sets current filehandle)
        RuntimeScalar fh = new RuntimeScalar(RuntimeIO.selectedHandle);
        RuntimeIO.selectedHandle = runtimeList.getFirst().getRuntimeIO();
        RuntimeIO.lastAccesseddHandle = RuntimeIO.selectedHandle;
        return fh;
    }

    public static RuntimeScalar seek(RuntimeScalar fileHandle, RuntimeList runtimeList) {
        if (fileHandle.type == RuntimeScalarType.GLOB || fileHandle.type == RuntimeScalarType.GLOBREFERENCE) {
            // File handle
            RuntimeIO runtimeIO = fileHandle.getRuntimeIO();
            if (runtimeIO.ioHandle != null) {
                if (runtimeIO instanceof TieHandle tieHandle) {
                    return TieHandle.tiedSeek(tieHandle, runtimeList);
                }

                long position = runtimeList.getFirst().getLong();
                int whence = IOHandle.SEEK_SET; // Default to SEEK_SET

                // Check if whence parameter is provided
                if (runtimeList.size() > 1) {
                    whence = runtimeList.elements.get(1).scalar().getInt();
                }

                return runtimeIO.ioHandle.seek(position, whence);
            } else {
                return RuntimeIO.handleIOError("No file handle available for seek");
            }
        } else {
            return RuntimeIO.handleIOError("Unsupported scalar type for seek");
        }
    }

    public static RuntimeScalar getc(int ctx, RuntimeBase... args) {
        RuntimeScalar fileHandle;
        if (args.length < 1) {
            fileHandle = new RuntimeScalar("main::STDIN");
        } else {
            fileHandle = args[0].scalar();
        }

        RuntimeIO fh = fileHandle.getRuntimeIO();

        if (fh instanceof TieHandle tieHandle) {
            return TieHandle.tiedGetc(tieHandle);
        }

        if (fh.ioHandle != null) {
            return fh.ioHandle.read(1);
        }
        throw new PerlCompilerException("No input source available");
    }

    public static RuntimeScalar tell(RuntimeScalar fileHandle) {
        RuntimeIO fh = fileHandle.getRuntimeIO();

        if (fh instanceof TieHandle tieHandle) {
            return TieHandle.tiedTell(tieHandle);
        }

        if (fh.ioHandle != null) {
            return fh.ioHandle.tell();
        }
        throw new PerlCompilerException("No input source available");
    }

    public static RuntimeScalar binmode(RuntimeScalar fileHandle, RuntimeList runtimeList) {
        RuntimeIO fh = fileHandle.getRuntimeIO();

        if (fh instanceof TieHandle tieHandle) {
            return TieHandle.tiedBinmode(tieHandle, runtimeList);
        }

        String ioLayer = runtimeList.getFirst().toString();
        if (ioLayer.isEmpty()) {
            ioLayer = ":raw";
        }
        fh.binmode(ioLayer);
        return fileHandle;
    }

    public static RuntimeScalar fileno(int ctx, RuntimeBase... args) {
        RuntimeScalar fileHandle;
        if (args.length < 1) {
            throw new PerlCompilerException("Not enough arguments for fileno");
        } else {
            fileHandle = args[0].scalar();
        }
        
        RuntimeIO fh = fileHandle.getRuntimeIO();

        if (fh instanceof TieHandle tieHandle) {
            return TieHandle.tiedFileno(tieHandle);
        }

        return fh.fileno();
    }


    /**
     * Opens a file and initialize a file handle.
     *
     * @return A RuntimeScalar indicating the result of the open operation.
     * @args file handle, file mode, arg list.
     */
    public static RuntimeScalar open(int ctx, RuntimeBase... args) {
        //public static RuntimeScalar open(RuntimeList runtimeList, RuntimeScalar fileHandle) {
//        open FILEHANDLE,MODE,EXPR
//        open FILEHANDLE,MODE,EXPR,LIST
//        open FILEHANDLE,MODE,REFERENCE
//        open FILEHANDLE,EXPR
//        open FILEHANDLE

        RuntimeScalar fileHandle = args[0].scalar();
        if (args.length < 2) {
            throw new PerlJavaUnimplementedException("1 argument open is not implemented");
        }
        String mode = args[1].toString();
        RuntimeList runtimeList = new RuntimeList(Arrays.copyOfRange(args, 1, args.length));

        RuntimeIO fh;

        if (mode.contains("|")) {
            // Pipe open
            fh = RuntimeIO.openPipe(runtimeList);
        } else if (args.length > 2) {
            // 3-argument open
            RuntimeScalar secondArg = args[2].scalar();

            // Check for filehandle duplication modes (<& and >&)
            if (mode.equals("<&") || mode.equals(">&")) {
                // Handle filehandle duplication
                String argStr = secondArg.toString();
                
                // Check if it's a GLOB or GLOBREFERENCE
                if (secondArg.type == RuntimeScalarType.GLOB || secondArg.type == RuntimeScalarType.GLOBREFERENCE) {
                    try {
                        RuntimeIO sourceHandle = secondArg.getRuntimeIO();
                        if (sourceHandle != null && sourceHandle.ioHandle != null) {
                            // For now, return the same handle (simplified duplication)
                            fh = sourceHandle;
                        } else {
                            throw new PerlCompilerException("Bad filehandle: " + extractFilehandleName(argStr));
                        }
                    } catch (Exception ex) {
                        throw new PerlCompilerException("Bad filehandle: " + extractFilehandleName(argStr));
                    }
                } else {
                    // For non-GLOB types, provide proper "Bad filehandle" error messages
                    throw new PerlCompilerException("Bad filehandle: " + extractFilehandleName(argStr));
                }
            } else if (secondArg.type == RuntimeScalarType.REFERENCE) {
                // Open to in-memory scalar
                fh = RuntimeIO.open(secondArg, mode);
            } else {
                // Regular file open
                String fileName = secondArg.toString();
                fh = RuntimeIO.open(fileName, mode);
            }
        } else {
            // 2-argument open
            fh = RuntimeIO.open(mode);
        }
        if (fh == null) {
            return scalarFalse;
        }
        if ((fileHandle.type == RuntimeScalarType.GLOB || fileHandle.type == RuntimeScalarType.GLOBREFERENCE) && fileHandle.value instanceof RuntimeGlob glob) {
            glob.setIO(fh);
        } else {
            fileHandle.type = RuntimeScalarType.GLOBREFERENCE;
            fileHandle.value = new RuntimeGlob(null).setIO(fh);
        }
        return scalarTrue; // success
    }

    /**
     * Close a file handle.
     *
     * @param args The file handle.
     * @return A RuntimeScalar with the result of the close operation.
     */
    public static RuntimeScalar close(int ctx, RuntimeBase... args) {
        RuntimeScalar handle = args.length == 1 ? ((RuntimeScalar) args[0]) : select(new RuntimeList(), RuntimeContextType.SCALAR);
        RuntimeIO fh = handle.getRuntimeIO();

        if (fh instanceof TieHandle tieHandle) {
            return TieHandle.tiedClose(tieHandle);
        }

        return fh.close();
    }

    /**
     * Prints the elements to the specified file handle according to the format string.
     *
     * @param runtimeList
     * @param fileHandle  The file handle to write to.
     * @return A RuntimeScalar indicating the result of the write operation.
     */
    public static RuntimeScalar printf(RuntimeList runtimeList, RuntimeScalar fileHandle) {
        RuntimeIO fh = fileHandle.getRuntimeIO();

        if (fh instanceof TieHandle tieHandle) {
            return TieHandle.tiedPrintf(tieHandle, runtimeList);
        }

        RuntimeScalar format = (RuntimeScalar) runtimeList.elements.removeFirst(); // Extract the format string from elements

        String formattedString;

        // Use sprintf to get the formatted string
        try {
            formattedString = SprintfOperator.sprintf(format, runtimeList).toString();
        } catch (PerlCompilerException e) {
            // Change sprintf error messages to printf
            String message = e.getMessage();
            if (message != null && message.contains("Integer overflow in format string for sprintf ")) {
                throw new PerlCompilerException("Integer overflow in format string for printf ");
            }
            // Re-throw other exceptions unchanged
            throw e;
        }

        // Write the formatted content to the file handle
        return fh.write(formattedString);
    }

    /**
     * Prints the elements to the specified file handle with a separator and newline.
     *
     * @param runtimeList
     * @param fileHandle  The file handle to write to.
     * @return A RuntimeScalar indicating the result of the write operation.
     */
    public static RuntimeScalar print(RuntimeList runtimeList, RuntimeScalar fileHandle) {
        RuntimeIO fh = fileHandle.getRuntimeIO();

        if (fh instanceof TieHandle tieHandle) {
            return TieHandle.tiedPrint(tieHandle, runtimeList);
        }

        StringBuilder sb = new StringBuilder();
        String separator = getGlobalVariable("main::,").toString(); // fetch $,
        String newline = getGlobalVariable("main::\\").toString();  // fetch $\
        boolean first = true;

        // Iterate through elements and append them with the separator
        for (RuntimeBase element : runtimeList.elements) {
            if (!first) {
                sb.append(separator);
            }
            sb.append(element.toString());
            first = false;
        }

        // Append the newline character
        sb.append(newline);

        try {
            // Write the content to the file handle
            return fh.write(sb.toString());
        } catch (Exception e) {
            getGlobalVariable("main::!").set("File operation failed: " + e.getMessage());
            return scalarFalse;
        }
    }

    /**
     * Prints the elements to the specified file handle with a separator and a newline at the end.
     *
     * @param runtimeList
     * @param fileHandle  The file handle to write to.
     * @return A RuntimeScalar indicating the result of the write operation.
     */
    public static RuntimeScalar say(RuntimeList runtimeList, RuntimeScalar fileHandle) {
        StringBuilder sb = new StringBuilder();
        String separator = getGlobalVariable("main::,").toString(); // fetch $,
        boolean first = true;

        // Iterate through elements and append them with the separator
        for (RuntimeBase element : runtimeList.elements) {
            if (!first) {
                sb.append(separator);
            }
            sb.append(element.toString());
            first = false;
        }

        // Append the newline character
        sb.append("\n");

        try {
            // Write the content to the file handle
            RuntimeIO fh = fileHandle.getRuntimeIO();
            return fh.write(sb.toString());
        } catch (Exception e) {
            getGlobalVariable("main::!").set("File operation failed: " + e.getMessage());
            return scalarFalse;
        }
    }

    /**
     * Reads EOF flag from a file handle.
     *
     * @param fileHandle The file handle.
     * @return A RuntimeScalar with the flag.
     */
    public static RuntimeScalar eof(RuntimeScalar fileHandle) {
        RuntimeIO fh = fileHandle.getRuntimeIO();

        if (fh instanceof TieHandle tieHandle) {
            return TieHandle.tiedEof(tieHandle, new RuntimeList());
        }

        return fh.eof();
    }

    public static RuntimeScalar eof(RuntimeList runtimeList, RuntimeScalar fileHandle) {
        RuntimeIO fh = fileHandle.getRuntimeIO();

        if (fh instanceof TieHandle tieHandle) {
            return TieHandle.tiedEof(tieHandle, runtimeList);
        }

        return fh.eof();
    }

    /**
     * System-level read operation that bypasses PerlIO layers.
     * sysread FILEHANDLE,SCALAR,LENGTH[,OFFSET]
     *
     * @param args Contains FILEHANDLE, TARGET, LENGTH and optional OFFSET
     * @return Number of bytes read, 0 at EOF, or undef on error
     */
    public static RuntimeScalar sysread(int ctx, RuntimeBase... args) {
        if (args.length < 3) {
            throw new PerlCompilerException("Not enough arguments for sysread");
        }

        RuntimeScalar fileHandle = args[0].scalar();

        RuntimeIO fh = fileHandle.getRuntimeIO();

        // Check if fh is null (invalid filehandle)
        if (fh == null) {
            getGlobalVariable("main::!").set("Bad file descriptor");
            WarnDie.warn(
                    new RuntimeScalar("sysread() on unopened filehandle"),
                    new RuntimeScalar("\n")
            );
            return new RuntimeScalar(); // undef
        }

        if (fh instanceof TieHandle) {
            throw new PerlCompilerException("sysread() is not supported on tied handles");
        }

        // Check for closed handle
        if (fh.ioHandle == null || fh.ioHandle instanceof ClosedIOHandle) {
            getGlobalVariable("main::!").set("Bad file descriptor");
            WarnDie.warn(
                    new RuntimeScalar("sysread() on closed filehandle"),
                    new RuntimeScalar("\n")
            );
            return new RuntimeScalar(); // undef
        }

        // Check for :utf8 layer
        if (hasUtf8Layer(fh)) {
            throw new PerlCompilerException("sysread() is not supported on handles with :utf8 layer");
        }

        RuntimeScalar target = args[1].scalar().scalarDeref();
        int length = args[2].scalar().getInt();
        int offset = 0;

        if (args.length > 3) {
            offset = args[3].scalar().getInt();
        }

        // Check for in-memory handles (ScalarBackedIO)
        IOHandle baseHandle = getBaseHandle(fh.ioHandle);

        if (baseHandle instanceof ScalarBackedIO) {
            getGlobalVariable("main::!").set("Invalid argument");
            return new RuntimeScalar(); // undef
        }

        // Try to perform the system read
        RuntimeScalar result;
        try {
            result = baseHandle.sysread(length);
        } catch (Exception e) {
            // e.printStackTrace();
            // This might happen with write-only handles
            getGlobalVariable("main::!").set("Bad file descriptor");
            WarnDie.warn(
                    new RuntimeScalar("Filehandle opened only for output"),
                    new RuntimeScalar("\n")
            );
            return new RuntimeScalar(); // undef
        }

        // Check if the result indicates an error (like from ClosedIOHandle)
        if (!result.getDefinedBoolean()) {
            String errorMsg = getGlobalVariable("main::!").toString();

            if (errorMsg.toLowerCase().contains("closed")) {
                WarnDie.warn(
                        new RuntimeScalar("sysread() on closed filehandle"),
                        new RuntimeScalar("\n")
                );
            } else if (errorMsg.toLowerCase().contains("output") || errorMsg.toLowerCase().contains("write")) {
                WarnDie.warn(
                        new RuntimeScalar("Filehandle opened only for output"),
                        new RuntimeScalar("\n")
                );
            }
            return new RuntimeScalar(); // undef
        }

        String data = result.toString();
        int bytesRead = data.length();

        if (bytesRead == 0) {
            // EOF or zero-byte read
            if (offset == 0) {
                // Clear the buffer when no offset is specified
                target.set("");
            }
            // Otherwise preserve the buffer when using offset
            return new RuntimeScalar(0);
        }

        // Handle offset
        String currentValue = target.toString();
        int currentLength = currentValue.length();

        if (offset < 0) {
            // Negative offset counts from end
            offset = currentLength + offset;
            if (offset < 0) {
                offset = 0;
            }
        }

        // Pad with nulls if needed
        if (offset > currentLength) {
            StringBuilder padded = new StringBuilder(currentValue);
            while (padded.length() < offset) {
                padded.append('\0');
            }
            currentValue = padded.toString();
        }

        // Place the data at the specified offset
        StringBuilder newValue = new StringBuilder();
        if (offset > 0) {
            newValue.append(currentValue, 0, Math.min(offset, currentValue.length()));
        }
        newValue.append(data);

        target.set(newValue.toString());
        return new RuntimeScalar(bytesRead);
    }

    /**
     * System-level write operation that bypasses PerlIO layers.
     * syswrite FILEHANDLE,SCALAR[,LENGTH[,OFFSET]]
     *
     * @param args Contains FILEHANDLE, SCALAR, optional LENGTH and OFFSET
     * @return Number of bytes written, or undef on error
     */
    public static RuntimeScalar syswrite(int ctx, RuntimeBase... args) {
        if (args.length < 2) {
            throw new PerlCompilerException("Not enough arguments for syswrite");
        }

        RuntimeScalar fileHandle = args[0].scalar();
        RuntimeIO fh = fileHandle.getRuntimeIO();

        // Check if fh is null (invalid filehandle)
        if (fh == null || fh.ioHandle == null || fh.ioHandle instanceof ClosedIOHandle) {
            getGlobalVariable("main::!").set("Bad file descriptor");
            WarnDie.warn(
                    new RuntimeScalar("syswrite() on closed filehandle"),
                    new RuntimeScalar("\n")
            );
            return new RuntimeScalar(); // undef
        }

        if (fh instanceof TieHandle) {
            throw new PerlCompilerException("syswrite() is not supported on tied handles");
        }

//        // Check for closed handle - but based on the debug output,
//        // closed handles still have their original ioHandle, not ClosedIOHandle
//        if (fh.ioHandle == null) {
//            getGlobalVariable("main::!").set("Bad file descriptor");
//            WarnDie.warn(
//                    new RuntimeScalar("syswrite() on closed filehandle"),
//                    new RuntimeScalar("\n")
//            );
//            return new RuntimeScalar(); // undef
//        }

        // Check for :utf8 layer
        if (hasUtf8Layer(fh)) {
            throw new PerlCompilerException("syswrite() is not supported on handles with :utf8 layer");
        }

        String data = args[1].scalar().toString();
        int length = data.length();
        int offset = 0;

        // Handle optional LENGTH parameter
        if (args.length > 2) {
            length = args[2].scalar().getInt();
        }

        // Handle optional OFFSET parameter
        if (args.length > 3) {
            offset = args[3].scalar().getInt();
        }

        // Handle negative offset
        if (offset < 0) {
            offset = data.length() + offset;
            if (offset < 0) {
                return RuntimeIO.handleIOError("Offset outside string");
            }
        }

        // Check offset bounds
        if (offset > data.length()) {
            return RuntimeIO.handleIOError("Offset outside string");
        }

        // Calculate actual length to write
        int availableLength = data.length() - offset;
        if (length > availableLength) {
            length = availableLength;
        }

        // Check for characters > 255
        String toWrite = data.substring(offset, offset + length);
        StringParser.assertNoWideCharacters(toWrite, "syswrite");

        // Check for in-memory handles (ScalarBackedIO)
        IOHandle baseHandle = getBaseHandle(fh.ioHandle);
        if (baseHandle instanceof ScalarBackedIO) {
            getGlobalVariable("main::!").set("Invalid argument");
            return new RuntimeScalar(); // undef
        }

        // Try to perform the system write
        RuntimeScalar result;
        try {
            result = baseHandle.syswrite(toWrite);
        } catch (Exception e) {
            // Handle various exceptions
            String exceptionType = e.getClass().getSimpleName();
            String msg = e.getMessage();

            if (e instanceof java.nio.channels.ClosedChannelException ||
                    (msg != null && msg.contains("closed"))) {
                // Closed channel
                getGlobalVariable("main::!").set("Bad file descriptor");
                WarnDie.warn(
                        new RuntimeScalar("syswrite() on closed filehandle"),
                        new RuntimeScalar("\n")
                );
                return new RuntimeScalar(); // undef
            } else if (e instanceof java.nio.channels.NonWritableChannelException ||
                    exceptionType.contains("NonWritableChannel")) {
                // Read-only handle
                getGlobalVariable("main::!").set("Bad file descriptor");
                WarnDie.warn(
                        new RuntimeScalar("Filehandle opened only for input"),
                        new RuntimeScalar("\n")
                );
                return new RuntimeScalar(); // undef
            } else {
                // Other errors
                getGlobalVariable("main::!").set(msg != null ? msg : "I/O error");
                return new RuntimeScalar(); // undef
            }
        }

        // Check if the result indicates an error
        if (!result.getDefinedBoolean()) {
            String errorMsg = getGlobalVariable("main::!").toString().toLowerCase();
            if (errorMsg.contains("closed")) {
                WarnDie.warn(
                        new RuntimeScalar("syswrite() on closed filehandle"),
                        new RuntimeScalar("\n")
                );
            } else if (errorMsg.contains("input") || errorMsg.contains("read")) {
                WarnDie.warn(
                        new RuntimeScalar("Filehandle opened only for input"),
                        new RuntimeScalar("\n")
                );
            }
        }

        return result;
    }

    /**
     * Checks if the handle has a :utf8 layer.
     */
    private static boolean hasUtf8Layer(RuntimeIO fh) {
        IOHandle handle = fh.ioHandle;
        while (handle instanceof LayeredIOHandle layered) {
            String layers = layered.getCurrentLayers();
            if (layers.contains(":utf8") || layers.contains(":encoding")) {
                return true;
            }
            handle = layered.getDelegate();
        }
        return false;
    }

    /**
     * Gets the base handle by unwrapping all layers.
     */
    private static IOHandle getBaseHandle(IOHandle handle) {
        while (handle instanceof LayeredIOHandle layered) {
            handle = layered.getDelegate();
        }
        return handle;
    }

    /**
     * Opens a file using system-level open flags.
     *
     * @param runtimeList The list containing filehandle, filename, mode, and optional perms
     * @return A RuntimeScalar indicating success (1) or failure (0)
     */
    public static RuntimeScalar sysopen(int ctx, RuntimeBase... args) {
        // sysopen FILEHANDLE,FILENAME,MODE
        // sysopen FILEHANDLE,FILENAME,MODE,PERMS

        if (args.length < 3) {
            throw new PerlCompilerException("Not enough arguments for sysopen");
        }

        RuntimeScalar fileHandle = args[0].scalar();
        String fileName = args[1].toString();
        int mode = args[2].scalar().getInt();
        int perms = 0666; // Default permissions (octal)

        if (args.length >= 4) {
            perms = args[3].scalar().getInt();
        }

        // Convert numeric flags to mode string for RuntimeIO
        String modeStr = "";

        // Common flag combinations
        int O_RDONLY = 0;
        int O_WRONLY = 1;
        int O_RDWR = 2;
        int O_CREAT = 0100; // 64 in decimal
        int O_EXCL = 0200;  // 128 in decimal
        int O_APPEND = 02000; // 1024 in decimal
        int O_TRUNC = 01000;  // 512 in decimal

        // Determine the base mode
        int baseMode = mode & 3; // Get the lowest 2 bits

        if (baseMode == O_RDONLY) {
            modeStr = "<";
        } else if (baseMode == O_WRONLY) {
            if ((mode & O_APPEND) != 0) {
                modeStr = ">>";
            } else if ((mode & O_TRUNC) != 0 || (mode & O_CREAT) != 0) {
                modeStr = ">";
            } else {
                modeStr = ">";
            }
        } else if (baseMode == O_RDWR) {
            if ((mode & O_APPEND) != 0) {
                modeStr = "+>>";
            } else {
                modeStr = "+<";
            }
        }

        // If creating a new file, apply the permissions
        if ((mode & O_CREAT) != 0) {
            File file = RuntimeIO.resolveFile(fileName);
            if (!file.exists()) {
                try {
                    file.createNewFile();
                    // Apply permissions to the newly created file
                    applyFilePermissions(file.toPath(), perms);
                } catch (IOException e) {
                    // Failed to create file
                    return scalarFalse;
                }
            }
        }

        RuntimeIO fh = RuntimeIO.open(fileName, modeStr);
        if (fh == null) {
            return scalarFalse;
        }

        fileHandle.type = RuntimeScalarType.GLOBREFERENCE;
        fileHandle.value = new RuntimeGlob(null).setIO(fh);
        return scalarTrue;
    }

    /**
     * Helper method to apply Unix-style permissions to a file.
     * Uses PosixFilePermissions on Unix-like systems, falls back to basic permissions on Windows.
     *
     * @param path The path to the file
     * @param mode The Unix permission mode (octal)
     * @return true if permissions were successfully applied, false otherwise
     */
    public static boolean applyFilePermissions(Path path, int mode) {
        try {
            // Check if POSIX permissions are supported
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                // Use POSIX permissions on Unix-like systems
                Set<PosixFilePermission> perms = new HashSet<>();

                // Owner permissions
                if ((mode & 0400) != 0) perms.add(PosixFilePermission.OWNER_READ);
                if ((mode & 0200) != 0) perms.add(PosixFilePermission.OWNER_WRITE);
                if ((mode & 0100) != 0) perms.add(PosixFilePermission.OWNER_EXECUTE);

                // Group permissions
                if ((mode & 040) != 0) perms.add(PosixFilePermission.GROUP_READ);
                if ((mode & 020) != 0) perms.add(PosixFilePermission.GROUP_WRITE);
                if ((mode & 010) != 0) perms.add(PosixFilePermission.GROUP_EXECUTE);

                // Others permissions
                if ((mode & 04) != 0) perms.add(PosixFilePermission.OTHERS_READ);
                if ((mode & 02) != 0) perms.add(PosixFilePermission.OTHERS_WRITE);
                if ((mode & 01) != 0) perms.add(PosixFilePermission.OTHERS_EXECUTE);

                Files.setPosixFilePermissions(path, perms);
            } else {
                // Fall back to basic permissions on Windows
                File file = path.toFile();

                // Windows only supports read/write permissions, not execute
                boolean ownerRead = (mode & 0400) != 0;
                boolean ownerWrite = (mode & 0200) != 0;
                boolean ownerExecute = (mode & 0100) != 0;

                // On Windows, we can only set owner permissions
                file.setReadable(ownerRead, true);
                file.setWritable(ownerWrite, true);
                file.setExecutable(ownerExecute, true);

                // If any group/other has read permission, make readable by all
                if ((mode & 044) != 0) {
                    file.setReadable(true, false);
                }
                // If any group/other has write permission, make writable by all
                if ((mode & 022) != 0) {
                    file.setWritable(true, false);
                }
                // If any group/other has execute permission, make executable by all
                if ((mode & 011) != 0) {
                    file.setExecutable(true, false);
                }
            }
            return true;
        } catch (IOException | SecurityException e) {
            // Permission denied or other error
            return false;
        }
    }

    /**
     * Executes a Perl format against a filehandle.
     * write FILEHANDLE
     * write
     * 
     * This function looks up the format associated with the filehandle name,
     * executes it with the current values of format variables, and writes
     * the formatted output to the filehandle.
     *
     * @param ctx The runtime context
     * @param args Optional filehandle argument (defaults to currently selected handle)
     * @return A RuntimeScalar indicating success (1) or failure (0)
     */
    public static RuntimeScalar write(int ctx, RuntimeBase... args) {
        String formatName;
        RuntimeIO fh = RuntimeIO.stdout; // Default output handle
        
        if (args.length == 0) {
            // No arguments: write() - use STDOUT format to STDOUT handle
            formatName = "STDOUT";
        } else {
            // One argument: write FORMAT_NAME - use named format to STDOUT handle
            RuntimeScalar arg = args[0].scalar();
            
            // Check if argument is a glob reference (which contains the format name)
            if (arg.type == RuntimeScalarType.GLOBREFERENCE && arg.value instanceof RuntimeGlob) {
                RuntimeGlob glob = (RuntimeGlob) arg.value;
                formatName = glob.globName;
            } else {
                // Check if argument is a filehandle or format name
                RuntimeIO argFh = arg.getRuntimeIO();
                if (argFh != null) {
                    // Argument is a filehandle - determine format name from handle
                    fh = argFh;
                    if (fh == RuntimeIO.stdout) {
                        formatName = "STDOUT";
                    } else if (fh == RuntimeIO.stderr) {
                        formatName = "STDERR";
                    } else if (fh == RuntimeIO.stdin) {
                        formatName = "STDIN";
                    } else {
                        formatName = "STDOUT"; // Default fallback
                    }
                } else {
                    // Argument is a format name string (most common case)
                    formatName = arg.toString();
                    // Normalize the format name
                    formatName = NameNormalizer.normalizeVariableName(formatName, "main");
                }
            }
        }
        
        // Look up the format
        RuntimeFormat format = GlobalVariable.getGlobalFormatRef(formatName);
        
        if (format == null || !format.isFormatDefined()) {
            // Format not found or not defined
            String errorMsg = "Undefined format \"" + formatName + "\" called";
            getGlobalVariable("main::!").set(errorMsg);
            throw new RuntimeException(errorMsg);
        }
        
        try {
            // Execute the format with arguments from current scope
            // For now, we'll pass empty arguments and let the format execution handle variable lookup
            // In a full implementation, this would collect format variables from the current scope
            RuntimeList formatArgs = new RuntimeList();
            
            // TODO: Collect format variables from current scope
            // This would involve scanning for variables referenced in the format's argument lines
            // and collecting their current values from the symbol table
            // For now, the format execution will need to handle variable lookup internally
            
            String formattedOutput = format.execute(formatArgs);
            
            // Write the formatted output to the filehandle
            RuntimeScalar writeResult = fh.write(formattedOutput);
            
            return writeResult;
            
        } catch (Exception e) {
            getGlobalVariable("main::!").set("Format execution failed: " + e.getMessage());
            return scalarFalse;
        }
    }
    
    /**
     * Executes a Perl format with explicit arguments.
     * This is a helper method for testing and advanced format usage.
     *
     * @param formatName The name of the format to execute
     * @param args The arguments to pass to the format
     * @param fileHandle The filehandle to write to
     * @return A RuntimeScalar indicating success (1) or failure (0)
     */
    public static RuntimeScalar writeFormat(String formatName, RuntimeList args, RuntimeScalar fileHandle) {
        RuntimeIO fh = fileHandle.getRuntimeIO();
        
        if (fh == null) {
            getGlobalVariable("main::!").set("Bad file descriptor");
            return scalarFalse;
        }
        
        // Look up the format
        RuntimeFormat format = GlobalVariable.getGlobalFormatRef(formatName);
        
        if (format == null || !format.isFormatDefined()) {
            getGlobalVariable("main::!").set("Undefined format \"" + formatName + "\" called");
            return scalarFalse;
        }
        
        try {
            String formattedOutput = format.execute(args);
            return fh.write(formattedOutput);
        } catch (Exception e) {
            getGlobalVariable("main::!").set("Format execution failed: " + e.getMessage());
            return scalarFalse;
        }
    }

    /**
     * Extracts a clean filehandle name from a string representation.
     * Removes prefixes like "*main::" and GLOB references for cleaner error messages.
     */
    private static String extractFilehandleName(String argStr) {
        if (argStr == null) return "unknown";
        
        // Remove *main:: prefix if present
        if (argStr.startsWith("*main::")) {
            return argStr.substring(7);
        }
        
        // Handle GLOB(0x...) format - extract just the reference part
        if (argStr.startsWith("GLOB(") && argStr.endsWith(")")) {
            return argStr; // Keep the GLOB reference as is for now
        }
        
        return argStr;
    }

    // Socket I/O operators implementation

    /**
     * socket(SOCKET, DOMAIN, TYPE, PROTOCOL)
     * Creates a socket and associates it with SOCKET filehandle.
     */
    public static RuntimeScalar socket(int ctx, RuntimeBase... args) {
        if (args.length < 4) {
            getGlobalVariable("main::!").set("Not enough arguments for socket");
            return scalarFalse;
        }

        try {
            RuntimeScalar socketHandle = args[0].scalar();
            int domain = args[1].scalar().getInt();
            int type = args[2].scalar().getInt();
            int protocol = args[3].scalar().getInt();

            // Map Perl socket constants to Java
            ProtocolFamily family;
            if (domain == 2) { // AF_INET
                family = StandardProtocolFamily.INET;
            } else if (domain == 10) { // AF_INET6
                family = StandardProtocolFamily.INET6;
            } else if (domain == 1) { // AF_UNIX
                family = StandardProtocolFamily.UNIX;
            } else {
                getGlobalVariable("main::!").set("Unsupported socket domain: " + domain);
                return scalarFalse;
            }

            if (type == 1) { // SOCK_STREAM (TCP)
                // Create ServerSocket for Perl socket compatibility
                // This allows the socket to be used for both server operations (bind/listen/accept)
                // and client operations (connect) as needed
                ServerSocket serverSocket = new ServerSocket();
                SocketIO socketIOHandle = new SocketIO(serverSocket);
                RuntimeIO socketIO = new RuntimeIO(socketIOHandle);
                socketHandle.set(socketIO);
                return scalarTrue;
            } else if (type == 2) { // SOCK_DGRAM (UDP)
                // For UDP, we'll use DatagramSocket - note: SocketIO doesn't support UDP yet
                // This is a placeholder implementation
                getGlobalVariable("main::!").set("UDP sockets not yet fully implemented");
                return scalarFalse;
            } else {
                getGlobalVariable("main::!").set("Unsupported socket type: " + type);
                return scalarFalse;
            }

        } catch (Exception e) {
            getGlobalVariable("main::!").set("Socket creation failed: " + e.getMessage());
            return scalarFalse;
        }
    }

    /**
     * Parses a Perl sockaddr_in packed binary address.
     * Format: 2-byte family + 2-byte port + 4-byte IP address + 8 bytes padding
     * 
     * @param packedAddress The packed binary socket address
     * @return An array containing [host, port] or null if parsing fails
     */
    private static String[] parseSockaddrIn(String packedAddress) {
        try {
            byte[] bytes = packedAddress.getBytes("ISO-8859-1"); // Get raw bytes
            
            if (bytes.length < 8) {
                return null; // Too short for sockaddr_in
            }
            
            // Extract port (bytes 2-3, network byte order)
            int port = ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF);
            
            // Extract IP address (bytes 4-7)
            int ip1 = bytes[4] & 0xFF;
            int ip2 = bytes[5] & 0xFF;
            int ip3 = bytes[6] & 0xFF;
            int ip4 = bytes[7] & 0xFF;
            
            String host = ip1 + "." + ip2 + "." + ip3 + "." + ip4;
            
            return new String[]{host, String.valueOf(port)};
            
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * bind(SOCKET, NAME)
     * Binds a socket to an address.
     */
    public static RuntimeScalar bind(int ctx, RuntimeBase... args) {
        if (args.length < 2) {
            getGlobalVariable("main::!").set("Not enough arguments for bind");
            return scalarFalse;
        }

        try {
            RuntimeScalar socketHandle = args[0].scalar();
            RuntimeScalar address = args[1].scalar();
            
            RuntimeIO socketIO = socketHandle.getRuntimeIO();
            if (socketIO == null) {
                getGlobalVariable("main::!").set("Invalid socket handle for bind");
                return scalarFalse;
            }

            // Parse Perl-style packed socket address (sockaddr_in format)
            String addressStr = address.toString();
            String[] parts = parseSockaddrIn(addressStr);
            
            // Fallback to "host:port" string format if binary parsing fails
            if (parts == null) {
                parts = addressStr.split(":");
                if (parts.length != 2) {
                    getGlobalVariable("main::!").set("Invalid address format for bind (expected sockaddr_in or host:port)");
                    return scalarFalse;
                }
            }
            
            String host = parts[0];
            int port;
            try {
                port = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                getGlobalVariable("main::!").set("Invalid port number for bind");
                return scalarFalse;
            }
            
            // Delegate to RuntimeIO's bind method
            return socketIO.bind(host, port);

        } catch (Exception e) {
            getGlobalVariable("main::!").set("Bind failed: " + e.getMessage());
            return scalarFalse;
        }
    }

    /**
     * connect(SOCKET, NAME)
     * Connects a socket to an address.
     */
    public static RuntimeScalar connect(int ctx, RuntimeBase... args) {
        if (args.length < 2) {
            getGlobalVariable("main::!").set("Not enough arguments for connect");
            return scalarFalse;
        }

        try {
            RuntimeScalar socketHandle = args[0].scalar();
            RuntimeScalar address = args[1].scalar();
            
            RuntimeIO socketIO = socketHandle.getRuntimeIO();
            if (socketIO == null) {
                getGlobalVariable("main::!").set("Invalid socket handle for connect");
                return scalarFalse;
            }

            // Parse Perl-style packed socket address (sockaddr_in format)
            String addressStr = address.toString();
            String[] parts = parseSockaddrIn(addressStr);
            
            // Fallback to "host:port" string format if binary parsing fails
            if (parts == null) {
                parts = addressStr.split(":");
                if (parts.length != 2) {
                    getGlobalVariable("main::!").set("Invalid address format for connect (expected sockaddr_in or host:port)");
                    return scalarFalse;
                }
            }
            
            String host = parts[0];
            int port;
            try {
                port = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                getGlobalVariable("main::!").set("Invalid port number for connect");
                return scalarFalse;
            }
            
            // Delegate to RuntimeIO's connect method
            return socketIO.connect(host, port);

        } catch (Exception e) {
            getGlobalVariable("main::!").set("Connect failed: " + e.getMessage());
            return scalarFalse;
        }
    }

    /**
     * listen(SOCKET, QUEUESIZE)
     * Puts a socket into listening mode.
     */
    public static RuntimeScalar listen(int ctx, RuntimeBase... args) {
        if (args.length < 2) {
            getGlobalVariable("main::!").set("Not enough arguments for listen");
            return scalarFalse;
        }

        try {
            RuntimeScalar socketHandle = args[0].scalar();
            int queueSize = args[1].scalar().getInt();
            
            RuntimeIO socketIO = socketHandle.getRuntimeIO();
            if (socketIO == null) {
                getGlobalVariable("main::!").set("Invalid socket handle for listen");
                return scalarFalse;
            }

            // Delegate to RuntimeIO's listen method
            return socketIO.listen(queueSize);

        } catch (Exception e) {
            getGlobalVariable("main::!").set("Listen failed: " + e.getMessage());
            return scalarFalse;
        }
    }

    /**
     * accept(NEWSOCKET, GENERICSOCKET)
     * Accepts a connection on a listening socket.
     */
    public static RuntimeScalar accept(int ctx, RuntimeBase... args) {
        if (args.length < 2) {
            getGlobalVariable("main::!").set("Not enough arguments for accept");
            return scalarFalse;
        }

        try {
            RuntimeScalar newSocketHandle = args[0].scalar();
            RuntimeScalar listenSocketHandle = args[1].scalar();
            
            RuntimeIO listenSocketIO = listenSocketHandle.getRuntimeIO();
            if (listenSocketIO == null) {
                getGlobalVariable("main::!").set("Invalid listening socket handle for accept");
                return scalarFalse;
            }

            // Accept connection and create new socket handle
            RuntimeScalar acceptResult = listenSocketIO.accept();
            if (acceptResult.getDefinedBoolean()) {
                // The accept() method in SocketIO returns the remote address string
                // We need to create a new socket handle for the accepted connection
                // For now, this is a simplified implementation
                getGlobalVariable("main::!").set("Accept operation needs full socket handle creation");
                return scalarFalse;
            } else {
                return scalarFalse;
            }

        } catch (Exception e) {
            getGlobalVariable("main::!").set("Accept failed: " + e.getMessage());
            return scalarFalse;
        }
    }

    /**
     * pipe(READHANDLE, WRITEHANDLE)
     * Creates a pair of connected pipes.
     */
    public static RuntimeScalar pipe(int ctx, RuntimeBase... args) {
        if (args.length < 2) {
            getGlobalVariable("main::!").set("Not enough arguments for pipe");
            return scalarFalse;
        }

        try {
            RuntimeScalar readHandle = args[0].scalar();
            RuntimeScalar writeHandle = args[1].scalar();

            // For now, use a simple implementation with PipedInputStream/PipedOutputStream
            // This creates an internal pipe for communication between threads
            java.io.PipedInputStream pipeIn = new java.io.PipedInputStream();
            java.io.PipedOutputStream pipeOut = new java.io.PipedOutputStream(pipeIn);
            
            // Create simple IOHandle implementations for the pipe ends
            // Note: This is a simplified implementation - in a full implementation,
            // we would create proper IOHandle classes for pipes
            getGlobalVariable("main::!").set("Internal pipes not yet fully implemented");
            return scalarFalse;

        } catch (Exception e) {
            getGlobalVariable("main::!").set("Pipe creation failed: " + e.getMessage());
            return scalarFalse;
        }
    }

    /**
     * truncate(FILEHANDLE, LENGTH) or truncate(EXPR, LENGTH)
     * Updated to use the new API signature pattern.
     */
    public static RuntimeScalar truncate(int ctx, RuntimeBase... args) {
        if (args.length < 2) {
            getGlobalVariable("main::!").set("Not enough arguments for truncate");
            return scalarFalse;
        }

        try {
            RuntimeBase firstArg = args[0];
            long length = args[1].scalar().getLong();

            // Check if first argument is a filehandle or a filename
            if (firstArg.scalar().getRuntimeIO() != null) {
                // First argument is a filehandle
                RuntimeIO fh = firstArg.scalar().getRuntimeIO();
                return fh.truncate(length);
            } else {
                // First argument is a filename
                String filename = firstArg.scalar().toString();
                try {
                    Path path = Path.of(filename);
                    FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE);
                    channel.truncate(length);
                    channel.close();
                    return scalarTrue;
                } catch (IOException e) {
                    getGlobalVariable("main::!").set("Truncate failed: " + e.getMessage());
                    return scalarFalse;
                }
            }

        } catch (Exception e) {
            getGlobalVariable("main::!").set("Truncate failed: " + e.getMessage());
            return scalarFalse;
        }
    }
}
