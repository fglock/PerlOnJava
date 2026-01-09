package org.perlonjava.operators;

import org.perlonjava.io.ClosedIOHandle;
import org.perlonjava.runtime.RuntimeBase;
import org.perlonjava.runtime.RuntimeContextType;
import org.perlonjava.runtime.RuntimeIO;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;
import org.perlonjava.runtime.RuntimeScalarType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.perlonjava.operators.FileTestOperator.lastFileHandle;
import static org.perlonjava.operators.FileTestOperator.updateLastStat;
import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.RuntimeIO.resolvePath;
import static org.perlonjava.runtime.RuntimeScalarCache.getScalarInt;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarTrue;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;


public class Stat {

    // Helper method to get permissions in octal format
    private static int getPermissionsOctal(BasicFileAttributes basicAttr, PosixFileAttributes attr) {
        int permissions = 0;

        // File type bits (first)
        if (basicAttr.isDirectory()) permissions |= 0040000;  // Directory
        if (basicAttr.isRegularFile()) permissions |= 0100000;  // Regular file
        if (basicAttr.isSymbolicLink()) permissions |= 0120000;  // Symbolic link

        // Then add permission bits
        Set<PosixFilePermission> perms = attr.permissions();

        if (perms.contains(PosixFilePermission.OWNER_READ)) permissions |= 0400;
        if (perms.contains(PosixFilePermission.OWNER_WRITE)) permissions |= 0200;
        if (perms.contains(PosixFilePermission.OWNER_EXECUTE)) permissions |= 0100;

        if (perms.contains(PosixFilePermission.GROUP_READ)) permissions |= 0040;
        if (perms.contains(PosixFilePermission.GROUP_WRITE)) permissions |= 0020;
        if (perms.contains(PosixFilePermission.GROUP_EXECUTE)) permissions |= 0010;

        if (perms.contains(PosixFilePermission.OTHERS_READ)) permissions |= 0004;
        if (perms.contains(PosixFilePermission.OTHERS_WRITE)) permissions |= 0002;
        if (perms.contains(PosixFilePermission.OTHERS_EXECUTE)) permissions |= 0001;

        return permissions;
    }

    // Helper method to check if a string looks like a filehandle name
    private static boolean looksLikeFilehandle(String name) {
        // Check if it's a typical filehandle name (all caps, starts with letter, no path separators)
        return name.matches("^[A-Z_][A-Z0-9_]*$") && !name.contains("/") && !name.contains("\\");
    }

    public static RuntimeList statLastHandle() {
        return stat(lastFileHandle);
    }

    public static RuntimeList lstatLastHandle() {
        return lstat(lastFileHandle);
    }
    
    /**
     * stat with context awareness
     * @param arg the file or filehandle to stat
     * @param ctx the calling context (SCALAR, LIST, VOID, or RUNTIME)
     * @return RuntimeScalar in scalar context, RuntimeList otherwise
     */
    public static RuntimeBase stat(RuntimeScalar arg, int ctx) {
        RuntimeList result = stat(arg);
        if (ctx == RuntimeContextType.SCALAR) {
            // stat in scalar context: empty list -> "", non-empty list -> 1
            return result.isEmpty() ? new RuntimeScalar("") : scalarTrue;
        }
        return result;
    }
    
    /**
     * lstat with context awareness
     * @param arg the file or filehandle to lstat
     * @param ctx the calling context (SCALAR, LIST, VOID, or RUNTIME)
     * @return RuntimeScalar in scalar context, RuntimeList otherwise
     */
    public static RuntimeBase lstat(RuntimeScalar arg, int ctx) {
        RuntimeList result = lstat(arg);
        if (ctx == RuntimeContextType.SCALAR) {
            // lstat in scalar context: empty list -> "", non-empty list -> 1
            return result.isEmpty() ? new RuntimeScalar("") : scalarTrue;
        }
        return result;
    }

    public static RuntimeList stat(RuntimeScalar arg) {
        lastFileHandle.set(arg);
        RuntimeList res = new RuntimeList();

        // Check if the argument is a file handle (GLOB or GLOBREFERENCE)
        if (arg.type == RuntimeScalarType.GLOB || arg.type == RuntimeScalarType.GLOBREFERENCE) {
            RuntimeIO fh = arg.getRuntimeIO();

            // Check if fh is null (invalid filehandle)
            if (fh == null) {
                // Set $! to EBADF (Bad file descriptor) = 9
                getGlobalVariable("main::!").set(9);
                updateLastStat(arg, false, 9);
                return res; // Return empty list
            }

            // Check for closed handle or no valid IO handles
            if ((fh.ioHandle == null || fh.ioHandle instanceof ClosedIOHandle) &&
                    fh.directoryIO == null) {
                // Set $! to EBADF (Bad file descriptor) = 9
                getGlobalVariable("main::!").set(9);
                updateLastStat(arg, false, 9);
                return res; // Return empty list
            }

            // For in-memory file handles (like PerlIO::scalar), we can't stat them
            // They should return EBADF
            getGlobalVariable("main::!").set(9);
            updateLastStat(arg, false, 9);
            return res;
        }

        // Handle string arguments
        String filename = arg.toString();

        // Check if it looks like a filehandle name but isn't actually a filehandle
        if (looksLikeFilehandle(filename)) {
            // Try to get it as a global variable (filehandle)
            RuntimeScalar globVar = null;
            try {
                globVar = getGlobalVariable("main::" + filename);
                if (globVar != null && (globVar.type == RuntimeScalarType.GLOB || globVar.type == RuntimeScalarType.GLOBREFERENCE)) {
                    // It's actually a filehandle, recursively call stat with it
                    return stat(globVar);
                }
            } catch (Exception e) {
                // Ignore, treat as filename
            }

            // It looks like a filehandle but isn't one, return EBADF
            getGlobalVariable("main::!").set(9);
            updateLastStat(arg, false, 9);
            return res;
        }

        // Handle regular filenames
        try {
            Path path = resolvePath(filename);

            // Basic file attributes (similar to some Perl stat fields)
            BasicFileAttributes basicAttr = Files.readAttributes(path, BasicFileAttributes.class);

            // POSIX file attributes (for Unix-like systems)
            PosixFileAttributes posixAttr = Files.readAttributes(path, PosixFileAttributes.class);

            statInternal(res, basicAttr, posixAttr);
            // Clear $! on success
            getGlobalVariable("main::!").set(0);
            updateLastStat(arg, true, 0);
        } catch (NoSuchFileException e) {
            // Set $! to ENOENT (No such file or directory) = 2
            getGlobalVariable("main::!").set(2);
            updateLastStat(arg, false, 2);
        } catch (IOException e) {
            // Returns the empty list if "stat" fails.
            // Set a generic error code for other IO errors
            getGlobalVariable("main::!").set(5); // EIO (Input/output error)
            updateLastStat(arg, false, 5);
        }
        return res;
    }

