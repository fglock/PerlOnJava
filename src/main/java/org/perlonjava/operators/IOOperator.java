package org.perlonjava.operators;

import org.perlonjava.astnode.FormatLine;
import org.perlonjava.astnode.PictureLine;
import org.perlonjava.io.*;
import org.perlonjava.parser.StringParser;
import org.perlonjava.runtime.*;

import java.io.File;
import java.io.IOException;
import java.net.*;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.RuntimeScalarCache.*;

public class IOOperator {
    // Simple socket option storage: key is "socketHashCode:level:optname", value is the option value
    private static final Map<String, Integer> globalSocketOptions = new ConcurrentHashMap<>();

    // File descriptor to RuntimeIO mapping for duplication support
    private static final Map<Integer, RuntimeIO> fileDescriptorMap = new ConcurrentHashMap<>();

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
        
        // If no explicit filehandle was provided (tell with no args),
        // fall back to the last accessed handle like Perl does.
        if (fh == null) {
            RuntimeIO last = RuntimeIO.lastAccesseddHandle;
            if (last != null) {
                return last.tell();
            }
            // Set $! to EBADF (9) and return undef
            GlobalVariable.getGlobalVariable("main::!").set(9);
            return RuntimeScalarCache.scalarUndef;
        }

        if (fh instanceof TieHandle tieHandle) {
            return TieHandle.tiedTell(tieHandle);
        }

