package org.perlonjava.operators;

import org.perlonjava.io.ClosedIOHandle;
import org.perlonjava.io.PipeInputChannel;
import org.perlonjava.io.PipeOutputChannel;
import org.perlonjava.io.ScalarBackedIO;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeIO;
import org.perlonjava.runtime.RuntimeScalar;
import org.perlonjava.runtime.RuntimeScalarType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;

import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.RuntimeIO.resolvePath;
import static org.perlonjava.runtime.RuntimeScalarCache.*;

/**
 * FileTestOperator class implements Perl-like file test operators in Java.
 * This class provides methods to perform various file tests similar to Perl's -X operators.
 * <p>
 * Implementation notes for Perl file test operators:
 * <p>
 * 1. -R, -W, -X, -O (for real uid/gid) are not implemented due to lack of
 * straightforward Java equivalents.
 * <p>
 * 2. -t (tty check) is not implemented as it's specific to file handles
 * rather than file paths.
 * <p>
 * 3. -p, -S, -b, and -c are approximated using file names or paths, as Java
 * doesn't provide direct equivalents.
 * <p>
 * 4. -k (sticky bit) is approximated using the "others execute" permission,
 * as Java doesn't have a direct equivalent.
 * <p>
 * 5. -T and -B (text/binary check) are implemented using a heuristic similar
 * to Perl's approach.
 * <p>
 * 6. Time-based operators (-M, -A, -C) return the difference in days as a
 * floating-point number.
 */
public class FileTestOperator {

    static RuntimeScalar lastFileHandle = new RuntimeScalar();

    public static RuntimeScalar fileTestLastHandle(String operator) {
        return fileTest(operator, lastFileHandle);
    }