    public static RuntimeList lstat(RuntimeScalar arg) {
        lastFileHandle.set(arg);
        RuntimeList res = new RuntimeList();

        // Check if the argument is a file handle (GLOB or GLOBREFERENCE)
        if (arg.type == RuntimeScalarType.GLOB || arg.type == RuntimeScalarType.GLOBREFERENCE) {
            RuntimeIO fh = arg.getRuntimeIO();

            // Check if fh is null (invalid filehandle)
            if (fh == null) {
                // Set $! to EBADF (Bad file descriptor) = 9
                getGlobalVariable("main::!").set(9);
                updateLastStat(arg, false, 9);
                return res; // Return empty list
            }

            // Check for closed handle or no valid IO handles
            if ((fh.ioHandle == null || fh.ioHandle instanceof ClosedIOHandle) &&
                    fh.directoryIO == null) {
                // Set $! to EBADF (Bad file descriptor) = 9
                getGlobalVariable("main::!").set(9);
                updateLastStat(arg, false, 9);
                return res; // Return empty list
            }

            // For in-memory file handles (like PerlIO::scalar), we can't lstat them
            // They should return EBADF
            getGlobalVariable("main::!").set(9);
            updateLastStat(arg, false, 9);
            return res;
        }

        // Handle string arguments
        String filename = arg.toString();

        // Check if it looks like a filehandle name but isn't actually a filehandle
        if (looksLikeFilehandle(filename)) {
            // Try to get it as a global variable (filehandle)
            RuntimeScalar globVar = null;
            try {
                globVar = getGlobalVariable("main::" + filename);
                if (globVar != null && (globVar.type == RuntimeScalarType.GLOB || globVar.type == RuntimeScalarType.GLOBREFERENCE)) {
                    // It's actually a filehandle, recursively call lstat with it
                    return lstat(globVar);
                }
            } catch (Exception e) {
                // Ignore, treat as filename
            }

            // It looks like a filehandle but isn't one, return EBADF
            getGlobalVariable("main::!").set(9);
            updateLastStat(arg, false, 9);
            return res;
        }

        // Handle regular filenames
        try {
            Path path = resolvePath(filename);

            // Basic attributes without following symlink
            BasicFileAttributes basicAttr = Files.readAttributes(path,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);

            // POSIX attributes without following symlink
            PosixFileAttributes posixAttr = Files.readAttributes(path,
                    PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);

            statInternal(res, basicAttr, posixAttr);
            // Clear $! on success
            getGlobalVariable("main::!").set(0);
            updateLastStat(arg, true, 0);
        } catch (NoSuchFileException e) {
            // Set $! to ENOENT (No such file or directory) = 2
            getGlobalVariable("main::!").set(2);
            updateLastStat(arg, false, 2);
        } catch (IOException e) {
            // Returns the empty list if "lstat" fails.
            // Set a generic error code for other IO errors
            getGlobalVariable("main::!").set(5); // EIO (Input/output error)
            updateLastStat(arg, false, 5);
        }
        return res;
    }

    public static void statInternal(RuntimeList res, BasicFileAttributes basicAttr, PosixFileAttributes posixAttr) {
        // Emulating Perl's stat return list
        res.add(scalarUndef);                            // 0 dev (device number) - not directly available in Java
        res.add(scalarUndef);                            // 1 ino (inode number) - not directly available in Java
        res.add(getScalarInt(getPermissionsOctal(basicAttr, posixAttr))); // 2 mode (file mode/permissions)
        res.add(getScalarInt(1));                        // 3 nlink (number of hard links) - not easily obtainable in standard Java
        res.add(scalarUndef);                            // 4 uid (user ID) - posixAttr.owner().getName() returns String
        res.add(scalarUndef);                            // 5 gid (group ID) - posixAttr.group().getName() returns String
        res.add(scalarUndef);                            // 6 rdev (device identifier for special files) - not available
        res.add(getScalarInt(basicAttr.size()));         // 7 size (file size in bytes)
        res.add(getScalarInt(basicAttr.lastAccessTime().toMillis() / 1000)); // 8 atime (last access time)
        res.add(getScalarInt(basicAttr.lastModifiedTime().toMillis() / 1000)); // 9 mtime (last modified time)
        res.add(getScalarInt(basicAttr.creationTime().toMillis() / 1000));    // 10 ctime (file creation time)
        res.add(scalarUndef);                            // 11 blksize (preferred I/O block size) - not directly available
        res.add(scalarUndef);                            // 12 blocks (number of blocks) - not directly available
    }
}