        if (fh.ioHandle != null) {
            return fh.ioHandle.tell();
        }
        // Set $! to EBADF (9) and return undef if no underlying handle
        GlobalVariable.getGlobalVariable("main::!").set(9);
        return RuntimeScalarCache.scalarUndef;
    }

    public static RuntimeScalar binmode(RuntimeScalar fileHandle, RuntimeList runtimeList) {
        RuntimeIO fh = fileHandle.getRuntimeIO();
        // Handle undefined or invalid filehandle
        if (fh == null) {
            // Set $! to EBADF (Bad file descriptor) - errno 9
            GlobalVariable.getGlobalVariable("main::!")
                    .set(new RuntimeScalar(9));
            return RuntimeScalarCache.scalarUndef;
        }

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

        // Get the filehandle - this should be an lvalue RuntimeScalar
        // For array/hash elements like $fh0[0], this is the actual lvalue that can be modified
        // We assert it's a RuntimeScalar rather than calling .scalar() which would create a copy
        RuntimeScalar fileHandle = (RuntimeScalar) args[0];
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

            // Check for filehandle duplication modes (<&, >&, +<&, <&=, >&=, +<&=)
            if (mode.equals("<&") || mode.equals(">&") || mode.equals("+<&") ||
                    mode.equals("<&=") || mode.equals(">&=") || mode.equals("+<&=")) {
                // Handle filehandle duplication
                String argStr = secondArg.toString();
                boolean isParsimonious = mode.endsWith("="); // &= modes reuse file descriptor

                // Check if it's a numeric file descriptor
                if (argStr.matches("^\\d+$")) {
                    int fd = Integer.parseInt(argStr);
                    // Handle numeric file descriptor duplication
                    RuntimeIO sourceHandle = findFileHandleByDescriptor(fd);
                    if (sourceHandle != null && sourceHandle.ioHandle != null) {
                        if (isParsimonious) {
                            // &= mode: reuse the same file descriptor (parsimonious)
                            fh = sourceHandle;
                        } else {
                            // & mode: create a new handle that duplicates the original
                            fh = duplicateFileHandle(sourceHandle);
                        }
                    } else {
                        throw new PerlCompilerException("Bad file descriptor: " + fd);
                    }
                }
                // Check if it's a GLOB or GLOBREFERENCE
                else if (secondArg.type == RuntimeScalarType.GLOB || secondArg.type == RuntimeScalarType.GLOBREFERENCE) {
                    try {
                        RuntimeIO sourceHandle = secondArg.getRuntimeIO();
                        if (sourceHandle != null && sourceHandle.ioHandle != null) {
                            if (isParsimonious) {
                                // &= mode: reuse the same file descriptor (parsimonious)
                                fh = sourceHandle;
                            } else {
                                // & mode: create a new handle that duplicates the original
                                fh = duplicateFileHandle(sourceHandle);
                            }
                        } else {
                            throw new PerlCompilerException("Bad filehandle: " + extractFilehandleName(argStr));
                        }
                    } catch (Exception ex) {
                        throw new PerlCompilerException("Bad filehandle: " + extractFilehandleName(argStr));
                    }
                } else {
                    // Handle string filehandle names (like "STDOUT", "STDERR", "STDIN")
                    String handleName = secondArg.toString();
                    if (handleName.equals("STDOUT") || handleName.equals("STDERR") || handleName.equals("STDIN")) {
                        // Convert string to proper filehandle reference
                        RuntimeScalar handleRef = GlobalVariable.getGlobalIO("main::" + handleName);
                        if (handleRef != null && handleRef.value instanceof RuntimeGlob) {
                            RuntimeIO sourceHandle = ((RuntimeGlob) handleRef.value).getIO().getRuntimeIO();
                            if (sourceHandle != null && sourceHandle.ioHandle != null) {
                                if (isParsimonious) {
                                    // &= mode: reuse the same file descriptor (parsimonious)
                                    fh = sourceHandle;
                                } else {
                                    // & mode: create a new handle that duplicates the original
                                    fh = duplicateFileHandle(sourceHandle);
                                }
                            } else {
                                throw new PerlCompilerException("Bad filehandle: " + extractFilehandleName(argStr));
                            }
                        } else {
                            throw new PerlCompilerException("Bad filehandle: " + extractFilehandleName(argStr));
                        }
                    } else {
                        // For other non-GLOB types, provide proper "Bad filehandle" error messages
                        throw new PerlCompilerException("Bad filehandle: " + extractFilehandleName(argStr));
                    }
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
            return scalarUndef;
        }

        // Check if the filehandle already contains a GLOB
        if ((fileHandle.type == RuntimeScalarType.GLOB || fileHandle.type == RuntimeScalarType.GLOBREFERENCE) && fileHandle.value instanceof RuntimeGlob glob) {
            glob.setIO(fh);
        } else {
            // Create a new anonymous GLOB and assign it to the lvalue
            RuntimeScalar newGlob = new RuntimeScalar();
            newGlob.type = RuntimeScalarType.GLOBREFERENCE;
            newGlob.value = new RuntimeGlob(null).setIO(fh);
            // Use set() to modify the lvalue in place
            fileHandle.set(newGlob);
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

        // Handle case where the filehandle is invalid/corrupted
        if (fh == null) {
            // Return false (undef in boolean context) for invalid filehandle
            return new RuntimeScalar();
        }

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

        // Handle undefined or invalid filehandle
        if (fh == null) {
            // Set $! to EBADF (Bad file descriptor) - errno 9
            GlobalVariable.getGlobalVariable("main::!")
                    .set(new RuntimeScalar(9));
            return RuntimeScalarCache.scalarUndef;
        }

        if (fh instanceof TieHandle tieHandle) {
            return TieHandle.tiedEof(tieHandle, new RuntimeList());
        }

        return fh.eof();
    }

    public static RuntimeScalar eof(RuntimeList runtimeList, RuntimeScalar fileHandle) {
        RuntimeIO fh = fileHandle.getRuntimeIO();

        // Handle undefined or invalid filehandle
        if (fh == null) {
            // Set $! to EBADF (Bad file descriptor) - errno 9
            GlobalVariable.getGlobalVariable("main::!")
                    .set(new RuntimeScalar(9));
            return RuntimeScalarCache.scalarUndef;
        }

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
     * <p>
     * This function looks up the format associated with the filehandle name,
     * executes it with the current values of format variables, and writes
     * the formatted output to the filehandle.
     *
     * @param ctx  The runtime context
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
            if (arg.type == RuntimeScalarType.GLOBREFERENCE && arg.value instanceof RuntimeGlob glob) {
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
     * @param args       The arguments to pass to the format
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
     * Implements the formline operator.
     * Formats text according to a format template and appends to $^A.
     *
     * @param ctx  The runtime context
     * @param args The arguments: format template followed by values
     * @return The current value of $^A after appending
     */
    public static RuntimeScalar formline(int ctx, RuntimeBase... args) {
        if (args.length < 1) {
            throw new PerlCompilerException("Not enough arguments for formline");
        }

        // Get the format template
        String formatTemplate = args[0].scalar().toString();

        // For simple cases (like constants in index.t), if there are no format fields,
        // just append the template string directly to $^A
        if (!formatTemplate.contains("@") && !formatTemplate.contains("^")) {
            // Simple case: no format fields, just append the string
            RuntimeScalar accumulator = getGlobalVariable(GlobalContext.encodeSpecialVar("A"));
            String currentValue = accumulator.toString();
            accumulator.set(currentValue + formatTemplate);
            return scalarTrue;
        }

        // Create arguments list for format processing
        RuntimeList formatArgs = new RuntimeList();
        for (int i = 1; i < args.length; i++) {
            formatArgs.add(args[i]);
        }

        // For complex format templates with @ or ^ fields, use RuntimeFormat
        // Note: This is a simplified implementation - full format support would require
        // parsing the format template properly
        try {
            // Create a temporary RuntimeFormat to process the template
            RuntimeFormat tempFormat = new RuntimeFormat("FORMLINE_TEMP", formatTemplate);

            // Parse the format template as picture lines
            List<FormatLine> lines = new ArrayList<>();
            lines.add(new PictureLine(formatTemplate, new ArrayList<>(), formatTemplate, 0));
            tempFormat.setCompiledLines(lines);

            // Execute the format and get the result
            String formattedOutput = tempFormat.execute(formatArgs);

            // Append to $^A
            RuntimeScalar accumulator = getGlobalVariable(GlobalContext.encodeSpecialVar("A"));
            String currentValue = accumulator.toString();
            accumulator.set(currentValue + formattedOutput);

            // Return success (1)
            return scalarTrue;
        } catch (Exception e) {
            throw new PerlCompilerException("formline failed: " + e.getMessage());
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
                // Create ServerSocket using ServerSocketChannel for native socket option support
                // This enables proper IPv4/IPv6 compatibility and Java's native socket options
                ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
                ServerSocket serverSocket = serverSocketChannel.socket();
                SocketIO socketIOHandle = new SocketIO(serverSocket, serverSocketChannel);
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
            // Quick check: if it looks like a text string (contains ':' or '.'), 
            // it's probably not a binary sockaddr_in structure
            if (packedAddress.contains(":") || packedAddress.matches(".*[0-9]+\\.[0-9]+.*")) {
                return null; // This is a text address, not binary sockaddr_in
            }

            byte[] bytes = packedAddress.getBytes(StandardCharsets.ISO_8859_1); // Get raw bytes

            if (bytes.length < 8) {
                return null; // Too short for sockaddr_in
            }

            // Check if first 2 bytes indicate AF_INET (family = 2)
            int family = ((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF);
            if (family != 2) { // AF_INET = 2
                return null; // Not a valid sockaddr_in structure
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
            // The arguments are references to RuntimeGlob objects that already exist
            RuntimeScalar readRef = args[0].scalar();
            RuntimeScalar writeRef = args[1].scalar();

            // Get the actual RuntimeGlob objects from the references
            RuntimeGlob readGlob = (RuntimeGlob) readRef.value;
            RuntimeGlob writeGlob = (RuntimeGlob) writeRef.value;

            // Create connected pipes using Java's PipedInputStream/PipedOutputStream
            java.io.PipedInputStream pipeIn = new java.io.PipedInputStream();
            java.io.PipedOutputStream pipeOut = new java.io.PipedOutputStream(pipeIn);

            // Create IOHandle implementations for the pipe ends
            InternalPipeHandle readerHandle = InternalPipeHandle.createReader(pipeIn);
            InternalPipeHandle writerHandle = InternalPipeHandle.createWriter(pipeOut);

            // Create RuntimeIO objects for the handles
            RuntimeIO readerIO = new RuntimeIO();
            readerIO.ioHandle = readerHandle;

            RuntimeIO writerIO = new RuntimeIO();
            writerIO.ioHandle = writerHandle;

            // Set the IO handles directly on the existing globs
            readGlob.setIO(readerIO);
            writeGlob.setIO(writerIO);

            return scalarTrue;

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

    /**
     * getsockname(SOCKET)
     * Returns the packed sockaddr structure for the local end of the socket.
     */
    public static RuntimeScalar getsockname(int ctx, RuntimeBase... args) {
        if (args.length < 1) {
            getGlobalVariable("main::!").set("Not enough arguments for getsockname");
            return scalarFalse;
        }

        try {
            RuntimeScalar socketHandle = args[0].scalar();
            RuntimeIO socketIO = socketHandle.getRuntimeIO();

            if (socketIO == null) {
                getGlobalVariable("main::!").set("Invalid socket handle for getsockname");
                return scalarFalse;
            }

            // Get the local socket address and pack it into sockaddr_in format
            return socketIO.getsockname();

        } catch (Exception e) {
            getGlobalVariable("main::!").set("getsockname failed: " + e.getMessage());
            return scalarFalse;
        }
    }

    /**
     * getpeername(SOCKET)
     * Returns the packed sockaddr structure for the remote end of the socket.
     */
    public static RuntimeScalar getpeername(int ctx, RuntimeBase... args) {
        if (args.length < 1) {
            getGlobalVariable("main::!").set("Not enough arguments for getpeername");
            return scalarFalse;
        }

        try {
            RuntimeScalar socketHandle = args[0].scalar();
            RuntimeIO socketIO = socketHandle.getRuntimeIO();

            if (socketIO == null) {
                getGlobalVariable("main::!").set("Invalid socket handle for getpeername");
                return scalarFalse;
            }

            // Get the remote socket address and pack it into sockaddr_in format
            return socketIO.getpeername();

        } catch (Exception e) {
            getGlobalVariable("main::!").set("getpeername failed: " + e.getMessage());
            return scalarFalse;
        }
    }

    /**
     * send(SOCKET, MSG, FLAGS [, TO])
     * Sends a message on a socket
     */
    public static RuntimeScalar send(int ctx, RuntimeBase... args) {
        if (args.length < 3) {
            getGlobalVariable("main::!").set("Not enough arguments for send");
            return scalarFalse;
        }

        try {
            RuntimeScalar socketHandle = args[0].scalar();
            String message = args[1].toString();
            int flags = args[2].scalar().getInt();

            RuntimeIO socketIO = socketHandle.getRuntimeIO();
            if (socketIO == null) {
                getGlobalVariable("main::!").set("Invalid socket handle for send");
                return scalarFalse;
            }

            // For now, ignore flags and TO address - implement basic send
            // Send message as string
            RuntimeScalar result = socketIO.write(message);

            if (result != null && !result.equals(scalarFalse)) {
                return new RuntimeScalar(message.length()); // Return number of bytes sent
            } else {
                getGlobalVariable("main::!").set("Send failed");
                return scalarUndef;
            }

        } catch (Exception e) {
            getGlobalVariable("main::!").set("send failed: " + e.getMessage());
            return scalarUndef;
        }
    }

    /**
     * recv(SOCKET, SCALAR, LENGTH [, FLAGS])
     * Receives a message from a socket
     */
    public static RuntimeScalar recv(int ctx, RuntimeBase... args) {
        if (args.length < 3) {
            getGlobalVariable("main::!").set("Not enough arguments for recv");
            return scalarFalse;
        }

        try {
            RuntimeScalar socketHandle = args[0].scalar();
            RuntimeScalar buffer = args[1].scalar();
            int length = args[2].scalar().getInt();
            int flags = args.length > 3 ? args[3].scalar().getInt() : 0;

            RuntimeIO socketIO = socketHandle.getRuntimeIO();
            if (socketIO == null) {
                getGlobalVariable("main::!").set("Invalid socket handle for recv");
                return scalarFalse;
            }

            // Read data from socket
            RuntimeScalar data = socketIO.ioHandle.read(length);
            if (data != null && !data.equals(scalarUndef)) {
                buffer.set(data.toString());
                return new RuntimeScalar(data.toString().length());
            } else {
                getGlobalVariable("main::!").set("Recv failed");
                return scalarUndef;
            }

        } catch (Exception e) {
            getGlobalVariable("main::!").set("recv failed: " + e.getMessage());
            return scalarUndef;
        }
    }

    /**
     * shutdown(SOCKET, HOW)
     * Shuts down a socket connection
     * HOW: 0 = further receives disallowed, 1 = further sends disallowed, 2 = both
     */
    public static RuntimeScalar shutdown(int ctx, RuntimeBase... args) {
        if (args.length < 2) {
            getGlobalVariable("main::!").set("Not enough arguments for shutdown");
            return scalarFalse;
        }

        try {
            RuntimeScalar socketHandle = args[0].scalar();
            int how = args[1].scalar().getInt();

            RuntimeIO socketIO = socketHandle.getRuntimeIO();
            if (socketIO == null) {
                getGlobalVariable("main::!").set("Invalid socket handle for shutdown");
                return scalarFalse;
            }

            // For now, implement basic shutdown by closing the socket
            // In a full implementation, we would handle the different HOW values:
            // 0 = SHUT_RD (shutdown reading), 1 = SHUT_WR (shutdown writing), 2 = SHUT_RDWR (shutdown both)
            if (socketIO.ioHandle instanceof org.perlonjava.io.SocketIO) {
                // For simplicity, just return success - actual socket shutdown would be more complex
                return scalarTrue;
            } else {
                getGlobalVariable("main::!").set("Not a socket handle for shutdown");
                return scalarFalse;
            }

        } catch (Exception e) {
            getGlobalVariable("main::!").set("shutdown failed: " + e.getMessage());
            return scalarFalse;
        }
    }

    /**
     * setsockopt(SOCKET, LEVEL, OPTNAME, OPTVAL)
     * Sets socket options
     */
    public static RuntimeScalar setsockopt(int ctx, RuntimeBase... args) {
        if (args.length < 4) {
            getGlobalVariable("main::!").set("Not enough arguments for setsockopt");
            return scalarFalse;
        }

        try {
            RuntimeScalar socketHandle = args[0].scalar();
            int level = args[1].scalar().getInt();
            int optname = args[2].scalar().getInt();
            RuntimeScalar optvalScalar = args[3].scalar();
            String optval = optvalScalar.toString();

            RuntimeIO socketIO = socketHandle.getRuntimeIO();
            if (socketIO == null) {
                getGlobalVariable("main::!").set("Invalid socket handle for setsockopt");
                return scalarFalse;
            }

            // Handle socket option setting
            if (socketIO.ioHandle instanceof SocketIO socketIOHandle) {

                // Extract the integer value from the optval - handle both integer and string representations
                int optionValue = 0;

                // Use Perl's looksLikeNumber logic to determine how to handle the value
                if (ScalarUtils.looksLikeNumber(optvalScalar)) {
                    // This is a number - get it directly as an integer
                    optionValue = optvalScalar.getInt();
                } else if (optval.length() == 4) {
                    // This might be a packed binary value - check if it contains non-printable characters
                    boolean isPacked = false;
                    for (int i = 0; i < optval.length(); i++) {
                        char c = optval.charAt(i);
                        if (c < 32 || c > 126) { // Non-printable ASCII characters suggest binary data
                            isPacked = true;
                            break;
                        }
                    }

                    if (isPacked) {
                        // Unpack as little-endian integer (packed format)
                        byte[] bytes = optval.getBytes(StandardCharsets.ISO_8859_1);
                        optionValue = (bytes[0] & 0xFF) |
                                ((bytes[1] & 0xFF) << 8) |
                                ((bytes[2] & 0xFF) << 16) |
                                ((bytes[3] & 0xFF) << 24);
                    } else {
                        // Try to parse as string number
                        try {
                            optionValue = Integer.parseInt(optval.trim());
                        } catch (NumberFormatException e) {
                            // If it's not a parseable number, treat non-empty string as 1, empty as 0
                            optionValue = optval.length() > 0 ? 1 : 0;
                        }
                    }
                } else {
                    // Try to parse as string number
                    try {
                        optionValue = Integer.parseInt(optval.trim());
                    } catch (NumberFormatException e) {
                        // If it's not a parseable number, treat non-empty string as 1, empty as 0
                        optionValue = optval.length() > 0 ? 1 : 0;
                    }
                }

                // Use Java's native socket option support via SocketIO
                boolean success = socketIOHandle.setSocketOption(level, optname, optionValue);

                return success ? scalarTrue : scalarFalse;
            } else {
                getGlobalVariable("main::!").set("Not a socket handle for setsockopt");
                return scalarFalse;
            }

        } catch (Exception e) {
            getGlobalVariable("main::!").set("setsockopt failed: " + e.getMessage());
            return scalarFalse;
        }
    }

    /**
     * getsockopt(SOCKET, LEVEL, OPTNAME)
     * Gets socket options
     */
    public static RuntimeScalar getsockopt(int ctx, RuntimeBase... args) {
        if (args.length < 3) {
            getGlobalVariable("main::!").set("Not enough arguments for getsockopt");
            return scalarUndef;
        }

        try {
            RuntimeScalar socketHandle = args[0].scalar();
            int level = args[1].scalar().getInt();
            int optname = args[2].scalar().getInt();

            RuntimeIO socketIO = socketHandle.getRuntimeIO();
            if (socketIO == null) {
                getGlobalVariable("main::!").set("Invalid socket handle for getsockopt");
                return scalarUndef;
            }

            // Handle socket option retrieval
            if (socketIO.ioHandle instanceof SocketIO socketIOHandle) {

                // Use Java's native socket option support via SocketIO
                int optionValue = socketIOHandle.getSocketOption(level, optname);

                // For SO_ERROR (common case), always return 0 (no error)
                if (level == 1 && optname == 4) { // SOL_SOCKET, SO_ERROR
                    optionValue = 0;
                }

                // Pack the option value as a 4-byte integer and return it
                return new RuntimeScalar(pack("i", optionValue));
            } else {
                getGlobalVariable("main::!").set("Not a socket handle for getsockopt");
                return scalarUndef;
            }

        } catch (Exception e) {
            getGlobalVariable("main::!").set("getsockopt failed: " + e.getMessage());
            return scalarUndef;
        }
    }

    /**
     * Helper method to pack an integer as a binary string (simplified version)
     */
    private static String pack(String template, int value) {
        // Simple implementation for "i" template (signed integer)
        if ("i".equals(template)) {
            byte[] bytes = new byte[4];
            bytes[0] = (byte) (value & 0xFF);
            bytes[1] = (byte) ((value >> 8) & 0xFF);
            bytes[2] = (byte) ((value >> 16) & 0xFF);
            bytes[3] = (byte) ((value >> 24) & 0xFF);
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
        return "";
    }

    /**
     * Find a RuntimeIO handle by its file descriptor number.
     * This is a simplified implementation that maps standard file descriptors.
     */
    private static RuntimeIO findFileHandleByDescriptor(int fd) {
        // Check if we have it in our mapping
        RuntimeIO handle = fileDescriptorMap.get(fd);
        if (handle != null) {
            return handle;
        }

        // Handle standard file descriptors
        switch (fd) {
            case 0: // STDIN
                return RuntimeIO.stdin;
            case 1: // STDOUT
                return RuntimeIO.stdout;
            case 2: // STDERR
                return RuntimeIO.stderr;
            default:
                return null; // Unknown file descriptor
        }
    }

    /**
     * Create a duplicate of a RuntimeIO handle.
     * This creates a new RuntimeIO that shares the same underlying IOHandle.
     */
    /**
     * Opens a filehandle by duplicating an existing one (for 2-argument open with dup mode).
     * This handles cases like: open(my $fh, ">&1")
     *
     * @param fileName The file descriptor number or handle name
     * @param mode     The duplication mode (>&, <&, etc.)
     * @return RuntimeIO handle that duplicates the original, or null on error
     */
    public static RuntimeIO openFileHandleDup(String fileName, String mode) {
        boolean isParsimonious = mode.endsWith("="); // &= modes reuse file descriptor

        // Check if it's a numeric file descriptor
        if (fileName.matches("^\\d+$")) {
            int fd = Integer.parseInt(fileName);
            RuntimeIO sourceHandle = findFileHandleByDescriptor(fd);
            if (sourceHandle != null && sourceHandle.ioHandle != null) {
                if (isParsimonious) {
                    return sourceHandle;
                } else {
                    return duplicateFileHandle(sourceHandle);
                }
            } else {
                throw new PerlCompilerException("Bad file descriptor: " + fd);
            }
        } else {
            throw new PerlCompilerException("Unsupported filehandle duplication: " + fileName);
        }
    }

    private static RuntimeIO duplicateFileHandle(RuntimeIO original) {
        if (original == null || original.ioHandle == null) {
            return null;
        }

        // Create a new RuntimeIO that shares the same IOHandle
        RuntimeIO duplicate = new RuntimeIO();
        duplicate.ioHandle = original.ioHandle;
        duplicate.currentLineNumber = original.currentLineNumber;

        return duplicate;
    }

    /**
     * Register a RuntimeIO handle with a file descriptor number for duplication support.
     */
    public static void registerFileDescriptor(int fd, RuntimeIO handle) {
        if (handle != null) {
            fileDescriptorMap.put(fd, handle);
        }
    }

    /**
     * Unregister a file descriptor when the handle is closed.
     */
    public static void unregisterFileDescriptor(int fd) {
        fileDescriptorMap.remove(fd);
    }

    /**
     * Create a pair of connected sockets (socketpair operator)
     * This creates two connected sockets that can communicate with each other
     */
    public static RuntimeScalar socketpair(int ctx, RuntimeBase... args) {
        if (args.length < 5) {
            throw new PerlCompilerException("Not enough arguments for socketpair");
        }

        try {
            // The first two arguments are references to RuntimeGlob objects that already exist
            RuntimeScalar sock1Ref = args[0].scalar();
            RuntimeScalar sock2Ref = args[1].scalar();
            RuntimeBase domain = args[2];
            RuntimeBase type = args[3];
            RuntimeBase protocol = args[4];

            // Get the actual RuntimeGlob objects from the references
            RuntimeGlob glob1 = (RuntimeGlob) sock1Ref.value;
            RuntimeGlob glob2 = (RuntimeGlob) sock2Ref.value;

            // For simplicity, we'll create a local socket pair using ServerSocket and Socket
            // This is similar to how socketpair works on Unix systems

            // Create a server socket on localhost with a random port
            ServerSocket serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            int port = serverSocket.getLocalPort();

            // Create the first socket and connect it to the server
            Socket socket1 = new Socket();
            socket1.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));

            // Accept the connection on the server side to get the second socket
            Socket socket2 = serverSocket.accept();

            // Close the server socket as we no longer need it
            serverSocket.close();

            // Create RuntimeIO objects for both sockets
            RuntimeIO io1 = new RuntimeIO();
            io1.ioHandle = new SocketIO(socket1);

            RuntimeIO io2 = new RuntimeIO();
            io2.ioHandle = new SocketIO(socket2);

            // Set the IO handles directly on the existing globs
            glob1.setIO(io1);
            glob2.setIO(io2);

            return scalarTrue;

        } catch (IOException e) {
            // Set $! to the error message
            getGlobalVariable("main::!").set(e.getMessage());
            return scalarFalse;
        }
    }

}
