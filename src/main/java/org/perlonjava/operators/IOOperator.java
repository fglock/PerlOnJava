package org.perlonjava.operators;

import org.perlonjava.io.ClosedIOHandle;
import org.perlonjava.io.IOHandle;
import org.perlonjava.io.LayeredIOHandle;
import org.perlonjava.io.ScalarBackedIO;
import org.perlonjava.runtime.*;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;

import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.RuntimeScalarCache.*;

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
            throw new PerlCompilerException("not implemented: select RBITS,WBITS,EBITS,TIMEOUT");
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

    public static RuntimeScalar getc(RuntimeScalar fileHandle) {
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
        fh.binmode(ioLayer);
        return fileHandle;
    }

    public static RuntimeScalar fileno(RuntimeScalar fileHandle) {
        RuntimeIO fh = fileHandle.getRuntimeIO();

        if (fh instanceof TieHandle tieHandle) {
            return TieHandle.tiedFileno(tieHandle);
        }

        return fh.fileno();
    }

    public static RuntimeScalar truncate(RuntimeScalar fileHandle, RuntimeList runtimeList) {
        long length = runtimeList.getFirst().getLong();
        if (fileHandle.type == RuntimeScalarType.STRING) {
            // Handle as filename
            String filename = fileHandle.toString();
            Path filePath = Paths.get(filename);
            try (FileChannel channel1 = FileChannel.open(filePath, StandardOpenOption.WRITE)) {
                channel1.truncate(length);
                return scalarTrue;
            } catch (IOException e) {
                return RuntimeIO.handleIOException(e, "truncate failed");
            }
        } else if (fileHandle.type == RuntimeScalarType.GLOB || fileHandle.type == RuntimeScalarType.GLOBREFERENCE) {
            // File handle
            RuntimeIO runtimeIO = fileHandle.getRuntimeIO();
            if (runtimeIO.ioHandle != null) {
                return runtimeIO.ioHandle.truncate(length);
            } else {
                return RuntimeIO.handleIOError("No file handle available for truncate");
            }
        } else {
            return RuntimeIO.handleIOError("Unsupported scalar type for truncate");
        }
    }

    /**
     * Opens a file and initialize a file handle.
     *
     * @param runtimeList
     * @param fileHandle  The file handle.
     * @return A RuntimeScalar indicating the result of the open operation.
     */
    public static RuntimeScalar open(RuntimeList runtimeList, RuntimeScalar fileHandle) {
//        open FILEHANDLE,MODE,EXPR
//        open FILEHANDLE,MODE,EXPR,LIST
//        open FILEHANDLE,MODE,REFERENCE
//        open FILEHANDLE,EXPR
//        open FILEHANDLE

        RuntimeIO fh;
        String mode = runtimeList.getFirst().toString();

        if (mode.contains("|")) {
            // Pipe open
            fh = RuntimeIO.openPipe(runtimeList);
        } else if (runtimeList.size() > 1) {
            // 3-argument open
            RuntimeScalar secondArg = runtimeList.elements.get(1).scalar();

            // Check if the second argument is a scalar reference (for in-memory operations)
            if (secondArg.type == RuntimeScalarType.REFERENCE) {
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
        fileHandle.type = RuntimeScalarType.GLOBREFERENCE;
        fileHandle.value = new RuntimeGlob(null).setIO(fh);
        return scalarTrue; // success
    }

    /**
     * Close a file handle.
     *
     * @param fileHandle The file handle.
     * @return A RuntimeScalar with the result of the close operation.
     */
    public static RuntimeScalar close(RuntimeScalar fileHandle) {
        RuntimeIO fh = fileHandle.getRuntimeIO();

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

        // Use sprintf to get the formatted string
        String formattedString = SprintfOperator.sprintf(format, runtimeList).toString();

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
    public static RuntimeScalar sysread(RuntimeBase... args) {
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
            e.printStackTrace();
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
            // EOF or zero-byte read - always clear the buffer when reading 0 bytes
            target.set("");
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
    public static RuntimeScalar syswrite(RuntimeBase... args) {
        if (args.length < 2) {
            throw new PerlCompilerException("Not enough arguments for syswrite");
        }

        RuntimeScalar fileHandle = args[0].scalar();
        RuntimeIO fh = fileHandle.getRuntimeIO();

        // Check if fh is null (invalid filehandle)
        if (fh == null) {
            getGlobalVariable("main::!").set("Bad file descriptor");
            WarnDie.warn(
                    new RuntimeScalar("syswrite() on unopened filehandle"),
                    new RuntimeScalar("\n")
            );
            return new RuntimeScalar(); // undef
        }

        if (fh instanceof TieHandle) {
            throw new PerlCompilerException("syswrite() is not supported on tied handles");
        }

        // Check for closed handle - but based on the debug output,
        // closed handles still have their original ioHandle, not ClosedIOHandle
        if (fh.ioHandle == null) {
            getGlobalVariable("main::!").set("Bad file descriptor");
            WarnDie.warn(
                    new RuntimeScalar("syswrite() on closed filehandle"),
                    new RuntimeScalar("\n")
            );
            return new RuntimeScalar(); // undef
        }

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
        for (int i = 0; i < toWrite.length(); i++) {
            if (toWrite.charAt(i) > 255) {
                throw new PerlCompilerException("Wide character in syswrite");
            }
        }

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
}