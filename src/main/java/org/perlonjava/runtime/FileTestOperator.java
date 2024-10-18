package org.perlonjava.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;

import static org.perlonjava.runtime.GlobalContext.getGlobalVariable;
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
        Path path = Paths.get(filePath);

        try {
            switch (operator) {
                case "-r":
                    // Check if file is readable
                    return getScalarBoolean(Files.isReadable(path));
                case "-w":
                    // Check if file is writable
                    return getScalarBoolean(Files.isWritable(path));
                case "-x":
                    // Check if file is executable
                    return getScalarBoolean(Files.isExecutable(path));
                case "-e":
                    // Check if file exists
                    return getScalarBoolean(Files.exists(path));
                case "-z":
                    // Check if file is empty (zero size)
                    return getScalarBoolean(Files.size(path) == 0);
                case "-s":
                    // Return file size if non-zero, otherwise return false
                    long size = Files.size(path);
                    return size > 0 ? new RuntimeScalar(size) : scalarFalse;
                case "-f":
                    // Check if path is a regular file
                    return getScalarBoolean(Files.isRegularFile(path));
                case "-d":
                    // Check if path is a directory
                    return getScalarBoolean(Files.isDirectory(path));
                case "-l":
                    // Check if path is a symbolic link
                    return getScalarBoolean(Files.isSymbolicLink(path));
                case "-p":
                    // Approximate check for named pipe (FIFO)
                    return getScalarBoolean(Files.isRegularFile(path) && filePath.endsWith(".fifo"));
                case "-S":
                    // Approximate check for socket
                    return getScalarBoolean(Files.isRegularFile(path) && filePath.endsWith(".sock"));
                case "-b":
                    // Approximate check for block special file
                    return getScalarBoolean(Files.isRegularFile(path) && filePath.startsWith("/dev/"));
                case "-c":
                    // Approximate check for character special file
                    return getScalarBoolean(Files.isRegularFile(path) && filePath.startsWith("/dev/"));
                case "-u":
                    // Check if setuid bit is set
                    return getScalarBoolean((Files.getPosixFilePermissions(path).contains(PosixFilePermission.OWNER_EXECUTE)));
                case "-g":
                    // Check if setgid bit is set
                    return getScalarBoolean((Files.getPosixFilePermissions(path).contains(PosixFilePermission.GROUP_EXECUTE)));
                case "-k":
                    // Approximate check for sticky bit (using others execute permission)
                    return getScalarBoolean((Files.getPosixFilePermissions(path).contains(PosixFilePermission.OTHERS_EXECUTE)));
                case "-T":
                case "-B":
                    // Check if file is text (-T) or binary (-B)
                    return isTextOrBinary(path, operator.equals("-T"));
                case "-M":
                case "-A":
                case "-C":
                    // Get file time difference for modification (-M), access (-A), or creation (-C) time
                    return getFileTimeDifference(path, operator);
                default:
                    throw new UnsupportedOperationException("Unsupported file test operator: " + operator);
            }
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
        long fileTime;

        switch (operator) {
            case "-M":
                // Get last modified time
                fileTime = Files.getLastModifiedTime(path).toMillis();
                break;
            case "-A":
                // Get last access time
                fileTime = ((FileTime) Files.getAttribute(path, "lastAccessTime", LinkOption.NOFOLLOW_LINKS)).toMillis();
                break;
            case "-C":
                // Get creation time
                fileTime = ((FileTime) Files.getAttribute(path, "creationTime", LinkOption.NOFOLLOW_LINKS)).toMillis();
                break;
            default:
                throw new PerlCompilerException("Invalid time operator: " + operator);
        }

        double daysDifference = (currentTime - fileTime) / (1000.0 * 60 * 60 * 24);
        return new RuntimeScalar(daysDifference);
    }
}