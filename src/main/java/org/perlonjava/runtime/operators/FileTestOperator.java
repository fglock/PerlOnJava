package org.perlonjava.runtime.operators;

import org.perlonjava.runtime.io.*;
import org.perlonjava.runtime.nativ.ffm.FFMPosix;
import org.perlonjava.runtime.perlmodule.Warnings;
import org.perlonjava.runtime.runtimetypes.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.*;

import static org.perlonjava.runtime.runtimetypes.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.runtimetypes.RuntimeIO.resolvePath;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.*;

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
        Stat.lastNativeStatFields = null;
        // Always reset BasicFileAttributes - they should only be set by statForFileTest
        // for real filesystem paths. JAR resources don't have BasicFileAttributes.
        lastBasicAttr = null;
        lastPosixAttr = null;
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
        return new RuntimeScalar(" at " + fileName + " line " + line + ".\n");
    }

    private static String filehandleShortName(RuntimeScalar fileHandle) {
        String globName = null;
        if (fileHandle.value instanceof RuntimeGlob runtimeGlob) {
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

            PosixFileAttributes posixAttr = null;
            try {
                posixAttr = lstat
                        ? Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                        : Files.readAttributes(path, PosixFileAttributes.class);
            } catch (UnsupportedOperationException | IOException ignored) {
            }

            getGlobalVariable("main::!").set(0);
            updateLastStat(arg, true, 0, lstat);
            // Set attributes after updateLastStat (which resets them to null)
            lastBasicAttr = basicAttr;
            lastPosixAttr = posixAttr;
            Stat.lastNativeStatFields = Stat.nativeStat(path.toString(), !lstat);
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

        // If lastBasicAttr is null (e.g., after testing a JAR path), fall back to re-testing
        if (lastBasicAttr == null) {
            return fileTest(operator, lastFileHandle);
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
            case "-T", "-B" -> {
                // Handle -T/-B on _ without re-statting (preserves stat buffer)
                // We need to resolve the path from the last stat argument and read file content
                // without calling fileTest() which would overwrite the stat buffer.
                try {
                    String filename = lastStatArg.toString();
                    Path path = resolvePath(filename);
                    if (path == null || !Files.exists(path)) {
                        getGlobalVariable("main::!").set(2);
                        yield scalarUndef;
                    }
                    getGlobalVariable("main::!").set(0);
                    yield isTextOrBinary(path, operator.equals("-T"));
                } catch (IOException e) {
                    getGlobalVariable("main::!").set(5);
                    yield scalarUndef;
                }
            }
            default -> fileTest(operator, lastFileHandle);
        };
    }

    private static boolean isUnderscoreTypeglob(RuntimeScalar fileHandle) {
        if (fileHandle.value instanceof RuntimeGlob runtimeGlob) {
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

            // Try to get the file path from the handle for stat-based file tests
            IOHandle innerHandle = fh.ioHandle;
            while (true) {
                if (innerHandle instanceof LayeredIOHandle lh) {
                    innerHandle = lh.getDelegate();
                } else if (innerHandle instanceof DupIOHandle dh) {
                    innerHandle = dh.getDelegate();
                } else if (innerHandle instanceof BorrowedIOHandle bh) {
                    innerHandle = bh.getDelegate();
                } else {
                    break;
                }
            }
            if (innerHandle instanceof CustomFileChannel cfc) {
                // Special handling for -T/-B on filehandles: check from current position
                if (operator.equals("-T") || operator.equals("-B")) {
                    Path path = cfc.getFilePath();
                    if (path != null) {
                        // Stat the file first (like Perl does)
                        statForFileTest(fileHandle, path, false);
                    }
                    // At EOF, both -T and -B return true (Perl behavior)
                    if (cfc.eof().getBoolean()) {
                        return scalarTrue;
                    }
                    // Read from current position to determine text/binary
                    try {
                        return isTextOrBinaryFromHandle(cfc, operator.equals("-T"));
                    } catch (IOException e) {
                        getGlobalVariable("main::!").set(5);
                        return scalarUndef;
                    }
                }
                Path path = cfc.getFilePath();
                if (path != null) {
                    return fileTest(operator, new RuntimeScalar(path.toString()));
                }
            }
            // Check for directory handle
            if (fh.directoryIO != null) {
                Path dirPath = fh.directoryIO.getAbsoluteDirectoryPath();
                if (dirPath != null) {
                    return fileTest(operator, new RuntimeScalar(dirPath.toString()));
                }
            }
            // Special handling for -t on standard streams (STDIN, STDOUT, STDERR)
            if (operator.equals("-t")) {
                String globName = null;
                if (fileHandle.value instanceof RuntimeGlob rg) {
                    globName = rg.globName;
                } else if (fileHandle.value instanceof RuntimeIO rio) {
                    globName = rio.globName;
                }
                if (globName != null) {
                    int fd = -1;
                    if (globName.endsWith("::STDIN") || globName.equals("STDIN")) {
                        fd = 0;
                    } else if (globName.endsWith("::STDOUT") || globName.equals("STDOUT")) {
                        fd = 1;
                    } else if (globName.endsWith("::STDERR") || globName.equals("STDERR")) {
                        fd = 2;
                    }
                    if (fd >= 0) {
                        try {
                            boolean isTty = FFMPosix.get().isatty(fd) != 0;
                            getGlobalVariable("main::!").set(0);
                            return getScalarBoolean(isTty);
                        } catch (Exception e) {
                            // Fall back to System.console() check for fd 0
                            if (fd == 0) {
                                boolean isTty = System.console() != null;
                                getGlobalVariable("main::!").set(0);
                                return getScalarBoolean(isTty);
                            }
                        }
                    }
                }
            }
            // Fallback for non-file handles (pipes, sockets, etc.)
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

        // Handle NUL bytes in filename - Perl warns and treats as non-existent
        if (filename.indexOf('\0') >= 0) {
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

        // Handle JAR virtual directory and JAR resource paths
        // Note: Check filename != null first to avoid NPE
        // Also use simple string check to avoid loading Jar class early
        if (filename != null && filename.startsWith("jar:")) {
            if (Jar.isJarDirectory(filename)) {
                // "jar:PERL5LIB" - the virtual directory
                updateLastStat(fileHandle, true, 0);
                return switch (operator) {
                    case "-d", "-e", "-r", "-x" -> scalarTrue;  // It's a readable, executable directory
                    case "-f", "-l", "-w", "-z" -> scalarFalse;  // Not a file, link, writable, or empty
                    case "-s" -> RuntimeScalarCache.scalarZero;  // Size 0
                    default -> scalarUndef;
                };
            }
            // JAR resource path (e.g., "jar:PERL5LIB/DBI.pm")
            if (Jar.exists(filename)) {
                // Check if it's a directory entry (not a file)
                if (Jar.isResourceDirectory(filename)) {
                    updateLastStat(fileHandle, true, 0);
                    return switch (operator) {
                        case "-d", "-e", "-r", "-x" -> scalarTrue;  // It's a readable, executable directory
                        case "-f", "-l", "-w", "-z" -> scalarFalse;  // Not a file, link, writable, or empty
                        case "-s" -> RuntimeScalarCache.scalarZero;  // Size 0
                        default -> scalarUndef;
                    };
                }
                // It's a regular file
                updateLastStat(fileHandle, true, 0);
                return switch (operator) {
                    case "-e", "-f", "-r" -> scalarTrue;  // Exists, is a file, is readable
                    case "-d", "-l", "-w", "-z" -> scalarFalse;  // Not a dir, link, writable, or empty
                    case "-s" -> {
                        long size = Jar.getSize(filename);
                        yield size > 0 ? new RuntimeScalar(size) : RuntimeScalarCache.scalarZero;
                    }
                    default -> scalarUndef;
                };
            } else {
                getGlobalVariable("main::!").set(2);  // ENOENT
                updateLastStat(fileHandle, false, 2);
                return scalarUndef;
            }
        }

        // Handle string filenames
        Path path = resolvePath(filename);

        if (path == null) {
            getGlobalVariable("main::!").set(2);  // ENOENT
            updateLastStat(fileHandle, false, 2);
            return scalarUndef;
        }

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
                    // Check for named pipe (FIFO) using native stat mode bits
                    if (!Files.exists(path)) {
                        getGlobalVariable("main::!").set(2); // ENOENT
                        yield scalarUndef;
                    }
                    getGlobalVariable("main::!").set(0);
                    if (Stat.lastNativeStatFields != null) {
                        yield getScalarBoolean((Stat.lastNativeStatFields.mode() & 0170000) == 0010000);
                    }
                    yield getScalarBoolean(Files.isRegularFile(path) && filename.endsWith(".fifo"));
                }
                case "-S" -> {
                    // Check for socket using native stat mode bits
                    if (!Files.exists(path)) {
                        getGlobalVariable("main::!").set(2); // ENOENT
                        yield scalarUndef;
                    }
                    getGlobalVariable("main::!").set(0);
                    if (Stat.lastNativeStatFields != null) {
                        yield getScalarBoolean((Stat.lastNativeStatFields.mode() & 0170000) == 0140000);
                    }
                    yield getScalarBoolean(Files.isRegularFile(path) && filename.endsWith(".sock"));
                }
                case "-b" -> {
                    // Check for block special file using native stat mode bits
                    if (!Files.exists(path)) {
                        getGlobalVariable("main::!").set(2); // ENOENT
                        yield scalarUndef;
                    }
                    getGlobalVariable("main::!").set(0);
                    if (Stat.lastNativeStatFields != null) {
                        yield getScalarBoolean((Stat.lastNativeStatFields.mode() & 0170000) == 0060000);
                    }
                    yield getScalarBoolean(Files.isRegularFile(path) && filename.startsWith("/dev/"));
                }
                case "-c" -> {
                    // Check for character special file using native stat mode bits
                    if (!Files.exists(path)) {
                        getGlobalVariable("main::!").set(2); // ENOENT
                        yield scalarUndef;
                    }
                    getGlobalVariable("main::!").set(0);
                    if (Stat.lastNativeStatFields != null) {
                        yield getScalarBoolean((Stat.lastNativeStatFields.mode() & 0170000) == 0020000);
                    }
                    yield getScalarBoolean(Files.isRegularFile(path) && filename.startsWith("/dev/"));
                }
                case "-u" -> {
                    // Check if setuid bit is set using native stat mode bits
                    if (!Files.exists(path)) {
                        getGlobalVariable("main::!").set(2); // ENOENT
                        yield scalarUndef;
                    }
                    getGlobalVariable("main::!").set(0);
                    if (Stat.lastNativeStatFields != null) {
                        yield getScalarBoolean((Stat.lastNativeStatFields.mode() & 04000) != 0);
                    }
                    yield getScalarBoolean
                            ((Files.getPosixFilePermissions(path).contains(PosixFilePermission.OWNER_EXECUTE)));
                }
                case "-g" -> {
                    // Check if setgid bit is set using native stat mode bits
                    if (!Files.exists(path)) {
                        getGlobalVariable("main::!").set(2); // ENOENT
                        yield scalarUndef;
                    }
                    getGlobalVariable("main::!").set(0);
                    if (Stat.lastNativeStatFields != null) {
                        yield getScalarBoolean((Stat.lastNativeStatFields.mode() & 02000) != 0);
                    }
                    yield getScalarBoolean
                            ((Files.getPosixFilePermissions(path).contains(PosixFilePermission.GROUP_EXECUTE)));
                }
                case "-k" -> {
                    // Check for sticky bit using native stat mode bits
                    if (!Files.exists(path)) {
                        getGlobalVariable("main::!").set(2); // ENOENT
                        yield scalarUndef;
                    }
                    getGlobalVariable("main::!").set(0);
                    if (Stat.lastNativeStatFields != null) {
                        yield getScalarBoolean((Stat.lastNativeStatFields.mode() & 01000) != 0);
                    }
                    yield getScalarBoolean
                            ((Files.getPosixFilePermissions(path).contains(PosixFilePermission.OTHERS_EXECUTE)));
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

        return analyzeTextBinary(buffer, bytesRead, checkForText);
    }

    /**
     * Determines if a filehandle's content from the current position is text or binary.
     * Used for -T/-B on filehandles where we need to read from the current position,
     * not from the beginning of the file. Saves and restores the file position.
     *
     * @param cfc          The CustomFileChannel to read from
     * @param checkForText True if checking for text, false if checking for binary
     * @return A RuntimeScalar representing the result (true or false)
     * @throws IOException If an I/O error occurs
     */
    private static RuntimeScalar isTextOrBinaryFromHandle(CustomFileChannel cfc, boolean checkForText) throws IOException {
        // Save current position
        long savedPos = cfc.tell().getLong();
        // Read up to 1024 bytes from the current position using sysread
        RuntimeScalar data = cfc.sysread(1024);
        // Restore position (Perl's -T/-B don't permanently advance the handle)
        cfc.seek(savedPos, 0);
        if (data.type == RuntimeScalarType.UNDEF) {
            return scalarTrue; // No data = both text and binary (like empty file)
        }
        byte[] buffer = data.toString().getBytes();
        if (buffer.length == 0) {
            return scalarTrue;
        }
        return analyzeTextBinary(buffer, buffer.length, checkForText);
    }

    /**
     * Common heuristic for text/binary detection.
     */
    private static RuntimeScalar analyzeTextBinary(byte[] buffer, int length, boolean checkForText) {
        int textChars = 0;
        int totalChars = 0;

        for (int i = 0; i < length; i++) {
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
        // Use $^T (program start time) as base, not current time - Perl semantics
        long currentTime = getGlobalVariable("main::" + Character.toString('T' - 'A' + 1)).getLong() * 1000L;
        long fileTime = switch (operator) {
            case "-M" ->
                // Get last modified time
                    Files.getLastModifiedTime(path).toMillis();
            case "-A" ->
                // Get last access time
                    ((FileTime) Files.getAttribute(path, "lastAccessTime", LinkOption.NOFOLLOW_LINKS)).toMillis();
            case "-C" -> {
                // Get ctime (inode change time on Unix, creation time fallback)
                if (Stat.lastNativeStatFields != null) {
                    yield Stat.lastNativeStatFields.ctime() * 1000L;
                }
                yield ((FileTime) Files.getAttribute(path, "creationTime", LinkOption.NOFOLLOW_LINKS)).toMillis();
            }
            default -> throw new PerlCompilerException("Invalid time operator: " + operator);
        };

        double daysDifference = (currentTime - fileTime) / (1000.0 * 60 * 60 * 24);
        return new RuntimeScalar(daysDifference);
    }
}
