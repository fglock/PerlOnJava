package org.perlonjava.operators;

import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeScalar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;

import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.RuntimeIO.getPath;
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
        String filePath = fileHandle.toString();
        Path path = getPath(filePath);

        try {
            return switch (operator) {
                case "-r" ->
                    // Check if file is readable
                        getScalarBoolean(Files.isReadable(path));
                case "-w" ->
                    // Check if file is writable
                        getScalarBoolean(Files.isWritable(path));
                case "-x" ->
                    // Check if file is executable
                        getScalarBoolean(Files.isExecutable(path));
                case "-e" ->
                    // Check if file exists
                        getScalarBoolean(Files.exists(path));
                case "-z" ->
                    // Check if file is empty (zero size)
                        getScalarBoolean(Files.size(path) == 0);
                case "-s" -> {
                    // Return file size if non-zero, otherwise return false
                    long size = Files.size(path);
                    yield size > 0 ? new RuntimeScalar(size) : scalarFalse;
                }
                case "-f" ->
                    // Check if path is a regular file
                        getScalarBoolean(Files.isRegularFile(path));
                case "-d" ->
                    // Check if path is a directory
                        getScalarBoolean(Files.isDirectory(path));
                case "-l" ->
                    // Check if path is a symbolic link
                        getScalarBoolean(Files.isSymbolicLink(path));
                case "-p" ->
                    // Approximate check for named pipe (FIFO)
                        getScalarBoolean(Files.isRegularFile(path) && filePath.endsWith(".fifo"));
                case "-S" ->
                    // Approximate check for socket
                        getScalarBoolean(Files.isRegularFile(path) && filePath.endsWith(".sock"));
                case "-b" ->
                    // Approximate check for block special file
                        getScalarBoolean(Files.isRegularFile(path) && filePath.startsWith("/dev/"));
                case "-c" ->
                    // Approximate check for character special file
                        getScalarBoolean(Files.isRegularFile(path) && filePath.startsWith("/dev/"));
                case "-u" ->
                    // Check if setuid bit is set
                        getScalarBoolean((Files.getPosixFilePermissions(path).contains(PosixFilePermission.OWNER_EXECUTE)));
                case "-g" ->
                    // Check if setgid bit is set
                        getScalarBoolean((Files.getPosixFilePermissions(path).contains(PosixFilePermission.GROUP_EXECUTE)));
                case "-k" ->
                    // Approximate check for sticky bit (using others execute permission)
                        getScalarBoolean((Files.getPosixFilePermissions(path).contains(PosixFilePermission.OTHERS_EXECUTE)));
                case "-T", "-B" ->
                    // Check if file is text (-T) or binary (-B)
                        isTextOrBinary(path, operator.equals("-T"));
                case "-M", "-A", "-C" ->
                    // Get file time difference for modification (-M), access (-A), or creation (-C) time
                        getFileTimeDifference(path, operator);
                case "-R" ->
                    // Check if file is readable by the real user ID
                        getScalarBoolean(Files.isReadable(path));
                case "-W" ->
                    // Check if file is writable by the real user ID
                        getScalarBoolean(Files.isWritable(path));
                case "-X" ->
                    // Check if file is executable by the real user ID
                        getScalarBoolean(Files.isExecutable(path));
                case "-O" -> {
                    // Check if file is owned by the current user
                    UserPrincipal owner = Files.getOwner(path);
                    UserPrincipal currentUser = path.getFileSystem().getUserPrincipalLookupService()
                            .lookupPrincipalByName(System.getProperty("user.name"));
                    yield getScalarBoolean(owner.equals(currentUser));
                }
                case "-t" ->
                    // Check if the standard input is a TTY
                    getScalarBoolean(System.console() != null);
                default -> throw new UnsupportedOperationException("Unsupported file test operator: " + operator);
            };
        } catch (IOException e) {
            // Set error message in global variable and return false
            getGlobalVariable("main::!").set(e.getMessage());
            return scalarFalse;
        }
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