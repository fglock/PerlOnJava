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

/*
 * Implementation notes for Perl file test operators:
 *
 * 1. -R, -W, -X, -O (for real uid/gid) are not implemented due to lack of
 *    straightforward Java equivalents.
 *
 * 2. -t (tty check) is not implemented as it's specific to file handles
 *    rather than file paths.
 *
 * 3. -p, -S, -b, and -c are approximated using file names or paths, as Java
 *    doesn't provide direct equivalents.
 *
 * 4. -k (sticky bit) is approximated using the "others execute" permission,
 *    as Java doesn't have a direct equivalent.
 *
 * 5. -T and -B (text/binary check) are implemented using a heuristic similar
 *    to Perl's approach.
 *
 * 6. Time-based operators (-M, -A, -C) return the difference in days as a
 *    floating-point number.
 */
public class FileTestOperator {

    public static RuntimeScalar fileTest(String operator, RuntimeScalar fileHandle) {
        String filePath = fileHandle.toString();
        Path path = Paths.get(filePath);

        try {
            switch (operator) {
                case "-r":
                    return getScalarBoolean(Files.isReadable(path));
                case "-w":
                    return getScalarBoolean(Files.isWritable(path));
                case "-x":
                    return getScalarBoolean(Files.isExecutable(path));
                case "-e":
                    return getScalarBoolean(Files.exists(path));
                case "-z":
                    return getScalarBoolean(Files.size(path) == 0);
                case "-s":
                    long size = Files.size(path);
                    return size > 0 ? new RuntimeScalar(size) : scalarFalse;
                case "-f":
                    return getScalarBoolean(Files.isRegularFile(path));
                case "-d":
                    return getScalarBoolean(Files.isDirectory(path));
                case "-l":
                    return getScalarBoolean(Files.isSymbolicLink(path));
                case "-p":
                    return getScalarBoolean(Files.isRegularFile(path) && filePath.endsWith(".fifo"));
                case "-S":
                    return getScalarBoolean(Files.isRegularFile(path) && filePath.endsWith(".sock"));
                case "-b":
                    return getScalarBoolean(Files.isRegularFile(path) && filePath.startsWith("/dev/"));
                case "-c":
                    return getScalarBoolean(Files.isRegularFile(path) && filePath.startsWith("/dev/"));
                case "-u":
                    return getScalarBoolean((Files.getPosixFilePermissions(path).contains(PosixFilePermission.OWNER_EXECUTE)));
                case "-g":
                    return getScalarBoolean((Files.getPosixFilePermissions(path).contains(PosixFilePermission.GROUP_EXECUTE)));
                case "-k":
                    // Sticky bit is not directly supported in Java, so this is an approximation
                    return getScalarBoolean((Files.getPosixFilePermissions(path).contains(PosixFilePermission.OTHERS_EXECUTE)));
                case "-T":
                case "-B":
                    return isTextOrBinary(path, operator.equals("-T"));
                case "-M":
                case "-A":
                case "-C":
                    return getFileTimeDifference(path, operator);
                default:
                    throw new UnsupportedOperationException("Unsupported file test operator: " + operator);
            }
        } catch (IOException e) {
            getGlobalVariable("main::!").set(e.getMessage());
            return scalarFalse;
        }
    }

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

    private static RuntimeScalar getFileTimeDifference(Path path, String operator) throws IOException {
        long currentTime = System.currentTimeMillis();
        long fileTime;

        switch (operator) {
            case "-M":
                fileTime = Files.getLastModifiedTime(path).toMillis();
                break;
            case "-A":
                fileTime = ((FileTime) Files.getAttribute(path, "lastAccessTime", LinkOption.NOFOLLOW_LINKS)).toMillis();
                break;
            case "-C":
                fileTime = ((FileTime) Files.getAttribute(path, "creationTime", LinkOption.NOFOLLOW_LINKS)).toMillis();
                break;
            default:
                throw new IllegalArgumentException("Invalid time operator: " + operator);
        }

        double daysDifference = (currentTime - fileTime) / (1000.0 * 60 * 60 * 24);
        return new RuntimeScalar(daysDifference);
    }

}

