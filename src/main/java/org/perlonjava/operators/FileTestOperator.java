package org.perlonjava.operators;

import org.perlonjava.io.ClosedIOHandle;
import org.perlonjava.io.PipeInputChannel;
import org.perlonjava.io.PipeOutputChannel;
import org.perlonjava.io.ScalarBackedIO;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeCode;
import org.perlonjava.runtime.RuntimeContextType;
import org.perlonjava.runtime.RuntimeIO;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;
import org.perlonjava.runtime.RuntimeScalarType;
import org.perlonjava.runtime.RuntimeScalarCache;
import org.perlonjava.perlmodule.Warnings;
import org.perlonjava.operators.WarnDie;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributes;
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

    static boolean lastStatOk = false;
    static int lastStatErrno = 0;
    static RuntimeScalar lastStatArg = new RuntimeScalar();
    static boolean lastStatWasLstat = false;
    static BasicFileAttributes lastBasicAttr;
    static PosixFileAttributes lastPosixAttr;

    static void updateLastStat(RuntimeScalar arg, boolean ok, int errno, boolean wasLstat) {
        lastStatArg.set(arg);
        lastStatOk = ok;
        lastStatErrno = errno;
        lastStatWasLstat = wasLstat;
        if (!ok) {
            lastBasicAttr = null;
            lastPosixAttr = null;
        }
    }

    static void updateLastStat(RuntimeScalar arg, boolean ok, int errno) {
        updateLastStat(arg, ok, errno, false);
    }

    private static boolean warningsEnabled() {
        return getGlobalVariable("main::" + Character.toString('W' - 'A' + 1)).getBoolean()
                || Warnings.warningManager.isWarningEnabled("all");
    }

    private static RuntimeScalar callerWhere() {
        RuntimeList caller = RuntimeCode.caller(new RuntimeList(RuntimeScalarCache.getScalarInt(0)), RuntimeContextType.LIST);
        if (caller.size() < 3) {
            return new RuntimeScalar("\n");
        }
        String fileName = caller.elements.get(1).toString();
        int line = ((RuntimeScalar) caller.elements.get(2)).getInt();
        return new RuntimeScalar(" at " + fileName + " line " + line + "\n");
    }

    private static String filehandleShortName(RuntimeScalar fileHandle) {
        String globName = null;
        if (fileHandle.value instanceof org.perlonjava.runtime.RuntimeGlob runtimeGlob) {
            globName = runtimeGlob.globName;
        } else if (fileHandle.value instanceof RuntimeIO runtimeIO) {
            globName = runtimeIO.globName;
        }
        if (globName == null) {
            return null;
        }
        int lastColon = globName.lastIndexOf("::");
        return lastColon >= 0 ? globName.substring(lastColon + 2) : globName;
    }

    private static void warnFilehandleL(RuntimeScalar fileHandle) {
        if (!warningsEnabled()) {
            return;
        }
        String name = filehandleShortName(fileHandle);
        String msg = (name == null || name.isEmpty())
                ? "Use of -l on filehandle"
                : "Use of -l on filehandle " + name;
        WarnDie.warn(new RuntimeScalar(msg), callerWhere());
    }

    private static boolean isIORef(RuntimeScalar fileHandle) {
        return (fileHandle.type == RuntimeScalarType.GLOB && fileHandle.value instanceof RuntimeIO)
                || (fileHandle.type == RuntimeScalarType.GLOBREFERENCE && fileHandle.value instanceof RuntimeIO);
    }

    private static boolean statForFileTest(RuntimeScalar arg, Path path, boolean lstat) {
        try {
            BasicFileAttributes basicAttr = lstat
                    ? Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                    : Files.readAttributes(path, BasicFileAttributes.class);

            // POSIX attributes are not available on all platforms (e.g. Windows).
            // Perl filetest operators like -e/-f/-d only need the basic attributes.
            PosixFileAttributes posixAttr = null;
            try {
                posixAttr = lstat
                        ? Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                        : Files.readAttributes(path, PosixFileAttributes.class);
            } catch (UnsupportedOperationException | IOException ignored) {
                // Leave posixAttr as null.
            }

            lastBasicAttr = basicAttr;
            lastPosixAttr = posixAttr;
            getGlobalVariable("main::!").set(0);
            updateLastStat(arg, true, 0, lstat);
            return true;
        } catch (NoSuchFileException e) {
            getGlobalVariable("main::!").set(2);
            updateLastStat(arg, false, 2, lstat);
            return false;
        } catch (IOException | UnsupportedOperationException e) {
            getGlobalVariable("main::!").set(5);
            updateLastStat(arg, false, 5, lstat);
            return false;
        }
    }

    private static RuntimeScalar fileTestFromLastStat(String operator) {
        if (!lastStatOk) {
            if (operator.equals("-T") || operator.equals("-B")) {
                getGlobalVariable("main::!").set(lastStatErrno != 0 ? lastStatErrno : 2);
                return scalarUndef;
            }
            getGlobalVariable("main::!").set(9);
            return scalarUndef;
        }

        if (operator.equals("-l") && !lastStatWasLstat) {
            throw new PerlCompilerException("The stat preceding -l _ wasn't an lstat");
        }

        return switch (operator) {
            case "-e" -> scalarTrue;
            case "-f" -> getScalarBoolean(lastBasicAttr.isRegularFile());
            case "-d" -> getScalarBoolean(lastBasicAttr.isDirectory());
            case "-s" -> {
                long size = lastBasicAttr.size();
                yield size > 0 ? new RuntimeScalar(size) : RuntimeScalarCache.scalarZero;
            }
            case "-z" -> getScalarBoolean(lastBasicAttr.size() == 0);
            case "-l" -> getScalarBoolean(lastBasicAttr.isSymbolicLink());
            default -> fileTest(operator, lastFileHandle);
        };
    }

    private static boolean isUnderscoreTypeglob(RuntimeScalar fileHandle) {
        if (fileHandle.value instanceof org.perlonjava.runtime.RuntimeGlob runtimeGlob) {
            return runtimeGlob.globName != null && runtimeGlob.globName.endsWith("::_");
        }
        if (fileHandle.value instanceof RuntimeIO runtimeIO) {
            return runtimeIO.globName != null && runtimeIO.globName.endsWith("::_");
        }
        return false;
    }

    private static boolean looksLikeFilehandle(String name) {
        // Check if it's a typical filehandle name (all caps, starts with letter, no path separators)
        return name.matches("^[A-Z_][A-Z0-9_]*$") && !name.contains("/") && !name.contains("\\");
    }

    public static RuntimeScalar fileTestLastHandle(String operator) {
        // Perl's special '_' uses the cached stat buffer and must not re-stat.
        return fileTestFromLastStat(operator);
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

            // Perl warns on -l applied to a filehandle (including unopened filehandles and IO refs),
            // and returns undef with EBADF. Check this before checking if fh is null.
            if (operator.equals("-l")) {
                warnFilehandleL(fileHandle);
                getGlobalVariable("main::!").set(9);  // EBADF
                updateLastStat(fileHandle, false, 9);
                return scalarUndef;
            }

            // Check if fh is null (invalid filehandle)
            if (fh == null) {
                // Special case: -T/-B on * _ (or \*_) should preserve last stat errno when the last stat failed,
                // even if the underscore typeglob doesn't have its IO slot initialized.
                if ((operator.equals("-T") || operator.equals("-B")) && !lastStatOk && isUnderscoreTypeglob(fileHandle)) {
                    getGlobalVariable("main::!").set(lastStatErrno != 0 ? lastStatErrno : 2);
                    return scalarUndef;
                }
                // Set $! to EBADF (Bad file descriptor) = 9
                getGlobalVariable("main::!").set(9);
                updateLastStat(fileHandle, false, 9);
                return scalarUndef;
            }

            // Special case: -T/-B on * _ (or \*_) should preserve last stat errno when the last stat failed.
            if ((operator.equals("-T") || operator.equals("-B")) && !lastStatOk && isUnderscoreTypeglob(fileHandle)) {
                getGlobalVariable("main::!").set(lastStatErrno != 0 ? lastStatErrno : 2);
                return scalarUndef;
            }

            // Check for closed handle or no valid IO handles
            if ((fh.ioHandle == null || fh.ioHandle instanceof ClosedIOHandle) &&
                    fh.directoryIO == null) {
                if ((operator.equals("-T") || operator.equals("-B")) && !lastStatOk && isUnderscoreTypeglob(fileHandle)) {
                    getGlobalVariable("main::!").set(lastStatErrno != 0 ? lastStatErrno : 2);
                    return scalarUndef;
                }
                // Set $! to EBADF (Bad file descriptor) = 9
                getGlobalVariable("main::!").set(9);
                updateLastStat(fileHandle, false, 9);
                return scalarUndef;
            }

            // For file test operators on file handles, return undef and set EBADF
            getGlobalVariable("main::!").set(9);
            updateLastStat(fileHandle, false, 9);
            return scalarUndef;
        }

        // Handle undef - treat as non-existent file
        if (fileHandle.type == RuntimeScalarType.UNDEF) {
            getGlobalVariable("main::!").set(2); // ENOENT
            updateLastStat(fileHandle, false, 2);
            return operator.equals("-l") ? scalarFalse : scalarUndef;
        }

        // Handle string arguments
        String filename = fileHandle.toString();

        // Handle empty string - treat as non-existent file
        if (filename.isEmpty()) {
            getGlobalVariable("main::!").set(2); // ENOENT
            updateLastStat(fileHandle, false, 2);
            return operator.equals("-l") ? scalarFalse : scalarUndef;
        }

        // Check if it looks like a filehandle name but isn't actually a filehandle
        if (looksLikeFilehandle(filename)) {
            // Try to get it as a global variable (filehandle)
            RuntimeScalar globVar = null;
            try {
                globVar = getGlobalVariable("main::" + filename);
                if (globVar != null && (globVar.type == RuntimeScalarType.GLOB || globVar.type == RuntimeScalarType.GLOBREFERENCE)) {
                    // It's actually a filehandle, recursively call fileTest with it
                    return fileTest(operator, globVar);
                }
            } catch (Exception e) {
                // Ignore, treat as filename
            }

            // It looks like a filehandle but isn't one - fall through to treat as filename
            // Don't return error here, as it could be a legitimate uppercase filename
        }

        // Handle string filenames
        Path path = resolvePath(filename);

        try {
            boolean lstat = operator.equals("-l");
            statForFileTest(fileHandle, path, lstat);
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
                    if (!exists) {
                        getGlobalVariable("main::!").set(2); // ENOENT
                        yield scalarUndef;
                    }
                    getGlobalVariable("main::!").set(0); // Clear error
                    yield scalarTrue;
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
                    if (!lastStatOk) {
                        yield scalarUndef;
                    }
                    long size = lastBasicAttr.size();
                    yield size > 0 ? new RuntimeScalar(size) : RuntimeScalarCache.scalarZero;
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
                    if (!lastStatOk) {
                        yield scalarUndef;
                    }
                    yield getScalarBoolean(lastBasicAttr.isSymbolicLink());
                }
                case "-o" -> {
                    // Check if file is owned by the effective user id (approximate with current user)
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
            updateLastStat(fileHandle, false, 2);
            return scalarUndef;
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
                // Subsequent operators use the cached stat buffer (_)
                result = fileTestLastHandle(operators[i]);
            }
        }
        return result;
    }

    public static RuntimeScalar chainedFileTestLastHandle(String[] operators) {
        // When the operand is '_', all filetests must use the cached stat buffer.
        RuntimeScalar result = null;
        for (String operator : operators) {
            result = fileTestLastHandle(operator);
        }
        return result;
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