    /**
     * Performs a file test based on the given operator and file handle.
     *
     * @param operator   The file test operator (e.g., "-r", "-w", "-x", etc.)
     * @param fileHandle The RuntimeScalar representing the file path or handle
     * @return A RuntimeScalar containing the result of the file test
     */
    public static RuntimeScalar fileTest(String operator, RuntimeScalar fileHandle) {
        lastFileHandle.set(fileHandle);

        // Check if the argument is a file handle (GLOB or GLOBREFERENCE)
        if (fileHandle.type == RuntimeScalarType.GLOB || fileHandle.type == RuntimeScalarType.GLOBREFERENCE) {
            RuntimeIO fh = fileHandle.getRuntimeIO();

            // Check if fh is null (invalid filehandle)
            if (fh == null) {
                // Set $! to EBADF (Bad file descriptor) = 9
                getGlobalVariable("main::!").set(9);
                return operator.equals("-l") ? scalarFalse : scalarUndef;
            }

            // Check for closed handle or no valid IO handles
            if ((fh.ioHandle == null || fh.ioHandle instanceof ClosedIOHandle) &&
                    fh.directoryIO == null) {
                // Set $! to EBADF (Bad file descriptor) = 9
                getGlobalVariable("main::!").set(9);
                return operator.equals("-l") ? scalarFalse : scalarUndef;
            }

            // Special handling for -t operator on file handles
            if (operator.equals("-t")) {
                // Check if the file handle is a TTY
                // For now, return false for all file handles as we don't have TTY detection
                return scalarFalse;
            }

            // Special handling for -f operator on file handles
            if (operator.equals("-f")) {
                // For filehandles, -f should return true if it's a regular file
                // Check if it's a pipe or special handle
                if (fh.ioHandle instanceof PipeInputChannel || fh.ioHandle instanceof PipeOutputChannel) {
                    // Pipes are not regular files
                    getGlobalVariable("main::!").set(0); // Clear error
                    return scalarFalse;
                }

                // Check if it's a directory handle
                if (fh.directoryIO != null) {
                    // Directory handles are not regular files
                    getGlobalVariable("main::!").set(0); // Clear error
                    return scalarFalse;
                }

                // Check if it's an in-memory scalar handle
                if (fh.ioHandle instanceof ScalarBackedIO) {
                    // In-memory scalar handles are not regular files
                    getGlobalVariable("main::!").set(0); // Clear error
                    return scalarFalse;
                }

                // For most other filehandles (file I/O), assume it's a regular file
                getGlobalVariable("main::!").set(0); // Clear error
                return scalarTrue;
            }

            // For most other operators on file handles, return undef and set EBADF
            getGlobalVariable("main::!").set(9);
            return operator.equals("-l") ? scalarFalse : scalarUndef;
        }

        // Handle undef - treat as non-existent file
        if (fileHandle.type == RuntimeScalarType.UNDEF) {
            getGlobalVariable("main::!").set(2); // ENOENT
            return operator.equals("-l") ? scalarFalse : scalarUndef;
        }

        // Handle string arguments
        String filename = fileHandle.toString();

        // Handle empty string - treat as non-existent file
        if (filename.isEmpty()) {
            getGlobalVariable("main::!").set(2); // ENOENT
            return operator.equals("-l") ? scalarFalse : scalarUndef;
        }

        // Note: In Perl, the distinction between bareword filehandles and strings
        // is made at compile time. If we get a string at runtime, treat it as a filename.
        // The looksLikeFilehandle check was removed because it incorrectly rejected
        // valid filenames like "TEST" that happen to match typical filehandle naming patterns.

        // Handle string filenames
        Path path = resolvePath(filename);

        try {
            return switch (operator) {
                case "-r" -> {
                    // Check if file is readable
                    if (!Files.exists(path)) {
                        getGlobalVariable("main::!").set(2); // ENOENT
                        yield scalarUndef;
                    }
                    getGlobalVariable("main::!").set(0); // Clear error
                    yield getScalarBoolean(Files.isReadable(path));
                }
                case "-w" -> {
                    // Check if file is writable
                    if (!Files.exists(path)) {
                        getGlobalVariable("main::!").set(2); // ENOENT
                        yield scalarUndef;
                    }
                    getGlobalVariable("main::!").set(0); // Clear error
                    yield getScalarBoolean(Files.isWritable(path));
                }
                case "-x" -> {
                    // Check if file is executable
                    if (!Files.exists(path)) {
                        getGlobalVariable("main::!").set(2); // ENOENT
                        yield scalarUndef;
                    }
                    getGlobalVariable("main::!").set(0); // Clear error
                    yield getScalarBoolean(Files.isExecutable(path));
                }
                case "-e" -> {
                    // Check if file exists
                    boolean exists = Files.exists(path);
                    getGlobalVariable("main::!").set(exists ? 0 : 2); // Clear error or set ENOENT
                    yield getScalarBoolean(exists);
                }
                case "-z" -> {
                    // Check if file is empty (zero size)
                    if (!Files.exists(path)) {
                        getGlobalVariable("main::!").set(2); // ENOENT
                        yield scalarUndef;
                    }
                    getGlobalVariable("main::!").set(0); // Clear error
                    yield getScalarBoolean(Files.size(path) == 0);
                }
                case "-s" -> {
                    // Return file size if non-zero, otherwise return false
                    if (!Files.exists(path)) {
                        getGlobalVariable("main::!").set(2); // ENOENT
                        yield scalarUndef;
                    }
                    getGlobalVariable("main::!").set(0); // Clear error
                    long size = Files.size(path);
                    yield size > 0 ? new RuntimeScalar(size) : scalarFalse;
                }
                case "-f" -> {
                    // Check if path is a regular file
                    if (!Files.exists(path)) {
                        getGlobalVariable("main::!").set(2); // ENOENT
                        yield scalarUndef;
                    }
                    getGlobalVariable("main::!").set(0); // Clear error
                    yield getScalarBoolean(Files.isRegularFile(path));
                }
                case "-d" -> {
                    // Check if path is a directory
                    if (!Files.exists(path)) {
                        getGlobalVariable("main::!").set(2); // ENOENT
                        yield scalarUndef;
                    }
                    getGlobalVariable("main::!").set(0); // Clear error
                    yield getScalarBoolean(Files.isDirectory(path));
                }
                case "-l" -> {
                    // Check if path is a symbolic link
                    boolean isSymLink = Files.isSymbolicLink(path);
                    getGlobalVariable("main::!").set(isSymLink || Files.exists(path) ? 0 : 2);
                    yield getScalarBoolean(isSymLink);
                }
                case "-p" -> {
                    // Approximate check for named pipe (FIFO)
                    if (!Files.exists(path)) {
                        getGlobalVariable("main::!").set(2); // ENOENT
                        yield scalarUndef;
                    }
                    getGlobalVariable("main::!").set(0); // Clear error
                    yield getScalarBoolean(Files.isRegularFile(path) && filename.endsWith(".fifo"));
                }
                case "-S" -> {
                    // Approximate check for socket
                    if (!Files.exists(path)) {
                        getGlobalVariable("main::!").set(2); // ENOENT
                        yield scalarUndef;
                    }
                    getGlobalVariable("main::!").set(0); // Clear error
                    yield getScalarBoolean(Files.isRegularFile(path) && filename.endsWith(".sock"));
                }
                case "-b" -> {
                    // Approximate check for block special file
                    if (!Files.exists(path)) {
                        getGlobalVariable("main::!").set(2); // ENOENT
                        yield scalarUndef;
                    }
                    getGlobalVariable("main::!").set(0); // Clear error
                    yield getScalarBoolean(Files.isRegularFile(path) && filename.startsWith("/dev/"));
                }
                case "-c" -> {
                    // Approximate check for character special file
                    if (!Files.exists(path)) {
                        getGlobalVariable("main::!").set(2); // ENOENT
                        yield scalarUndef;
                    }
                    getGlobalVariable("main::!").set(0); // Clear error
                    yield getScalarBoolean(Files.isRegularFile(path) && filename.startsWith("/dev/"));
                }
                case "-u" -> {
                    // Check if setuid bit is set
                    if (!Files.exists(path)) {
                        getGlobalVariable("main::!").set(2); // ENOENT
                        yield scalarUndef;
                    }
                    getGlobalVariable("main::!").set(0); // Clear error
                    yield getScalarBoolean((Files.getPosixFilePermissions(path).contains(PosixFilePermission.OWNER_EXECUTE)));
                }
                case "-g" -> {
                    // Check if setgid bit is set
                    if (!Files.exists(path)) {
                        getGlobalVariable("main::!").set(2); // ENOENT
                        yield scalarUndef;
                    }
                    getGlobalVariable("main::!").set(0); // Clear error
                    yield getScalarBoolean((Files.getPosixFilePermissions(path).contains(PosixFilePermission.GROUP_EXECUTE)));
                }
                case "-k" -> {
                    // Approximate check for sticky bit (using others execute permission)
                    if (!Files.exists(path)) {
                        getGlobalVariable("main::!").set(2); // ENOENT
                        yield scalarUndef;
                    }
                    getGlobalVariable("main::!").set(0); // Clear error
                    yield getScalarBoolean((Files.getPosixFilePermissions(path).contains(PosixFilePermission.OTHERS_EXECUTE)));
                }
                case "-T", "-B" -> {
                    // Check if file is text (-T) or binary (-B)
                    if (!Files.exists(path)) {
                        getGlobalVariable("main::!").set(2); // ENOENT
                        yield scalarUndef;
                    }
                    getGlobalVariable("main::!").set(0); // Clear error
                    yield isTextOrBinary(path, operator.equals("-T"));
                }
                case "-M", "-A", "-C" -> {
                    // Get file time difference for modification (-M), access (-A), or creation (-C) time
                    if (!Files.exists(path)) {
                        getGlobalVariable("main::!").set(2); // ENOENT
                        yield scalarUndef;
                    }
                    getGlobalVariable("main::!").set(0); // Clear error
                    yield getFileTimeDifference(path, operator);
                }
                case "-R" -> {
                    // Check if file is readable by the real user ID
                    if (!Files.exists(path)) {
                        getGlobalVariable("main::!").set(2); // ENOENT
                        yield scalarUndef;
                    }
                    getGlobalVariable("main::!").set(0); // Clear error
                    yield getScalarBoolean(Files.isReadable(path));
                }
                case "-W" -> {
                    // Check if file is writable by the real user ID
                    if (!Files.exists(path)) {
                        getGlobalVariable("main::!").set(2); // ENOENT
                        yield scalarUndef;
                    }
                    getGlobalVariable("main::!").set(0); // Clear error
                    yield getScalarBoolean(Files.isWritable(path));
                }
                case "-X" -> {
                    // Check if file is executable by the real user ID
                    if (!Files.exists(path)) {
                        getGlobalVariable("main::!").set(2); // ENOENT
                        yield scalarUndef;
                    }
                    getGlobalVariable("main::!").set(0); // Clear error
                    yield getScalarBoolean(Files.isExecutable(path));
                }
                case "-O" -> {
                    // Check if file is owned by the current user
                    if (!Files.exists(path)) {
                        getGlobalVariable("main::!").set(2); // ENOENT
                        yield scalarUndef;
                    }
                    getGlobalVariable("main::!").set(0); // Clear error
                    UserPrincipal owner = Files.getOwner(path);
                    UserPrincipal currentUser = path.getFileSystem().getUserPrincipalLookupService()
                            .lookupPrincipalByName(System.getProperty("user.name"));
                    yield getScalarBoolean(owner.equals(currentUser));
                }
                case "-t" -> {
                    // -t on a string filename is an error in Perl (expects a filehandle)
                    // Set $! = EBADF and return undef
                    getGlobalVariable("main::!").set(9); // EBADF
                    yield scalarUndef;
                }
                default -> throw new UnsupportedOperationException("Unsupported file test operator: " + operator);
            };
        } catch (IOException e) {
            // Set error message in global variable and return false/undef
            getGlobalVariable("main::!").set(2); // ENOENT for most file operations
            return operator.equals("-l") ? scalarFalse : scalarUndef;
        }
    }

    public static RuntimeScalar chainedFileTest(String[] operators, RuntimeScalar fileHandle) {
        // Execute operators from right to left
        // First operator uses the provided fileHandle, subsequent ones use lastFileHandle (_)
        RuntimeScalar result = null;
        for (int i = 0; i < operators.length; i++) {
            if (i == 0) {
                // First operator (rightmost in the source) uses the provided fileHandle
                result = fileTest(operators[i], fileHandle);
            } else {
                // Subsequent operators use lastFileHandle (_)
                result = fileTest(operators[i], lastFileHandle);
            }
        }
        return result;
    }

    public static RuntimeScalar chainedFileTestLastHandle(String[] operators) {
        return chainedFileTest(operators, lastFileHandle);
    }

    /**
     * Determines if a file is text or binary based on its content.
     *
     * @param path         The path to the file
     * @param checkForText True if checking for text, false if checking for binary
     * @return A RuntimeScalar representing the result (true or false)
     * @throws IOException If an I/O error occurs
     */
    private static RuntimeScalar isTextOrBinary(Path path, boolean checkForText) throws IOException {
        byte[] buffer = new byte[1024];
        int bytesRead = Files.newInputStream(path).read(buffer);
        if (bytesRead == -1) {
            return scalarTrue; // Empty file is considered both text and binary
        }

        int textChars = 0;
        int totalChars = 0;

        for (int i = 0; i < bytesRead; i++) {
            if (buffer[i] == 0) {
                return checkForText ? scalarFalse : scalarTrue; // Binary file
            }
            if ((buffer[i] >= 32 && buffer[i] <= 126) || buffer[i] == '\n' || buffer[i] == '\r' || buffer[i] == '\t') {
                textChars++;
            }
            totalChars++;
        }

        double textRatio = (double) textChars / totalChars;
        return getScalarBoolean(checkForText ? textRatio > 0.7 : textRatio <= 0.7);
    }

    /**
     * Calculates the time difference between the current time and the file's time attribute.
     *
     * @param path     The path to the file
     * @param operator The time-based operator (-M, -A, or -C)
     * @return A RuntimeScalar containing the time difference in days
     * @throws IOException If an I/O error occurs
     */
    private static RuntimeScalar getFileTimeDifference(Path path, String operator) throws IOException {
        long currentTime = System.currentTimeMillis();
        long fileTime = switch (operator) {
            case "-M" ->
                // Get last modified time
                    Files.getLastModifiedTime(path).toMillis();
            case "-A" ->
                // Get last access time
                    ((FileTime) Files.getAttribute(path, "lastAccessTime", LinkOption.NOFOLLOW_LINKS)).toMillis();
            case "-C" ->
                // Get creation time
                    ((FileTime) Files.getAttribute(path, "creationTime", LinkOption.NOFOLLOW_LINKS)).toMillis();
            default -> throw new PerlCompilerException("Invalid time operator: " + operator);
        };

        double daysDifference = (currentTime - fileTime) / (1000.0 * 60 * 60 * 24);
        return new RuntimeScalar(daysDifference);
    }
}
